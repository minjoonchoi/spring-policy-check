package io.github.minjoonchoi.policy

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradleTaskTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun setup(sample: String = "valid", kotlinDsl: Boolean = true): File {
        val project = temporary.newFolder()
        File(project, "settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n")
        if (kotlinDsl) {
            File(project, "build.gradle.kts").writeText("""
                import io.github.minjoonchoi.policy.PolicyCheckTask
                plugins { base; id("io.github.minjoonchoi.spring-policy") }
                repositories { mavenCentral() }
                tasks.named<PolicyCheckTask>("policyCheck") { modulePath.set(":sample") }
            """.trimIndent())
        } else {
            // Compatibility coverage for existing Groovy consumers of the Kotlin plugin.
            File(project, "build.gradle").writeText("""
                plugins { id 'base'; id 'io.github.minjoonchoi.spring-policy' }
                repositories { mavenCentral() }
                tasks.named('policyCheck') { modulePath = ':sample' }
            """.trimIndent())
        }
        val root = File(System.getProperty("policy.repository"))
        File(root, "policy.yml").copyTo(File(project, "policy.yml"))
        val sources = File(project, "src/main/kotlin").apply { mkdirs() }
        for (directory in listOf(sample, "common")) {
            File(root, "samples/$directory").listFiles()!!.filter { it.extension == "kt" }.forEach { it.copyTo(File(sources, it.name)) }
        }
        return project
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath()
        .withGradleInstallation(File(System.getProperty("policy.gradle.home")))
        .withArguments(tasks.toList() + listOf("--configuration-cache", "--console=plain", "--max-workers=2", "--stacktrace"))

    private fun diagnostics(project: File): List<Map<*, *>> {
        val report = JsonSlurper().parse(File(project, "build/reports/policy/policyCheck.json"), "UTF-8") as Map<*, *>
        return (report["diagnostics"] as List<*>).map { it as Map<*, *> }
    }

    @Test fun goodInputAndConfigurationCacheReuse() {
        val project = setup()
        assertEquals(TaskOutcome.SUCCESS, runner(project, "policyCheck").build().task(":policyCheck")?.outcome)
        assertTrue(diagnostics(project).isEmpty())
        val second = runner(project, "policyCheck").build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":policyCheck")?.outcome)
        assertTrue(second.output.contains("Reusing configuration cache."))
    }
    @Test fun warningsSucceed() {
        val project = setup("warnings")
        val result = runner(project, "policyCheck").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":policyCheck")?.outcome)
        assertEquals(listOf("JPA002", "JPA001"), diagnostics(project).map { it["rule"] })
        assertTrue(result.output.contains("PREFER_DERIVED"))
    }
    @Test fun errorsFailAndReportIsWritten() {
        val project = setup("violations")
        val result = runner(project, "policyCheck").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":policyCheck")?.outcome)
        assertEquals(2, diagnostics(project).count { it["severity"] == "error" })
        assertTrue(result.output.contains("POLICY_VIOLATION"))
    }
    @Test fun malformedPolicyDeletesStaleReport() {
        val project = setup()
        runner(project, "policyCheck").build()
        File(project, "policy.yml").writeText("version: 99\nrules: []")
        assertTrue(runner(project, "policyCheck").buildAndFail().output.contains("ANALYSIS_ERROR"))
        assertFalse(File(project, "build/reports/policy/policyCheck.json").exists())
    }
    @Test fun sourceChangesInvalidateUpToDateState() {
        val project = setup()
        runner(project, "policyCheck").build()
        File(project, "src/main/kotlin/Bad.kt").writeText("package com.example.api.controller\nclass Bad")
        assertEquals(TaskOutcome.FAILED, runner(project, "policyCheck").buildAndFail().task(":policyCheck")?.outcome)
        assertEquals(listOf("NAME_MISMATCH"), diagnostics(project).map { it["code"] })
    }
    @Test fun checkLifecycleRunsPolicy() {
        val project = setup()
        assertEquals(TaskOutcome.SUCCESS, runner(project, "check").build().task(":policyCheck")?.outcome)
    }
    @Test fun groovyDslConsumerStillWorks() {
        val project = setup(kotlinDsl = false)
        assertEquals(TaskOutcome.SUCCESS, runner(project, "policyCheck").build().task(":policyCheck")?.outcome)
    }
    @Test fun kotlinDslRegistersTaskWithCustomInputsAndOutput() {
        val project = setup()
        File(project, "subset").mkdirs()
        File(project, "subset/Bad.kt").writeText("package com.example.api.controller\nclass Bad")
        File(project, "build.gradle.kts").appendText("\n" + """
            tasks.register<PolicyCheckTask>("subsetPolicy") {
                modulePath.set(":sample")
                sourceFiles.from(fileTree("subset") { include("**/*.kt") })
                reportFile.set(layout.buildDirectory.file("reports/custom.json"))
            }
        """.trimIndent())
        assertEquals(TaskOutcome.FAILED, runner(project, "subsetPolicy").buildAndFail().task(":subsetPolicy")?.outcome)
        val report = JsonSlurper().parse(File(project, "build/reports/custom.json"), "UTF-8") as Map<*, *>
        assertEquals(1, report["files"])
        val diagnostic = (report["diagnostics"] as List<*>).single() as Map<*, *>
        assertEquals("NAME_MISMATCH", diagnostic["code"])
        assertEquals("subset/Bad.kt", diagnostic["file"])
        assertFalse(File(project, "build/reports/policy/policyCheck.json").exists())
    }
    @Test fun emptySourcesAreAnAnalysisError() {
        val project = setup()
        File(project, "src/main/kotlin").deleteRecursively()
        assertTrue(runner(project, "policyCheck").buildAndFail().output.contains("No Kotlin source files"))
    }
    @Test fun modulePathsAndReportsAreIsolated() {
        val project = setup()
        File(project, "settings.gradle.kts").appendText("include(\":api\", \":application\")\n")
        File(project, "build.gradle.kts").appendText("\n" + """

            subprojects {
                apply(plugin = "io.github.minjoonchoi.spring-policy")
                repositories { mavenCentral() }
            }
        """.trimIndent())
        val api = File(project, "api/src/main/kotlin").apply { mkdirs() }
        val application = File(project, "application/src/main/kotlin").apply { mkdirs() }
        File(api, "Bad.kt").writeText("package com.example.api.controller\nclass Bad")
        File(application, "Good.kt").writeText("package com.example.application.command.service\nclass GoodCommandService")
        assertEquals(TaskOutcome.SUCCESS, runner(project, ":application:policyCheck").build().task(":application:policyCheck")?.outcome)
        assertEquals(TaskOutcome.FAILED, runner(project, ":api:policyCheck").buildAndFail().task(":api:policyCheck")?.outcome)
        assertEquals(listOf("NAME001"), diagnostics(File(project, "api")).map { it["rule"] })
        assertTrue(diagnostics(File(project, "application")).isEmpty())
    }
}
