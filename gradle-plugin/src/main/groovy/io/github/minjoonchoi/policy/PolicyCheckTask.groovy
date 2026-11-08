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
@DisableCachingByDefault(because = 'Diagnostic paths are relative to the consuming project; only local up-to-date checks are enabled.')
abstract class PolicyCheckTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getPolicyFile()

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getSourceFiles()

    @Classpath
    abstract ConfigurableFileCollection getAnalyzerClasspath()

    @Input
    abstract Property<String> getModulePath()

    @Internal
    abstract DirectoryProperty getProjectDirectory()

    @OutputFile
    abstract RegularFileProperty getReportFile()

    @Inject
    abstract WorkerExecutor getWorkerExecutor()

    @TaskAction
    void checkPolicy() {
        def queue = workerExecutor.classLoaderIsolation { spec ->
            spec.classpath.from(this.analyzerClasspath)
        }
        queue.submit(AnalyzeWorkAction) { p ->
            p.policyFile.set(this.policyFile)
            p.sourceFiles.from(this.sourceFiles)
            p.modulePath.set(this.modulePath)
            p.projectDirectory.set(this.projectDirectory)
            p.reportFile.set(this.reportFile)
        }
        queue.await()
    }
}
