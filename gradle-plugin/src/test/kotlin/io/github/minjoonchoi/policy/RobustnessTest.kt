package io.github.minjoonchoi.policy

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RobustnessTest {
    private fun yaml(): String = File(System.getProperty("policy.repository"), "policy.yml").readText()
    private fun source(name: String, text: String) = SourceInput(name, ":sample", text)
    private fun analyze(inputs: List<SourceInput>): AnalysisReport = PolicyEngine.analyze(PolicyConfig.load(yaml()), inputs)

    @Test fun invalidVersion() { assertThrows(Exception::class.java) { PolicyConfig.load(yaml().replaceFirst("version: 1", "version: 9")) } }
    @Test fun unknownKey() { assertThrows(Exception::class.java) { PolicyConfig.load(yaml() + "\nmisspelled: true\n") } }
    @Test fun unknownNestedKey() { assertThrows(Exception::class.java) { PolicyConfig.load(yaml().replaceFirst("declaration: class", "declaration: class, typo: true")) } }
    @Test fun invalidRegex() { assertThrows(Exception::class.java) { PolicyConfig.load(yaml().replace("^[A-Z][A-Za-z0-9]*Controller$", "[")) } }
    @Test fun emptyRules() { assertThrows(Exception::class.java) { PolicyConfig.load("version: 1\nrules: []") } }
    @Test fun duplicateRule() { assertThrows(Exception::class.java) { PolicyConfig.load(yaml().replace("id: NAME002", "id: NAME001")) } }
    @Test fun duplicateYamlKey() { assertThrows(Exception::class.java) { PolicyConfig.load(yaml() + "\nversion: 1\n") } }
    @Test fun unsafeYamlTag() { assertThrows(Exception::class.java) { PolicyConfig.load("!!java.net.URL [https://example.com]" ) } }
    @Test fun optionalListsRejectExplicitNull() {
        for (field in listOf("allowed-types", "restricted-calls")) {
            val policy = """
                version: 1
                rules:
                  - id: AUTH
                    severity: error
                    scope: { modules: [':sample'], package-pattern: '.*' }
                    check:
                      type: auth-boundary
                      allowed-package-pattern: '.*'
                      allowed-supertypes: []
                      token-headers: [Authorization]
                      $field: null
                    message: message
                    suggestion: suggestion
            """.trimIndent()
            assertThrows(IllegalArgumentException::class.java) { PolicyConfig.load(policy) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyConfig.load(yaml().replace(Regex("exclude-path-patterns:[\\s\\S]*?(?=rules:)"), "exclude-path-patterns: null\n"))
        }
    }
    @Test fun invalidKotlin() { assertThrows(Exception::class.java) { analyze(listOf(source("Broken.kt", "class { fun ("))) } }
    @Test fun emptyInput() { assertThrows(Exception::class.java) { analyze(emptyList()) } }
    @Test fun deterministicOrder() {
        val a = source("A.kt", "package com.example.api.controller\nclass WrongA")
        val b = source("B.kt", "package com.example.api.controller\nclass WrongB")
        assertEquals(analyze(listOf(a, b)), analyze(listOf(b, a)))
    }
    @Test fun excludesBeforeParsing() {
        val report = analyze(listOf(source("generated/Bad.kt", "invalid !!"), source("Good.kt", "package com.example.api.controller\nclass GoodController")))
        assertEquals(1, report.files)
        assertTrue(report.diagnostics.isEmpty())
    }
    @Test fun allExcludedFails() { assertThrows(Exception::class.java) { analyze(listOf(source("build/Bad.kt", "broken!"))) } }
    @Test fun duplicateClassFails() { assertThrows(Exception::class.java) { analyze(listOf(source("A.kt", "package a\nclass X"), source("B.kt", "package a\nclass X"))) } }
    @Test fun unicodeLocation() {
        val report = analyze(listOf(source("Unicode.kt", "package com.example.api.controller\n/* 😀 한글 */ class Wrong")))
        assertEquals(2, report.diagnostics.first().line)
        assertEquals(18, report.diagnostics.first().column)
    }
    @Test fun localConstantShadowingIsNotMistakenForHeader() {
        val report = analyze(listOf(source("Auth.kt", """
            package com.example.application
            import jakarta.servlet.http.HttpServletRequest
            const val HEADER = "Authorization"
            class Handler { fun read(request: HttpServletRequest, HEADER: String) = request.getHeader(HEADER) }
        """.trimIndent())))
        assertEquals(listOf("DYNAMIC_HEADER"), report.diagnostics.map { it.code })
    }
}
