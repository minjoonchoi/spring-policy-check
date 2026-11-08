package io.github.minjoonchoi.policy

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.util.regex.Pattern
import static io.github.minjoonchoi.policy.QueryShape.reject

class PolicyEngine {
    static Map analyze(Map config, List<Map> inputs) {
        if (!inputs) throw new IllegalArgumentException('No Kotlin source files; analysis was not performed')
        def excludes = config['exclude-path-patterns'].collect { Pattern.compile(it) }
        inputs = inputs.findAll { input -> !excludes.any { it.matcher(input.path).find() } }.sort { it.path }
        if (!inputs) throw new IllegalArgumentException('All Kotlin source files were excluded')
        def parser = new KotlinSource.Parser()
        try {
            def sources = inputs.collect { parser.parse(it) }
            def index = KotlinSource.index(sources)
            def diagnostics = []
            def applicable = [] as Set
            config.rules.each { rule ->
                def packagePattern = Pattern.compile(rule.scope['package-pattern'])
                sources.findAll { it.module in rule.scope.modules && packagePattern.matcher(it.packageName).find() }.each { source ->
                    applicable.add(rule.id)
                    diagnostics.addAll(inspect(source, rule, index))
                }
            }
            diagnostics.sort { a, b -> a.file <=> b.file ?: a.line <=> b.line ?: a.column <=> b.column ?: a.rule <=> b.rule ?: a.code <=> b.code }
            ['schema-version': 1, files: sources.size(), 'applicable-rules': applicable.toList(), diagnostics: diagnostics]
        } finally { parser.close() }
    }

    private static Map diagnostic(KotlinSource s, Map r, PsiElement node, String status, String code, String suggestion = null) {
        def location = s.location(node)
        [rule: r.id, status: status, severity: status == 'skipped' ? 'info' : r.severity,
         code: code, file: s.path, line: location.line, column: location.column,
         evidence: node.text.take(180), message: r.message, suggestion: suggestion]
    }
    private static List inspect(KotlinSource s, Map r, Map index) {
        switch (r.check.type) {
            case 'name':
                def pattern = Pattern.compile(r.check['name-pattern'])
                return s.file.declarations.findAll { it instanceof KtClass }.findAll { KtClass n ->
                    !n.isInterface() && !n.isEnum() && !n.isAnnotation() &&
                        n.hasModifier(KtTokens.DATA_KEYWORD) == (r.check.declaration == 'data-class') &&
                        !pattern.matcher(n.name).find()
                }.collect { diagnostic(s, r, it.nameIdentifier ?: it, 'violation', 'NAME_MISMATCH', r.suggestion) }
            case 'auth-boundary': return auth(s, r, index)
            case 'prefer-derived-query': return queryAnnotations(s, r, index, false)
            case 'prefer-query-annotation': return entityManager(s, r, index, false)
            case 'prefer-jpa-over-native': return queryAnnotations(s, r, index, true) + entityManager(s, r, index, true)
            default: throw new IllegalArgumentException("Unknown check type: ${r.check.type}")
        }
    }
    private static List auth(KotlinSource s, Map r, Map index) {
        def c = r.check
        def packagePattern = Pattern.compile(c['allowed-package-pattern'])
        def allowed = { PsiElement node ->
            if (!packagePattern.matcher(s.packageName).find()) return false
            def owner = KotlinSource.ancestor(node, KtClass)
            if (!owner) return false
            def fqn = s.className(owner)
            fqn in c['allowed-types'] || KotlinSource.hasSuper(fqn, c['allowed-supertypes'], index)
        }
        def isToken = { String header -> c['token-headers'].any { it.equalsIgnoreCase(header) } }
        def out = []
        s.nodes(KtCallExpression).each { n ->
            def call = KotlinSource.call(n)
            boolean headerMethod = call.method in ['getHeader', 'getHeaders']
            if (!headerMethod && !c['restricted-calls'].any { it.tokenize('#').last() == call.method }) return
            def type = call.receiver ? s.declaredReceiver(n, call.receiver, index) : null
            if (!type) { out.add(diagnostic(s, r, call.node, 'skipped', 'UNRESOLVED_RECEIVER')); return }
            boolean headerApi = headerMethod && type in ['jakarta.servlet.http.HttpServletRequest', 'javax.servlet.http.HttpServletRequest']
            if (!headerApi && !("${type}#${call.method}".toString() in c['restricted-calls'])) return
            if (headerApi) {
                def header = call.args ? s.constant(call.args.first()) : null
                if (header == null) { out.add(diagnostic(s, r, call.node, 'skipped', 'DYNAMIC_HEADER')); return }
                if (!isToken(header)) return
            }
            if (!allowed(n)) out.add(diagnostic(s, r, call.node, 'violation', 'AUTH_OUTSIDE_BOUNDARY', r.suggestion))
        }
        s.nodes(KtAnnotationEntry).each { a ->
            if (s.annotationName(a) != 'org.springframework.web.bind.annotation.RequestHeader') return
            def args = s.args(a)
            def header = s.constant(args.value ?: args.name ?: args['0'])
            if (header != null && isToken(header) && !allowed(a)) out.add(diagnostic(s, r, a, 'violation', 'AUTH_HEADER_PARAMETER', r.suggestion))
        }
        out
    }
    private static Map returnEntity(KotlinSource s, KtNamedFunction f, Map index) {
        def raw = f.typeReference?.text?.replaceAll(/\s/, '')
        if (!raw) return null
        def match = raw =~ /^([^<]+)<([^<>?]+)>$/
        if (!match.matches() || s.resolve(match.group(1), index) != 'kotlin.collections.List') return null
        def entity = index[s.resolve(match.group(2), index)]
        entity?.entity ? entity : null
    }
    private static List parameters(KotlinSource s, KtNamedFunction f) {
        f.valueParameters.collect { p ->
            def a = s.annotations(p).find { it.name == 'org.springframework.data.repository.query.Param' }
            def args = a ? s.args(a.node) : [:]
            [name: p.name, binding: s.constant(args['0'] ?: args.value) ?: p.name, type: p.typeReference?.text ?: '']
        }
    }
    private static String derived(KotlinSource s, KtNamedFunction f, Map shape, Map entity, Map check) {
        if (entity.entity != shape.entity) reject('ENTITY_MISMATCH')
        if (entity.complex) reject('COMPLEX_MAPPING')
        if (shape.predicates.size() > check['max-predicates']) reject('COMPLEXITY_LIMIT')
        if (shape.order && !entity.fields.containsKey(shape.order.property)) reject('UNKNOWN_PROPERTY')
        def params = parameters(s, f)
        def used = [] as Set
        def ordered = []
        shape.predicates.each { p ->
            def field = entity.fields[p.property]
            if (!field) reject('UNKNOWN_PROPERTY')
            if (!(field.type.replaceAll(/\?$/, '') in ['String', 'Long', 'Int', 'Boolean', 'Double', 'Float', 'Short', 'Byte'])) reject('UNSUPPORTED_PROPERTY_TYPE')
            def param
            if (p.parameter.startsWith(':')) {
                param = params.find { it.binding == p.parameter.substring(1) }
            } else {
                def number = p.parameter.substring(1)
                if (number ==~ /[0-9]+/) {
                    def ordinal = new BigInteger(number)
                    if (ordinal > 0 && ordinal <= params.size()) param = params[ordinal.intValue() - 1]
                }
            }
            if (!param) reject('PARAMETER_BINDING')
            if (!used.add(param.name)) reject('REUSED_PARAMETER')
            if (param.type.endsWith('?')) reject('NULL_SEMANTICS')
            if (param.type != field.type.replaceAll(/\?$/, '')) reject('PARAMETER_TYPE')
            ordered.add("${param.name}: ${param.type}")
        }
        if (used.size() != params.size()) reject('EXTRA_PARAMETERS')
        def method = QueryShape.method(shape)
        if (method.length() > check['max-method-length']) reject('METHOD_LENGTH_LIMIT')
        "fun ${method}(${ordered.join(', ')}): List<${entity.name}>"
    }
    private static String nativeCandidate(Map shape, Map entity) {
        if (entity.complex) reject('COMPLEX_MAPPING')
        if (entity.table != shape.entity) reject('TABLE_MAPPING')
        if (!entity.entity) reject('ENTITY_MAPPING')
        def columns = [:]
        entity.fields.each { name, f -> if (f.column) columns[f.column] = name }
        def query = QueryShape.jpql(shape, entity.entity, columns)
        "@Query(\"${query}\") — 런타임 필터·매핑·실행계획 검토 필요; 자동 수정하지 않음"
    }
    private static List queryAnnotations(KotlinSource s, Map r, Map index, boolean nativeQuery) {
        def out = []
        s.nodes(KtAnnotationEntry).each { a ->
            def name = s.annotationName(a)
            if (!name) {
                if (a.shortName?.asString() == (nativeQuery ? 'NativeQuery' : 'Query')) out.add(diagnostic(s, r, a, 'skipped', 'UNRESOLVED_ANNOTATION'))
                return
            }
            boolean isQuery = name == 'org.springframework.data.jpa.repository.Query'
            boolean isNative = name == 'org.springframework.data.jpa.repository.NativeQuery'
            if (!isQuery && !isNative) return
            def args = s.args(a)
            boolean flag = isNative || args.nativeQuery?.text == 'true'
            if (flag != nativeQuery) return
            try {
                if (args.nativeQuery && !(args.nativeQuery.text in ['true', 'false'])) reject('UNKNOWN_NATIVE_FLAG')
                def f = KotlinSource.ancestor(a, KtNamedFunction)
                if (!f) return
                def ownerClass = KotlinSource.ancestor(f, KtClass)
                def owner = ownerClass ? index[s.className(ownerClass)] : null
                if (!owner?.repositoryEntity) reject('REPOSITORY_UNRESOLVED')
                def query = s.constant(args.value ?: args['0'])
                if (query == null) reject('DYNAMIC_QUERY')
                def shape = QueryShape.parse(query, nativeQuery)
                if (args.keySet().any { !(it in ['0', 'value', 'nativeQuery']) } || s.annotations(f).any {
                    it.name in ['org.springframework.data.jpa.repository.Lock', 'org.springframework.data.jpa.repository.QueryHints', 'org.springframework.data.jpa.repository.EntityGraph', 'org.springframework.data.jpa.repository.Modifying']
                }) reject('QUERY_OPTIONS')
                def entity = returnEntity(s, f, index)
                if (!entity) reject('RETURN_TYPE')
                if (owner.repositoryEntity != entity.fqn) reject('REPOSITORY_ENTITY_MISMATCH')
                def candidate = nativeQuery ? nativeCandidate(shape, entity) : derived(s, f, shape, entity, r.check)
                out.add(diagnostic(s, r, a, 'suggestion', nativeQuery ? 'PREFER_JPA_REVIEW' : 'PREFER_DERIVED', "${r.suggestion} ${candidate}"))
            } catch (QueryShape.Unsupported e) { out.add(diagnostic(s, r, a, 'skipped', e.code)) }
        }
        out
    }
    private static List entityManager(KotlinSource s, Map r, Map index, boolean nativeQuery) {
        def out = []
        def target = nativeQuery ? 'createNativeQuery' : 'createQuery'
        s.nodes(KtCallExpression).each { n ->
            def call = KotlinSource.call(n)
            if (call.method != target) return
            try {
                def type = call.receiver ? s.declaredReceiver(n, call.receiver, index) : null
                if (!type) reject('UNRESOLVED_RECEIVER')
                if (!(type in ['jakarta.persistence.EntityManager', 'javax.persistence.EntityManager'])) return
                if (!call.args) return
                def arg = call.args.first()
                if (!nativeQuery && arg instanceof KtNameReferenceExpression && s.declaredReceiver(n, arg.referencedName, index)?.endsWith('.CriteriaQuery')) return
                def query = s.constant(arg)
                if (query == null) reject('DYNAMIC_QUERY')
                def shape = QueryShape.parse(query, nativeQuery)
                def top = call.node
                def chain = []
                while (top.parent instanceof KtQualifiedExpression && top.parent.receiverExpression == top) {
                    top = top.parent
                    chain.add(top.selectorExpression)
                }
                if (!chain || !(chain.last() instanceof KtNameReferenceExpression) || chain.last().text != 'resultList') reject('EXECUTION_CHAIN')
                if (chain.dropRight(1).any { !(it instanceof KtCallExpression) || it.calleeExpression?.text != 'setParameter' }) reject('EXECUTION_CHAIN')
                def entityArg = call.args.size() == 2 ? call.args[1].text : ''
                if (!entityArg.endsWith('::class.java')) reject('RESULT_MAPPING_REQUIRED')
                def entity = index[s.resolve(entityArg.substring(0, entityArg.length() - '::class.java'.length()), index)]
                if (!entity) reject('RESULT_MAPPING_REQUIRED')
                def bound = [] as Set
                chain.dropRight(1).each { binding ->
                    def values = binding.valueArguments.collect { it.argumentExpression }
                    def key = values ? s.constant(values[0]) : null
                    if (values.size() != 2 || key == null || !bound.add(':' + key)) reject('PARAMETER_BINDING')
                }
                if (bound != (shape.predicates.collect { it.parameter } as Set)) reject('PARAMETER_BINDING')
                def candidate
                if (nativeQuery) candidate = nativeCandidate(shape, entity)
                else {
                    if (entity.entity != shape.entity) reject('ENTITY_MISMATCH')
                    if (shape.predicates.any { !entity.fields.containsKey(it.property) } || (shape.order && !entity.fields.containsKey(shape.order.property))) reject('UNKNOWN_PROPERTY')
                    candidate = "@Query(\"${query}\") — 파라미터·반환 타입·트랜잭션 경계를 보존하세요"
                }
                out.add(diagnostic(s, r, call.node, 'suggestion', nativeQuery ? 'PREFER_JPA_REVIEW' : 'PREFER_QUERY_ANNOTATION', "${r.suggestion} ${candidate}"))
            } catch (QueryShape.Unsupported e) { out.add(diagnostic(s, r, call.node, 'skipped', e.code)) }
        }
        out
    }
}
