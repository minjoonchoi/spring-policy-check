package io.github.minjoonchoi.policy

import groovy.json.JsonSlurper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class AcceptanceTest(private val name: String) {
    companion object {
        private val repository = File(System.getProperty("policy.repository"))

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<String>> = File(repository, "tests/fixtures").listFiles()!!
            .filter { it.isDirectory }.sortedBy { it.name }.map { arrayOf(it.name) }
    }

    @Test fun actualKotlinProducesExpectedDiagnostics() {
        val directory = File(repository, "tests/fixtures/$name")
        val expected = JsonSlurper().parse(File(directory, "expected.json"), "UTF-8") as Map<*, *>
        val allRules = PolicyConfig.load(File(repository, "policy.yml").readText())
        val config = allRules.copy(rules = allRules.rules.filter { it.id == expected["rule"] })
        val inputs = directory.listFiles()!!.filter { it.extension == "kt" }.map {
            SourceInput(it.name, expected["module"] as String, it.readText())
        }
        val actual = PolicyEngine.analyze(config, inputs).diagnostics
        val expectations = expected["expected"] as List<*>
        assertEquals("$name: $actual", expectations.size, actual.size)
        actual.forEachIndexed { i, diagnostic ->
            val want = expectations[i] as Map<*, *>
            assertEquals(expected["rule"], diagnostic.rule)
            for (key in listOf("code", "status", "severity")) assertEquals("$name: $diagnostic", want[key], diagnostic.toMap()[key])
            assertTrue(diagnostic.line > 0 && diagnostic.column > 0 && diagnostic.evidence.isNotEmpty())
            (want["suggestion_contains"] as? String)?.let {
                assertTrue("$name: ${diagnostic.suggestion}", diagnostic.suggestion?.contains(it) == true)
            }
            if (diagnostic.status == "skipped") assertNull(diagnostic.suggestion)
        }
    }
}
