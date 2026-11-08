package io.github.minjoonchoi.policy

import io.github.minjoonchoi.policy.QueryShape.Companion.reject
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

object PolicyEngine {
    fun analyze(config: PolicyConfig, inputs: List<SourceInput>): AnalysisReport {
        require(inputs.isNotEmpty()) { "No Kotlin source files; analysis was not performed" }
        val included = inputs.filter { input -> config.excludePathPatterns.none { it.containsMatchIn(input.path) } }.sortedBy { it.path }
        require(included.isNotEmpty()) { "All Kotlin source files were excluded" }
        return KotlinSource.Parser().use { parser ->
            val sources = included.map { parser.parse(it) }
            val index = KotlinSource.index(sources)
            val diagnostics = mutableListOf<Diagnostic>()
            val applicable = linkedSetOf<String>()
            for (rule in config.rules) {
                sources.filter { it.module in rule.scope.modules && rule.scope.packagePattern.containsMatchIn(it.packageName) }.forEach { source ->
                    applicable.add(rule.id)
                    diagnostics.addAll(inspect(source, rule, index))
                }
            }
            AnalysisReport(sources.size, applicable.toList(), diagnostics.sortedWith(
                compareBy<Diagnostic> { it.file }.thenBy { it.line }.thenBy { it.column }.thenBy { it.rule }.thenBy { it.code },
            ))
        }
    }

    private fun diagnostic(source: KotlinSource, rule: PolicyRule, node: PsiElement, status: String, code: String, suggestion: String? = null): Diagnostic {
        val location = source.location(node)
        return Diagnostic(rule.id, status, if (status == "skipped") "info" else rule.severity, code,
            source.path, location.line, location.column, node.text.take(180), rule.message, suggestion)
    }

    private fun inspect(source: KotlinSource, rule: PolicyRule, index: SourceIndex): List<Diagnostic> = when (val check = rule.check) {
        is PolicyCheck.Name -> source.file.declarations.filterIsInstance<KtClass>().filter { node ->
            !node.isInterface() && !node.isEnum() && !node.isAnnotation() &&
                node.hasModifier(KtTokens.DATA_KEYWORD) == (check.declaration == "data-class") &&
                !check.namePattern.containsMatchIn(requireNotNull(node.name))
        }.map { diagnostic(source, rule, it.nameIdentifier ?: it, "violation", "NAME_MISMATCH", rule.suggestion) }
        is PolicyCheck.AuthBoundary -> auth(source, rule, check, index)
        is PolicyCheck.PreferDerivedQuery -> queryAnnotations(source, rule, index, false)
        PolicyCheck.PreferQueryAnnotation -> entityManager(source, rule, index, false)
        PolicyCheck.PreferJpaOverNative -> queryAnnotations(source, rule, index, true) + entityManager(source, rule, index, true)
    }

    private fun auth(source: KotlinSource, rule: PolicyRule, check: PolicyCheck.AuthBoundary, index: SourceIndex): List<Diagnostic> {
        fun allowed(node: PsiElement): Boolean {
            if (!check.allowedPackagePattern.containsMatchIn(source.packageName)) return false
            val owner = KotlinSource.ancestor(node, KtClass::class.java) as? KtClass ?: return false
            val fqn = source.className(owner)
            return fqn in check.allowedTypes || KotlinSource.hasSuper(fqn, check.allowedSupertypes, index)
        }
        fun isToken(header: String): Boolean = check.tokenHeaders.any { it.equals(header, ignoreCase = true) }
        val out = mutableListOf<Diagnostic>()
        for (node in source.nodes(KtCallExpression::class.java)) {
            val call = KotlinSource.call(node)
            val headerMethod = call.method in listOf("getHeader", "getHeaders")
            if (!headerMethod && check.restrictedCalls.none { it.substringAfterLast('#') == call.method }) continue
            val type = call.receiver?.let { source.declaredReceiver(node, it, index) }
            if (type == null) { out.add(diagnostic(source, rule, call.node, "skipped", "UNRESOLVED_RECEIVER")); continue }
            val headerApi = headerMethod && type in listOf("jakarta.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletRequest")
            if (!headerApi && "$type#${call.method}" !in check.restrictedCalls) continue
            if (headerApi) {
                val header = source.constant(call.args.firstOrNull())
                if (header == null) { out.add(diagnostic(source, rule, call.node, "skipped", "DYNAMIC_HEADER")); continue }
                if (!isToken(header)) continue
            }
            if (!allowed(node)) out.add(diagnostic(source, rule, call.node, "violation", "AUTH_OUTSIDE_BOUNDARY", rule.suggestion))
        }
        for (annotation in source.nodes(KtAnnotationEntry::class.java)) {
            if (source.annotationName(annotation) != "org.springframework.web.bind.annotation.RequestHeader") continue
            val args = source.args(annotation)
            val header = source.constant(args["value"] ?: args["name"] ?: args["0"])
            if (header != null && isToken(header) && !allowed(annotation)) out.add(diagnostic(source, rule, annotation, "violation", "AUTH_HEADER_PARAMETER", rule.suggestion))
        }
        return out
    }

    private fun returnEntity(source: KotlinSource, function: KtNamedFunction, index: SourceIndex): ClassModel? {
        val raw = function.typeReference?.text?.replace(Regex("\\s"), "") ?: return null
        val match = Regex("^([^<]+)<([^<>?]+)>$").matchEntire(raw) ?: return null
        if (source.resolve(match.groupValues[1], index) != "kotlin.collections.List") return null
        return index[source.resolve(match.groupValues[2], index)]?.takeIf { it.entity != null }
    }

    private data class Parameter(val name: String, val binding: String, val type: String)
    private fun parameters(source: KotlinSource, function: KtNamedFunction): List<Parameter> = function.valueParameters.map { parameter ->
        val annotation = source.annotations(parameter).find { it.name == "org.springframework.data.repository.query.Param" }
        val args = annotation?.let { source.args(it.node) }.orEmpty()
        val name = requireNotNull(parameter.name)
        Parameter(name, source.constant(args["0"] ?: args["value"])?.takeIf { it.isNotEmpty() } ?: name, parameter.typeReference?.text ?: "")
    }

    private fun derived(source: KotlinSource, function: KtNamedFunction, shape: QueryShape, entity: ClassModel, check: PolicyCheck.PreferDerivedQuery): String {
        if (entity.entity != shape.entity) reject("ENTITY_MISMATCH")
        if (entity.complex) reject("COMPLEX_MAPPING")
        if (shape.predicates.size > check.maxPredicates) reject("COMPLEXITY_LIMIT")
        if (shape.order != null && shape.order.property !in entity.fields) reject("UNKNOWN_PROPERTY")
        val params = parameters(source, function)
        val used = mutableSetOf<String>()
        val ordered = shape.predicates.map { predicate ->
            val field = entity.fields[predicate.property] ?: reject("UNKNOWN_PROPERTY")
            if (field.type.removeSuffix("?") !in KotlinSource.BASIC_TYPES) reject("UNSUPPORTED_PROPERTY_TYPE")
            val param = if (predicate.parameter.startsWith(':')) {
                params.find { it.binding == predicate.parameter.substring(1) }
            } else {
                // Do not overflow or accept signs when reading an arbitrary positional parameter.
                val ordinal = predicate.parameter.substring(1).takeIf { it.matches(Regex("[0-9]+")) }?.toBigIntegerOrNull()
                if (ordinal != null && ordinal.signum() > 0 && ordinal <= params.size.toBigInteger()) params[ordinal.toInt() - 1] else null
            } ?: reject("PARAMETER_BINDING")
            if (!used.add(param.name)) reject("REUSED_PARAMETER")
            if (param.type.endsWith('?')) reject("NULL_SEMANTICS")
            if (param.type != field.type.removeSuffix("?")) reject("PARAMETER_TYPE")
            "${param.name}: ${param.type}"
        }
        if (used.size != params.size) reject("EXTRA_PARAMETERS")
        val method = QueryShape.method(shape)
        if (method.length > check.maxMethodLength) reject("METHOD_LENGTH_LIMIT")
        return "fun $method(${ordered.joinToString(", ")}): List<${entity.name}>"
    }

    private fun nativeCandidate(shape: QueryShape, entity: ClassModel): String {
        if (entity.complex) reject("COMPLEX_MAPPING")
        if (entity.table != shape.entity) reject("TABLE_MAPPING")
        val entityName = entity.entity ?: reject("ENTITY_MAPPING")
        val columns = entity.fields.mapNotNull { (name, field) -> field.column?.takeIf { it.isNotEmpty() }?.let { it to name } }.toMap()
        val query = QueryShape.jpql(shape, entityName, columns)
        return "@Query(\"$query\") — 런타임 필터·매핑·실행계획 검토 필요; 자동 수정하지 않음"
    }

    private fun queryAnnotations(source: KotlinSource, rule: PolicyRule, index: SourceIndex, nativeQuery: Boolean): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        for (annotation in source.nodes(KtAnnotationEntry::class.java)) {
            val name = source.annotationName(annotation)
            if (name == null) {
                if (annotation.shortName?.asString() == (if (nativeQuery) "NativeQuery" else "Query")) out.add(diagnostic(source, rule, annotation, "skipped", "UNRESOLVED_ANNOTATION"))
                continue
            }
            val isQuery = name == "org.springframework.data.jpa.repository.Query"
            val isNative = name == "org.springframework.data.jpa.repository.NativeQuery"
            if (!isQuery && !isNative) continue
            val args = source.args(annotation)
            val flag = isNative || args["nativeQuery"]?.text == "true"
            if (flag != nativeQuery) continue
            try {
                if (args["nativeQuery"] != null && args["nativeQuery"]?.text !in listOf("true", "false")) reject("UNKNOWN_NATIVE_FLAG")
                val function = KotlinSource.ancestor(annotation, KtNamedFunction::class.java) as? KtNamedFunction ?: continue
                val ownerClass = KotlinSource.ancestor(function, KtClass::class.java) as? KtClass
                val owner = ownerClass?.let { index[source.className(it)] }
                val repositoryEntity = owner?.repositoryEntity ?: reject("REPOSITORY_UNRESOLVED")
                val query = source.constant(args["value"] ?: args["0"]) ?: reject("DYNAMIC_QUERY")
                val shape = QueryShape.parse(query, nativeQuery)
                if (args.keys.any { it !in listOf("0", "value", "nativeQuery") } || source.annotations(function).any {
                    it.name in listOf("org.springframework.data.jpa.repository.Lock", "org.springframework.data.jpa.repository.QueryHints", "org.springframework.data.jpa.repository.EntityGraph", "org.springframework.data.jpa.repository.Modifying")
                }) reject("QUERY_OPTIONS")
                val entity = returnEntity(source, function, index) ?: reject("RETURN_TYPE")
                if (repositoryEntity != entity.fqn) reject("REPOSITORY_ENTITY_MISMATCH")
                val candidate = if (nativeQuery) nativeCandidate(shape, entity) else derived(source, function, shape, entity, rule.check as PolicyCheck.PreferDerivedQuery)
                out.add(diagnostic(source, rule, annotation, "suggestion", if (nativeQuery) "PREFER_JPA_REVIEW" else "PREFER_DERIVED", "${rule.suggestion} $candidate"))
            } catch (e: QueryShape.Unsupported) {
                out.add(diagnostic(source, rule, annotation, "skipped", e.code))
            }
        }
        return out
    }

    private fun entityManager(source: KotlinSource, rule: PolicyRule, index: SourceIndex, nativeQuery: Boolean): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val target = if (nativeQuery) "createNativeQuery" else "createQuery"
        for (node in source.nodes(KtCallExpression::class.java)) {
            val call = KotlinSource.call(node)
            if (call.method != target) continue
            try {
                val type = call.receiver?.let { source.declaredReceiver(node, it, index) } ?: reject("UNRESOLVED_RECEIVER")
                if (type !in listOf("jakarta.persistence.EntityManager", "javax.persistence.EntityManager")) continue
                if (call.args.isEmpty()) continue
                val arg = call.args.first()
                if (!nativeQuery && arg is KtNameReferenceExpression && source.declaredReceiver(node, arg.getReferencedName(), index)?.endsWith(".CriteriaQuery") == true) continue
                val query = source.constant(arg) ?: reject("DYNAMIC_QUERY")
                val shape = QueryShape.parse(query, nativeQuery)
                var top = call.node
                val chain = mutableListOf<KtExpression?>()
                while (true) {
                    val parent = top.parent as? KtQualifiedExpression ?: break
                    if (parent.receiverExpression != top) break
                    top = parent
                    chain.add(parent.selectorExpression)
                }
                if (chain.lastOrNull() !is KtNameReferenceExpression || chain.last()?.text != "resultList") reject("EXECUTION_CHAIN")
                if (chain.dropLast(1).any { it !is KtCallExpression || it.calleeExpression?.text != "setParameter" }) reject("EXECUTION_CHAIN")
                val entityArg = if (call.args.size == 2) call.args[1]?.text.orEmpty() else ""
                if (!entityArg.endsWith("::class.java")) reject("RESULT_MAPPING_REQUIRED")
                val entity = index[source.resolve(entityArg.removeSuffix("::class.java"), index)] ?: reject("RESULT_MAPPING_REQUIRED")
                val bound = mutableSetOf<String>()
                for (binding in chain.dropLast(1)) {
                    val values = (binding as KtCallExpression).valueArguments.map { it.getArgumentExpression() }
                    val key = source.constant(values.firstOrNull())
                    if (values.size != 2 || key == null || !bound.add(":$key")) reject("PARAMETER_BINDING")
                }
                if (bound != shape.predicates.map { it.parameter }.toSet()) reject("PARAMETER_BINDING")
                val candidate = if (nativeQuery) nativeCandidate(shape, entity) else {
                    if (entity.entity != shape.entity) reject("ENTITY_MISMATCH")
                    if (shape.predicates.any { it.property !in entity.fields } || (shape.order != null && shape.order.property !in entity.fields)) reject("UNKNOWN_PROPERTY")
                    "@Query(\"$query\") — 파라미터·반환 타입·트랜잭션 경계를 보존하세요"
                }
                out.add(diagnostic(source, rule, call.node, "suggestion", if (nativeQuery) "PREFER_JPA_REVIEW" else "PREFER_QUERY_ANNOTATION", "${rule.suggestion} $candidate"))
            } catch (e: QueryShape.Unsupported) {
                out.add(diagnostic(source, rule, call.node, "skipped", e.code))
            }
        }
        return out
    }
}
