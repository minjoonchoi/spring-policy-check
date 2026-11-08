package io.github.minjoonchoi.policy

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.nio.file.Files
import java.util.Locale

abstract class AnalyzeWorkAction : WorkAction<AnalyzeWorkAction.Parameters> {
    interface Parameters : WorkParameters {
        val policyFile: RegularFileProperty
        val sourceFiles: ConfigurableFileCollection
        val modulePath: Property<String>
        val projectDirectory: DirectoryProperty
        val reportFile: RegularFileProperty
    }

    override fun execute() {
        val output = parameters.reportFile.get().asFile
        val report = try {
            Files.deleteIfExists(output.toPath())
            val config = PolicyConfig.load(parameters.policyFile.get().asFile.readText())
            val base = parameters.projectDirectory.get().asFile.toPath().toAbsolutePath().normalize()
            val inputs = parameters.sourceFiles.files.filter { it.extension == "kt" }.map { file ->
                val path = base.relativize(file.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/')
                SourceInput(path, parameters.modulePath.get(), file.readText())
            }
            PolicyEngine.analyze(config, inputs).also {
                Files.createDirectories(output.toPath().parent)
                output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(it.toMap())) + "\n")
            }
        } catch (e: Exception) {
            throw GradleException("ANALYSIS_ERROR: ${e.message}", e)
        }
        val logger = Logging.getLogger(AnalyzeWorkAction::class.java)
        report.diagnostics.forEach { diagnostic ->
            val level = if (diagnostic.status == "skipped") "SKIP" else diagnostic.severity.uppercase(Locale.ROOT)
            logger.lifecycle("${diagnostic.file}:${diagnostic.line}:${diagnostic.column} [$level] ${diagnostic.rule} ${diagnostic.code} — ${diagnostic.message}")
            diagnostic.suggestion?.let { logger.lifecycle("  제안: $it") }
        }
        val errors = report.diagnostics.count { it.severity == "error" }
        val skipped = report.diagnostics.count { it.status == "skipped" }
        logger.lifecycle("${report.files} files; ${report.applicableRules.size} applicable rules; $errors errors; ${report.diagnostics.size} diagnostics; $skipped skipped")
        if (errors > 0) throw GradleException("POLICY_VIOLATION: $errors error(s). See $output")
    }
}
