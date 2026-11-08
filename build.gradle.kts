import io.github.minjoonchoi.policy.PolicyCheckTask

plugins {
    base
    id("io.github.minjoonchoi.spring-policy")
}
repositories { mavenCentral() }

// This checker repository demonstrates valid Kotlin input with its default task.
tasks.named<PolicyCheckTask>("policyCheck") {
    modulePath.set(":sample")
    sourceFiles.setFrom(
        fileTree("samples/valid") { include("**/*.kt") },
        fileTree("samples/common") { include("**/*.kt") },
    )
    reportFile.set(layout.buildDirectory.file("reports/policy/valid.json"))
}
mapOf("policyWarningCheck" to "warnings", "policyViolationCheck" to "violations").forEach { (taskName, sample) ->
    tasks.register<PolicyCheckTask>(taskName) {
        description = "Check samples/$sample using the Gradle JVM."
        modulePath.set(":sample")
        sourceFiles.from(
            fileTree("samples/$sample") { include("**/*.kt") },
            fileTree("samples/common") { include("**/*.kt") },
        )
        reportFile.set(layout.buildDirectory.file("reports/policy/$sample.json"))
    }
}
tasks.register("policyGoodCheck") { group = "verification"; dependsOn("policyCheck") }
tasks.register("policySpecTest") {
    group = "verification"
    description = "Run Kotlin source specifications, query tests and Gradle TestKit tests."
    dependsOn(gradle.includedBuild("gradle-plugin").task(":test"))
}
tasks.check { dependsOn("policySpecTest") }
tasks.assemble { dependsOn(gradle.includedBuild("gradle-plugin").task(":assemble")) }
