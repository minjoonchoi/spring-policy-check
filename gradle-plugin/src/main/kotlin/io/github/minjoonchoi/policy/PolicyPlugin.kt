package io.github.minjoonchoi.policy

import org.gradle.api.Plugin
import org.gradle.api.Project

class PolicyPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val analyzer = project.configurations.create("springPolicyAnalyzer") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.isVisible = false
            it.description = "Kotlin syntax parser and YAML reader for the policy worker."
        }
        analyzer.defaultDependencies { dependencies ->
            dependencies.add(project.dependencies.create("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21"))
            dependencies.add(project.dependencies.create("org.yaml:snakeyaml:2.3"))
        }
        project.tasks.withType(PolicyCheckTask::class.java).configureEach { task ->
            task.group = "verification"
            task.policyFile.convention(project.rootProject.layout.projectDirectory.file("policy.yml"))
            task.modulePath.convention(project.path)
            task.projectDirectory.convention(project.layout.projectDirectory)
            task.analyzerClasspath.from(analyzer)
            task.reportFile.convention(project.layout.buildDirectory.file("reports/policy/${task.name}.json"))
        }
        val check = project.tasks.register("policyCheck", PolicyCheckTask::class.java) { task ->
            task.description = "Check Kotlin Spring policies inside the Gradle JVM."
            task.sourceFiles.from(project.fileTree("src/main/kotlin") { it.include("**/*.kt") })
        }
        project.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(check) }
    }
}
