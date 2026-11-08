package io.github.minjoonchoi.policy

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.io.Closeable
import java.io.File

data class EntityField(val type: String, val column: String?)
data class ClassModel(
    val fqn: String,
    val name: String,
    val entity: String?,
    val table: String?,
    val fields: Map<String, EntityField>,
    val supers: List<String>,
    val repositoryEntity: String?,
    val complex: Boolean,
)
typealias SourceIndex = Map<String, ClassModel>

class KotlinSource(val path: String, val module: String, val text: String, val file: KtFile) {
    val packageName: String = file.packageFqName.asString()
    private val imports: Map<String, String> = file.importDirectives.filter { !it.isAllUnder }.mapNotNull { directive ->
        directive.importedFqName?.asString()?.let { (directive.aliasName ?: it.substringAfterLast('.')) to it }
    }.toMap()

    class Parser : Closeable {
        private val disposable = Disposer.newDisposable("spring-policy-parser")
        private val environment = KotlinCoreEnvironment.createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
        private val factory = KtPsiFactory(environment.project, false)

        fun parse(input: SourceInput): KotlinSource {
            val source = KotlinSource(input.path, input.module, input.text, factory.createFile(File(input.path).name, input.text))
            val error = PsiTreeUtil.findChildOfType(source.file, PsiErrorElement::class.java)
            if (error != null) {
                val location = source.location(error)
                throw IllegalArgumentException("${source.path}:${location.line}:${location.column}: Kotlin parse error; analysis incomplete: ${error.errorDescription}")
            }
            return source
        }

        override fun close() = Disposer.dispose(disposable)
    }

    fun <T : PsiElement> nodes(type: Class<T>): List<T> = PsiTreeUtil.collectElementsOfType(file, type).sortedBy { it.textOffset }
    fun className(node: KtClass): String = fqn(packageName, requireNotNull(node.name))

    fun resolve(raw: String?, index: SourceIndex = emptyMap()): String? {
        if (raw.isNullOrEmpty()) return null
        val simple = raw.split(Regex("[<?(]"))[0].trim()
        if ('.' in simple) return simple
        if (file.declarations.any { it is KtClassOrObject && it.name == simple }) return fqn(packageName, simple)
        imports[simple]?.let { return it }
        if (simple in BASIC_TYPES) return "kotlin.$simple"
        if (simple in listOf("List", "Set", "Collection")) return "kotlin.collections.$simple"
        return fqn(packageName, simple).takeIf { it in index }
    }

    fun annotationName(annotation: KtAnnotationEntry): String? = resolve(annotation.typeReference?.text)
    fun args(annotation: KtAnnotationEntry): Map<String, KtExpression?> = annotation.valueArguments.mapIndexed { i, arg ->
        (arg.getArgumentName()?.asName?.asString() ?: i.toString()) to arg.getArgumentExpression()
    }.toMap()

    data class Annotation(val name: String?, val node: KtAnnotationEntry)
    fun annotations(node: KtModifierListOwner): List<Annotation> = node.annotationEntries.map { Annotation(annotationName(it), it) }
    fun annotationValue(node: KtModifierListOwner, suffix: String, key: String): String? {
        val annotation = annotations(node).find { it.name in listOf("jakarta.persistence.$suffix", "javax.persistence.$suffix") }
        return annotation?.let { constant(args(it.node)[key]) }
    }

    data class Binding(val size: Int, val offset: Int, val type: String?, val node: KtNamedDeclaration)
    fun binding(at: PsiElement, name: String): Binding? = nodes(KtNamedDeclaration::class.java).mapNotNull { node ->
        if ((node !is KtParameter && node !is KtProperty) || node.name != name) return@mapNotNull null
        val scope = if (node is KtParameter) {
            ancestor(node, KtNamedFunction::class.java, KtFunctionLiteral::class.java, KtClass::class.java, KtCatchClause::class.java, KtForExpression::class.java)
        } else {
            ancestor(node, KtBlockExpression::class.java, KtClassOrObject::class.java)
        } ?: file
        if (!scope.textRange.contains(at.textRange)) return@mapNotNull null
        if (scope is KtBlockExpression && node.textOffset > at.textOffset) return@mapNotNull null
        val type = when (node) {
            is KtParameter -> node.typeReference?.text
            is KtProperty -> node.typeReference?.text
            else -> null
        }
        Binding(scope.textLength, node.textOffset, type, node)
    }.minWithOrNull(compareBy<Binding> { it.size }.thenByDescending { it.offset })

    fun declaredReceiver(at: PsiElement, name: String, index: SourceIndex): String? = resolve(binding(at, name)?.type, index)

    fun constant(node: PsiElement?, depth: Int = 0): String? {
        if (node == null || depth > 8) return null
        if (node is KtStringTemplateExpression) {
            if (node.entries.any { it !is KtLiteralStringTemplateEntry && it !is KtEscapeStringTemplateEntry }) return null
            return node.entries.joinToString("") { if (it is KtEscapeStringTemplateEntry) it.unescapedValue else it.text }
        }
        if (node is KtDotQualifiedExpression && node.selectorExpression?.text == "AUTHORIZATION") {
            val receiver = node.receiverExpression
            if (receiver is KtNameReferenceExpression && binding(node, receiver.text) == null && resolve(receiver.text) == "org.springframework.http.HttpHeaders") return "Authorization"
        }
        if (node !is KtNameReferenceExpression) return null
        val property = binding(node, node.getReferencedName())?.node
        return if (property is KtProperty && property.parent is KtFile && property.hasModifier(KtTokens.CONST_KEYWORD)) constant(property.initializer, depth + 1) else null
    }

    data class Location(val line: Int, val column: Int)
    fun location(node: PsiElement): Location {
        val offset = node.textRange.startOffset
        val start = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(-1)) + 1
        return Location(text.substring(0, offset).count { it == '\n' } + 1, text.codePointCount(start, offset) + 1)
    }

    data class Call(val receiver: String?, val method: String?, val args: List<KtExpression?>, val node: PsiElement)

    companion object {
        val BASIC_TYPES = setOf("String", "Int", "Long", "Boolean", "Double", "Float", "Short", "Byte")

        fun ancestor(node: PsiElement, vararg types: Class<out PsiElement>): PsiElement? {
            var parent = node.parent
            while (parent != null) {
                if (types.any { it.isInstance(parent) }) return parent
                parent = parent.parent
            }
            return null
        }

        fun fqn(pkg: String, name: String): String = if (pkg.isNotEmpty()) "$pkg.$name" else name

        fun call(node: KtCallExpression): Call {
            val qualified = (node.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == node }
            val receiver = qualified?.receiverExpression as? KtNameReferenceExpression
            return Call(receiver?.getReferencedName(), node.calleeExpression?.text, node.valueArguments.map { it.getArgumentExpression() }, qualified ?: node)
        }

        fun index(sources: List<KotlinSource>): SourceIndex {
            val result = linkedMapOf<String, ClassModel>()
            for (source in sources) for (node in source.file.declarations.filterIsInstance<KtClass>()) {
                val name = source.className(node)
                require(name !in result) { "Duplicate class in source index: $name" }
                val annotations = source.annotations(node)
                val isEntity = annotations.any { it.name in listOf("jakarta.persistence.Entity", "javax.persistence.Entity") }
                var complex = false
                val fields = linkedMapOf<String, EntityField>()
                val declarations: List<KtCallableDeclaration> = node.primaryConstructorParameters.filter { it.hasValOrVar() } + node.declarations.filterIsInstance<KtProperty>()
                for (field in declarations) {
                    val fieldAnnotations = source.annotations(field)
                    if (fieldAnnotations.any { it.name?.endsWith(".Transient") == true }) continue
                    val type = field.typeReference?.text
                    if (type == null) { complex = true; continue }
                    if (fieldAnnotations.any { it.name !in listOf("jakarta.persistence.Id", "jakarta.persistence.Column", "jakarta.persistence.GeneratedValue", "javax.persistence.Id", "javax.persistence.Column", "javax.persistence.GeneratedValue") }) complex = true
                    if (type.removeSuffix("?") !in BASIC_TYPES) complex = true
                    if (field is KtProperty && (field.getter != null || field.delegateExpression != null)) complex = true
                    fields[requireNotNull(field.name)] = EntityField(type, source.annotationValue(field, "Column", "name"))
                }
                val supers = mutableListOf<String>()
                var repositoryEntity: String? = null
                for (entry in node.superTypeListEntries) {
                    val raw = entry.typeReference?.text ?: continue
                    val type = source.resolve(raw) ?: fqn(source.packageName, raw.substringBefore('<').trim())
                    supers.add(type)
                    if (type in listOf("org.springframework.data.jpa.repository.JpaRepository", "org.springframework.data.repository.CrudRepository", "org.springframework.data.repository.Repository")) {
                        Regex("<\\s*([^,>]+)").find(raw)?.groupValues?.get(1)?.let {
                            repositoryEntity = source.resolve(it) ?: fqn(source.packageName, it.trim())
                        }
                    }
                }
                if (isEntity && supers.isNotEmpty()) complex = true
                if (annotations.any { a -> a.name?.startsWith("org.hibernate.") == true || listOf(".Inheritance", ".SecondaryTable", ".Convert").any { a.name?.endsWith(it) == true } }) complex = true
                result[name] = ClassModel(name, requireNotNull(node.name), if (isEntity) source.annotationValue(node, "Entity", "name")?.takeIf { it.isNotEmpty() } ?: node.name else null,
                    source.annotationValue(node, "Table", "name"), fields, supers, repositoryEntity, complex)
            }
            return result
        }

        fun hasSuper(fqn: String, targets: List<String>, index: SourceIndex, depth: Int = 0): Boolean {
            if (fqn in targets) return true
            if (depth > 16) return false
            return index[fqn]?.supers?.any { hasSuper(it, targets, index, depth + 1) } ?: false
        }
    }
}
