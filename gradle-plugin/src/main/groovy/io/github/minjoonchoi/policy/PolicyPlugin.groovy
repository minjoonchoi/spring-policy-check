package io.github.minjoonchoi.policy

import org.gradle.api.Plugin
import org.gradle.api.Project

class PolicyPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        def analyzer = project.configurations.create('springPolicyAnalyzer') {
            canBeConsumed = false
            canBeResolved = true
            visible = false
            description = 'Kotlin syntax parser and YAML reader for the policy worker.'
        }
        analyzer.defaultDependencies { deps ->
            deps.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21'))
            deps.add(project.dependencies.create('org.yaml:snakeyaml:2.3'))
        }
        project.tasks.withType(PolicyCheckTask).configureEach { task ->
            task.group = 'verification'
            task.policyFile.convention(project.rootProject.layout.projectDirectory.file('policy.yml'))
            task.modulePath.convention(project.path)
            task.projectDirectory.convention(project.layout.projectDirectory)
            task.analyzerClasspath.from(analyzer)
            task.reportFile.convention(project.layout.buildDirectory.file("reports/policy/${task.name}.json"))
        }
        def check = project.tasks.register('policyCheck', PolicyCheckTask) {
            description = 'Check Kotlin Spring policies inside the Gradle JVM.'
            sourceFiles.from(project.fileTree('src/main/kotlin') { include '**/*.kt' })
        }
        project.tasks.matching { it.name == 'check' }.configureEach { dependsOn(check) }
    }
}
