package io.github.minjoonchoi.policy

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import static org.junit.Assert.*

class GradleTaskTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder()
    private File setup(String sample = 'valid', boolean kotlinDsl = false) {
        File project = temporary.newFolder()
        new File(project, 'settings.gradle').text = "rootProject.name = 'consumer'\n"
        if (kotlinDsl) {
            new File(project, 'build.gradle.kts').text = '''
import io.github.minjoonchoi.policy.PolicyCheckTask
plugins { base; id("io.github.minjoonchoi.spring-policy") }
repositories { mavenCentral() }
tasks.named<PolicyCheckTask>("policyCheck") { modulePath.set(":sample") }
'''
        } else {
            new File(project, 'build.gradle').text = '''
plugins { id 'base'; id 'io.github.minjoonchoi.spring-policy' }
repositories { mavenCentral() }
tasks.named('policyCheck') { modulePath = ':sample' }
'''
        }
        File root = new File(System.getProperty('policy.repository'))
        new File(project, 'policy.yml').text = new File(root, 'policy.yml').getText('UTF-8')
        def sources = new File(project, 'src/main/kotlin')
        sources.mkdirs()
        [sample, 'common'].each { dir ->
            new File(root, "samples/${dir}").listFiles().findAll { it.name.endsWith('.kt') }.each {
                new File(sources, it.name).text = it.getText('UTF-8')
            }
        }
        project
    }
    private GradleRunner runner(File project, String... tasks) {
        def arguments = tasks.toList() + ['--configuration-cache', '--console=plain', '--max-workers=2', '--stacktrace']
        GradleRunner.create().withProjectDir(project).withPluginClasspath()
            .withGradleInstallation(new File(System.getProperty('policy.gradle.home')))
            .withArguments(arguments)
    }
    private Map report(File project) {
        new JsonSlurper().parse(new File(project, 'build/reports/policy/policyCheck.json'), 'UTF-8')
    }
    @Test void goodInputAndConfigurationCacheReuse() {
        def project = setup()
        assertEquals(TaskOutcome.SUCCESS, runner(project, 'policyCheck').build().task(':policyCheck').outcome)
        assertTrue(report(project).diagnostics.empty)
        def second = runner(project, 'policyCheck').build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(':policyCheck').outcome)
        assertTrue(second.output.contains('Reusing configuration cache.'))
    }
    @Test void warningsSucceed() {
        def project = setup('warnings')
        def result = runner(project, 'policyCheck').build()
        assertEquals(TaskOutcome.SUCCESS, result.task(':policyCheck').outcome)
        assertEquals(['JPA002', 'JPA001'], report(project).diagnostics*.rule)
        assertTrue(result.output.contains('PREFER_DERIVED'))
    }
    @Test void errorsFailAndReportIsWritten() {
        def project = setup('violations')
        def result = runner(project, 'policyCheck').buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(':policyCheck').outcome)
        assertEquals(2, report(project).diagnostics.count { it.severity == 'error' })
        assertTrue(result.output.contains('POLICY_VIOLATION'))
    }
    @Test void malformedPolicyDeletesStaleReport() {
        def project = setup()
        runner(project, 'policyCheck').build()
        new File(project, 'policy.yml').text = 'version: 99\nrules: []'
        assertTrue(runner(project, 'policyCheck').buildAndFail().output.contains('ANALYSIS_ERROR'))
        assertFalse(new File(project, 'build/reports/policy/policyCheck.json').exists())
    }
    @Test void sourceChangesInvalidateUpToDateState() {
        def project = setup()
        runner(project, 'policyCheck').build()
        new File(project, 'src/main/kotlin/Bad.kt').text = 'package com.example.api.controller\nclass Bad'
        assertEquals(TaskOutcome.FAILED, runner(project, 'policyCheck').buildAndFail().task(':policyCheck').outcome)
        assertEquals(['NAME_MISMATCH'], report(project).diagnostics*.code)
    }
    @Test void checkLifecycleRunsPolicy() {
        def project = setup()
        assertEquals(TaskOutcome.SUCCESS, runner(project, 'check').build().task(':policyCheck').outcome)
    }
    @Test void kotlinDslConsumerWorks() {
        def project = setup('valid', true)
        assertEquals(TaskOutcome.SUCCESS, runner(project, 'policyCheck').build().task(':policyCheck').outcome)
    }
    @Test void emptySourcesAreAnAnalysisError() {
        def project = setup()
        new File(project, 'src/main/kotlin').deleteDir()
        assertTrue(runner(project, 'policyCheck').buildAndFail().output.contains('No Kotlin source files'))
    }
    @Test void modulePathsAndReportsAreIsolated() {
        def project = setup()
        new File(project, 'settings.gradle').append("include ':api', ':application'\n")
        new File(project, 'build.gradle').append('''
subprojects {
    apply plugin: 'io.github.minjoonchoi.spring-policy'
    repositories { mavenCentral() }
}
''')
        def api = new File(project, 'api/src/main/kotlin'); api.mkdirs()
        def application = new File(project, 'application/src/main/kotlin'); application.mkdirs()
        new File(api, 'Bad.kt').text = 'package com.example.api.controller\nclass Bad'
        new File(application, 'Good.kt').text = 'package com.example.application.command.service\nclass GoodCommandService'
        assertEquals(TaskOutcome.SUCCESS, runner(project, ':application:policyCheck').build().task(':application:policyCheck').outcome)
        assertEquals(TaskOutcome.FAILED, runner(project, ':api:policyCheck').buildAndFail().task(':api:policyCheck').outcome)
        assertEquals(['NAME001'], report(new File(project, 'api')).diagnostics*.rule)
        assertTrue(report(new File(project, 'application')).diagnostics.empty)
    }
}
