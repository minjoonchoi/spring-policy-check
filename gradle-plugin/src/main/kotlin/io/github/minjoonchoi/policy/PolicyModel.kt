package io.github.minjoonchoi.policy

data class SourceInput(val path: String, val module: String, val text: String)

data class PolicyRule(
    val id: String,
    val severity: String,
    val scope: RuleScope,
    val check: PolicyCheck,
    val message: String,
    val suggestion: String,
)

data class RuleScope(val modules: List<String>, val packagePattern: Regex)

sealed interface PolicyCheck {
    data class Name(val declaration: String, val namePattern: Regex) : PolicyCheck
    data class AuthBoundary(
        val allowedPackagePattern: Regex,
        val allowedSupertypes: List<String>,
        val allowedTypes: List<String>,
        val tokenHeaders: List<String>,
        val restrictedCalls: List<String>,
    ) : PolicyCheck
    data class PreferDerivedQuery(val maxPredicates: Int, val maxMethodLength: Int) : PolicyCheck
    object PreferQueryAnnotation : PolicyCheck
    object PreferJpaOverNative : PolicyCheck
}

data class Diagnostic(
    val rule: String,
    val status: String,
    val severity: String,
    val code: String,
    val file: String,
    val line: Int,
    val column: Int,
    val evidence: String,
    val message: String,
    val suggestion: String?,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "rule" to rule, "status" to status, "severity" to severity, "code" to code,
        "file" to file, "line" to line, "column" to column, "evidence" to evidence,
        "message" to message, "suggestion" to suggestion,
    )
}

data class AnalysisReport(val files: Int, val applicableRules: List<String>, val diagnostics: List<Diagnostic>) {
    fun toMap(): Map<String, Any> = linkedMapOf(
        "schema-version" to 1, "files" to files, "applicable-rules" to applicableRules,
        "diagnostics" to diagnostics.map { it.toMap() },
    )
}
