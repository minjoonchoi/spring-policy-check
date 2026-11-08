package io.github.minjoonchoi.policy

import org.junit.Test
import static org.junit.Assert.*

class RobustnessTest {
    private static String yaml() { new File(System.getProperty('policy.repository'), 'policy.yml').getText('UTF-8') }
    private static Map source(String name, String text) { [path: name, module: ':sample', text: text] }
    private static Map analyze(List inputs) { PolicyEngine.analyze(PolicyConfig.load(yaml()), inputs) }
    @Test void invalidVersion() { assertThrows(Exception) { PolicyConfig.load(yaml().replaceFirst('version: 1', 'version: 9')) } }
    @Test void unknownKey() { assertThrows(Exception) { PolicyConfig.load(yaml() + '\nmisspelled: true\n') } }
    @Test void unknownNestedKey() { assertThrows(Exception) { PolicyConfig.load(yaml().replaceFirst('declaration: class', 'declaration: class, typo: true')) } }
    @Test void invalidRegex() { assertThrows(Exception) { PolicyConfig.load(yaml().replace('^[A-Z][A-Za-z0-9]*Controller$', '[')) } }
    @Test void emptyRules() { assertThrows(Exception) { PolicyConfig.load('version: 1\nrules: []') } }
    @Test void duplicateRule() { assertThrows(Exception) { PolicyConfig.load(yaml().replace('id: NAME002', 'id: NAME001')) } }
    @Test void duplicateYamlKey() { assertThrows(Exception) { PolicyConfig.load(yaml() + '\nversion: 1\n') } }
    @Test void unsafeYamlTag() { assertThrows(Exception) { PolicyConfig.load('!!java.net.URL [https://example.com]') } }
    @Test void invalidKotlin() { assertThrows(Exception) { analyze([source('Broken.kt', 'class { fun (')]) } }
    @Test void emptyInput() { assertThrows(Exception) { analyze([]) } }
    @Test void deterministicOrder() {
        def a = source('A.kt', 'package com.example.api.controller\nclass WrongA')
        def b = source('B.kt', 'package com.example.api.controller\nclass WrongB')
        assertEquals(analyze([a, b]), analyze([b, a]))
    }
    @Test void excludesBeforeParsing() {
        def r = analyze([source('generated/Bad.kt', 'invalid !!'), source('Good.kt', 'package com.example.api.controller\nclass GoodController')])
        assertEquals(1, r.files)
        assertTrue(r.diagnostics.empty)
    }
    @Test void allExcludedFails() { assertThrows(Exception) { analyze([source('build/Bad.kt', 'broken!')]) } }
    @Test void duplicateClassFails() { assertThrows(Exception) { analyze([source('A.kt', 'package a\nclass X'), source('B.kt', 'package a\nclass X')]) } }
    @Test void unicodeLocation() {
        def r = analyze([source('Unicode.kt', 'package com.example.api.controller\n/* 😀 한글 */ class Wrong')])
        assertEquals(2, r.diagnostics.first().line)
        assertEquals(18, r.diagnostics.first().column)
    }
    @Test void localConstantShadowingIsNotMistakenForHeader() {
        def r = analyze([source('Auth.kt', '''package com.example.application
import jakarta.servlet.http.HttpServletRequest
const val HEADER = "Authorization"
class Handler { fun read(request: HttpServletRequest, HEADER: String) = request.getHeader(HEADER) }
''')])
        assertEquals(['DYNAMIC_HEADER'], r.diagnostics*.code)
    }
}
