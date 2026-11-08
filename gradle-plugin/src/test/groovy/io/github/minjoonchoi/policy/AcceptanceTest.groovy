package io.github.minjoonchoi.policy

import groovy.json.JsonSlurper
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import static org.junit.Assert.*

@RunWith(Parameterized)
class AcceptanceTest {
    static final File REPOSITORY = new File(System.getProperty('policy.repository'))
    @Parameterized.Parameters(name = '{0}')
    static Collection<Object[]> cases() {
        new File(REPOSITORY, 'tests/fixtures').listFiles().findAll { it.directory }.sort { it.name }.collect { [it.name] as Object[] }
    }
    final String name
    AcceptanceTest(String name) { this.name = name }

    @Test void actualKotlinProducesExpectedDiagnostics() {
        def directory = new File(REPOSITORY, "tests/fixtures/${name}")
        def expected = new JsonSlurper().parse(new File(directory, 'expected.json'), 'UTF-8')
        def config = PolicyConfig.load(new File(REPOSITORY, 'policy.yml').getText('UTF-8'))
        config.rules = config.rules.findAll { it.id == expected.rule }
        def inputs = directory.listFiles().findAll { it.name.endsWith('.kt') }.collect {
            [path: it.name, module: expected.module, text: it.getText('UTF-8')]
        }
        def actual = PolicyEngine.analyze(config, inputs).diagnostics
        assertEquals("${name}: ${actual}", expected.expected.size(), actual.size())
        actual.eachWithIndex { d, i ->
            def want = expected.expected[i]
            assertEquals(expected.rule, d.rule)
            ['code', 'status', 'severity'].each { assertEquals("${name}: ${d}", want[it], d[it]) }
            assertTrue(d.line > 0 && d.column > 0 && !d.evidence.empty)
            if (want.suggestion_contains) assertTrue("${name}: ${d.suggestion}", d.suggestion?.contains(want.suggestion_contains) ?: false)
            if (d.status == 'skipped') assertNull(d.suggestion)
        }
    }
}
