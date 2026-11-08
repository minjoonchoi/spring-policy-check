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

abstract class AnalyzeWorkAction implements WorkAction<Parameters> {
    static interface Parameters extends WorkParameters {
        RegularFileProperty getPolicyFile()
        ConfigurableFileCollection getSourceFiles()
        Property<String> getModulePath()
        DirectoryProperty getProjectDirectory()
        RegularFileProperty getReportFile()
    }

    @Override
    void execute() {
        def p = parameters
        File output = p.reportFile.get().asFile
        Map report
        try {
            Files.deleteIfExists(output.toPath())
            def config = PolicyConfig.load(p.policyFile.get().asFile.getText('UTF-8'))
            def base = p.projectDirectory.get().asFile.toPath().toAbsolutePath().normalize()
            def inputs = p.sourceFiles.files.findAll { it.name.endsWith('.kt') }.collect { file ->
                def path = base.relativize(file.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/')
                [path: path, module: p.modulePath.get(), text: file.getText('UTF-8')]
            }
            report = PolicyEngine.analyze(config, inputs)
            Files.createDirectories(output.toPath().parent)
            output.setText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n', 'UTF-8')
        } catch (Exception e) {
            throw new GradleException("ANALYSIS_ERROR: ${e.message}", e)
        }
        def logger = Logging.getLogger(AnalyzeWorkAction)
        report.diagnostics.each { d ->
            def level = d.status == 'skipped' ? 'SKIP' : d.severity.toUpperCase(Locale.ROOT)
            logger.lifecycle("${d.file}:${d.line}:${d.column} [${level}] ${d.rule} ${d.code} — ${d.message}")
            if (d.suggestion != null) logger.lifecycle("  제안: ${d.suggestion}")
        }
        int errors = report.diagnostics.count { it.severity == 'error' }
        logger.lifecycle("${report.files} files; ${report['applicable-rules'].size()} applicable rules; ${errors} errors; ${report.diagnostics.size()} diagnostics; ${report.diagnostics.count { it.status == 'skipped' }} skipped")
        if (errors) throw new GradleException("POLICY_VIOLATION: ${errors} error(s). See ${output}")
    }
}
