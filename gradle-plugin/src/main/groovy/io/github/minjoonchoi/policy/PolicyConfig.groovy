package io.github.minjoonchoi.policy

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.regex.Pattern

class PolicyConfig {
    static Map load(String text) {
        def options = new LoaderOptions()
        options.allowDuplicateKeys = false
        options.maxAliasesForCollections = 0
        options.nestingDepthLimit = 40
        options.codePointLimit = 1_000_000
        def config = new Yaml(new SafeConstructor(options)).load(text)
        keys(config, ['version', 'rules'], ['exclude-path-patterns'], 'policy')
        require(config.version instanceof Integer && config.version == 1, 'Unsupported policy version')
        require(config.rules instanceof List && !config.rules.empty, 'At least one rule is required')
        def ids = [] as Set
        config.rules.each { r ->
            keys(r, ['id', 'severity', 'scope', 'check', 'message', 'suggestion'], [], 'rule')
            ['id', 'message', 'suggestion'].each { require(r[it] instanceof String && !r[it].empty, "${it} must be a nonempty string") }
            require(ids.add(r.id), "Duplicate rule ID: ${r.id}")
            require(r.severity in ['error', 'warning', 'info'], 'Invalid severity')
            keys(r.scope, ['modules', 'package-pattern'], [], 'scope')
            strings(r.scope.modules, 'scope.modules', true)
            pattern(r.scope['package-pattern'])
            require(r.check instanceof Map, 'check must be a mapping')
            def c = r.check
            switch (c.type) {
                case 'name':
                    keys(c, ['type', 'declaration', 'name-pattern'], [], 'name check')
                    require(c.declaration in ['class', 'data-class'], 'Invalid declaration kind')
                    pattern(c['name-pattern'])
                    break
                case 'auth-boundary':
                    keys(c, ['type', 'allowed-package-pattern', 'allowed-supertypes', 'token-headers'], ['allowed-types', 'restricted-calls'], 'auth check')
                    pattern(c['allowed-package-pattern'])
                    strings(c['allowed-supertypes'], 'allowed-supertypes', false)
                    strings(c['token-headers'], 'token-headers', true)
                    c['allowed-types'] = c.get('allowed-types', [])
                    c['restricted-calls'] = c.get('restricted-calls', [])
                    strings(c['allowed-types'], 'allowed-types', false)
                    strings(c['restricted-calls'], 'restricted-calls', false)
                    break
                case 'prefer-derived-query':
                    keys(c, ['type', 'max-predicates', 'max-method-length'], [], 'derived query check')
                    ['max-predicates', 'max-method-length'].each { require(c[it] instanceof Integer && c[it] > 0, 'Query limits must be positive integers') }
                    break
                case 'prefer-query-annotation':
                case 'prefer-jpa-over-native':
                    keys(c, ['type'], [], 'query check')
                    break
                default: throw new IllegalArgumentException("Unknown check type: ${c.type}")
            }
        }
        config['exclude-path-patterns'] = config.get('exclude-path-patterns', [])
        strings(config['exclude-path-patterns'], 'exclude-path-patterns', false)
        config['exclude-path-patterns'].each { pattern(it) }
        config
    }

    private static void keys(Object value, List required, List optional, String name) {
        require(value instanceof Map, "${name} must be a mapping")
        require(value.keySet().containsAll(required), "Missing fields in ${name}: ${required - value.keySet()}")
        require(value.keySet().every { it in required || it in optional }, "Unknown fields in ${name}: ${value.keySet() - required - optional}")
    }
    private static void strings(Object value, String name, boolean nonempty) {
        require(value instanceof List && (!nonempty || !value.empty) && value.every { it instanceof String && !it.empty }, "${name} must be a list of strings")
    }
    private static void pattern(Object value) {
        require(value instanceof String, 'Pattern must be a string')
        Pattern.compile(value)
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message)
    }
}
