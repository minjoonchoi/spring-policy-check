package io.github.minjoonchoi.policy

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/** Runs the analyzer in an isolated classloader in the existing Gradle JVM. */
@DisableCachingByDefault(because = "Diagnostic paths are relative to the consuming project; only local up-to-date checks are enabled.")
abstract class PolicyCheckTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policyFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Classpath
    abstract val analyzerClasspath: ConfigurableFileCollection

    @get:Input
    abstract val modulePath: Property<String>

    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun checkPolicy() {
        val queue = workerExecutor.classLoaderIsolation { spec -> spec.classpath.from(analyzerClasspath) }
        queue.submit(AnalyzeWorkAction::class.java) { parameters ->
            parameters.policyFile.set(policyFile)
            parameters.sourceFiles.from(sourceFiles)
            parameters.modulePath.set(modulePath)
            parameters.projectDirectory.set(projectDirectory)
            parameters.reportFile.set(reportFile)
        }
        queue.await()
    }
}
