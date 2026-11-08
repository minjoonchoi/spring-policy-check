package io.github.minjoonchoi.policy

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class PolicyConfig(val rules: List<PolicyRule>, val excludePathPatterns: List<Regex>) {
    companion object {
        fun load(text: String): PolicyConfig {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                nestingDepthLimit = 40
                codePointLimit = 1_000_000
            }
            val config = keys(Yaml(SafeConstructor(options)).load<Any?>(text), listOf("version", "rules"), listOf("exclude-path-patterns"), "policy")
            require(config["version"] is Int && config["version"] == 1) { "Unsupported policy version" }
            val rawRules = config["rules"]
            require(rawRules is List<*> && rawRules.isNotEmpty()) { "At least one rule is required" }
            val ids = mutableSetOf<String>()
            val rules = rawRules.map { value ->
                val rule = keys(value, listOf("id", "severity", "scope", "check", "message", "suggestion"), name = "rule")
                val id = nonemptyString(rule["id"], "id")
                val message = nonemptyString(rule["message"], "message")
                val suggestion = nonemptyString(rule["suggestion"], "suggestion")
                require(ids.add(id)) { "Duplicate rule ID: $id" }
                val severity = rule["severity"]
                require(severity is String && severity in listOf("error", "warning", "info")) { "Invalid severity" }
                val scope = keys(rule["scope"], listOf("modules", "package-pattern"), name = "scope")
                val modules = strings(scope["modules"], "scope.modules", true)
                val packagePattern = pattern(scope["package-pattern"])
                PolicyRule(id, severity, RuleScope(modules, packagePattern), check(rule["check"]), message, suggestion)
            }
            val excludes = strings(optionalList(config, "exclude-path-patterns"), "exclude-path-patterns", false)
            return PolicyConfig(rules, excludes.map { pattern(it) })
        }

        private fun check(value: Any?): PolicyCheck {
            require(value is Map<*, *>) { "check must be a mapping" }
            return when (value["type"]) {
                "name" -> {
                    val check = keys(value, listOf("type", "declaration", "name-pattern"), name = "name check")
                    val declaration = check["declaration"]
                    require(declaration is String && declaration in listOf("class", "data-class")) { "Invalid declaration kind" }
                    PolicyCheck.Name(declaration, pattern(check["name-pattern"]))
                }
                "auth-boundary" -> {
                    val check = keys(value, listOf("type", "allowed-package-pattern", "allowed-supertypes", "token-headers"), listOf("allowed-types", "restricted-calls"), "auth check")
                    PolicyCheck.AuthBoundary(
                        pattern(check["allowed-package-pattern"]),
                        strings(check["allowed-supertypes"], "allowed-supertypes", false),
                        strings(optionalList(check, "allowed-types"), "allowed-types", false),
                        strings(check["token-headers"], "token-headers", true),
                        strings(optionalList(check, "restricted-calls"), "restricted-calls", false),
                    )
                }
                "prefer-derived-query" -> {
                    val check = keys(value, listOf("type", "max-predicates", "max-method-length"), name = "derived query check")
                    PolicyCheck.PreferDerivedQuery(positiveInt(check["max-predicates"]), positiveInt(check["max-method-length"]))
                }
                "prefer-query-annotation" -> {
                    keys(value, listOf("type"), name = "query check")
                    PolicyCheck.PreferQueryAnnotation
                }
                "prefer-jpa-over-native" -> {
                    keys(value, listOf("type"), name = "query check")
                    PolicyCheck.PreferJpaOverNative
                }
                else -> throw IllegalArgumentException("Unknown check type: ${value["type"]}")
            }
        }

        private fun keys(value: Any?, required: List<String>, optional: List<String> = emptyList(), name: String): Map<*, *> {
            require(value is Map<*, *>) { "$name must be a mapping" }
            require(value.keys.containsAll(required)) { "Missing fields in $name: ${required - value.keys}" }
            require(value.keys.all { it in required || it in optional }) { "Unknown fields in $name: ${value.keys - required.toSet() - optional.toSet()}" }
            return value
        }

        private fun optionalList(value: Map<*, *>, key: String): Any? = if (value.containsKey(key)) value[key] else emptyList<String>()

        private fun nonemptyString(value: Any?, name: String): String {
            require(value is String && value.isNotEmpty()) { "$name must be a nonempty string" }
            return value
        }

        private fun strings(value: Any?, name: String, nonempty: Boolean): List<String> {
            require(value is List<*> && (!nonempty || value.isNotEmpty()) && value.all { it is String && it.isNotEmpty() }) { "$name must be a list of strings" }
            return value.map { it as String }
        }

        private fun pattern(value: Any?): Regex {
            require(value is String) { "Pattern must be a string" }
            return Regex(value)
        }

        private fun positiveInt(value: Any?): Int {
            require(value is Int && value > 0) { "Query limits must be positive integers" }
            return value
        }
    }
}
