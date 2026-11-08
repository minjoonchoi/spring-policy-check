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

class KotlinSource {
    String path
    String module
    String text
    KtFile file
    String packageName
    Map<String, String> imports = [:]

    static class Parser implements Closeable {
        private final def disposable = Disposer.newDisposable('spring-policy-parser')
        private final KtPsiFactory factory
        Parser() {
            def environment = KotlinCoreEnvironment.createForProduction(disposable, new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
            factory = new KtPsiFactory(environment.project, false)
        }
        KotlinSource parse(Map input) {
            def s = new KotlinSource(path: input.path, module: input.module, text: input.text)
            s.file = factory.createFile(new File(s.path).name, s.text)
            def error = PsiTreeUtil.findChildOfType(s.file, PsiErrorElement)
            if (error) {
                def location = s.location(error)
                throw new IllegalArgumentException("${s.path}:${location.line}:${location.column}: Kotlin parse error; analysis incomplete: ${error.errorDescription}")
            }
            s.packageName = s.file.packageFqName.asString()
            s.file.importDirectives.findAll { !it.allUnder && it.importedFqName != null }.each {
                def fqn = it.importedFqName.asString()
                s.imports[it.aliasName ?: fqn.tokenize('.').last()] = fqn
            }
            s
        }
        @Override void close() { Disposer.dispose(disposable) }
    }

    List nodes(Class type) { PsiTreeUtil.collectElementsOfType(file, type).toList().sort { it.textOffset } }
    static PsiElement ancestor(PsiElement n, Class... types) {
        for (def p = n.parent; p != null; p = p.parent) {
            if (types.any { it.isInstance(p) }) return p
        }
        null
    }
    static String fqn(String pkg, String name) { pkg ? "${pkg}.${name}".toString() : name }
    String className(KtClass n) { fqn(packageName, n.name) }
    String resolve(String raw, Map index = [:]) {
        if (!raw) return null
        def simple = raw.split(/[<?(]/)[0].trim()
        if (simple.contains('.')) return simple
        if (file.declarations.any { it instanceof KtClassOrObject && it.name == simple }) return fqn(packageName, simple)
        if (imports.containsKey(simple)) return imports[simple]
        if (simple in ['String', 'Int', 'Long', 'Boolean', 'Double', 'Float', 'Short', 'Byte']) return "kotlin.${simple}"
        if (simple in ['List', 'Set', 'Collection']) return "kotlin.collections.${simple}"
        def local = fqn(packageName, simple)
        index.containsKey(local) ? local : null
    }
    String annotationName(KtAnnotationEntry a) { resolve(a.typeReference?.text) }
    Map args(KtAnnotationEntry a) {
        def result = [:]
        a.valueArguments.eachWithIndex { arg, i ->
            result[arg.argumentName?.asName?.asString() ?: i.toString()] = arg.argumentExpression
        }
        result
    }
    List annotations(KtModifierListOwner n) { n.annotationEntries.collect { [name: annotationName(it), node: it] } }
    String annotationValue(KtModifierListOwner n, String suffix, String key) {
        def a = annotations(n).find { it.name in ["jakarta.persistence.${suffix}".toString(), "javax.persistence.${suffix}".toString()] }
        a ? constant(args(a.node)[key]) : null
    }

    Map binding(PsiElement at, String name) {
        def candidates = []
        nodes(KtNamedDeclaration).findAll { (it instanceof KtParameter || it instanceof KtProperty) && it.name == name }.each { n ->
            def scope
            if (n instanceof KtParameter) {
                scope = ancestor(n, KtNamedFunction, KtFunctionLiteral, KtClass, KtCatchClause, KtForExpression)
            } else {
                scope = ancestor(n, KtBlockExpression, KtClassOrObject)
            }
            scope = scope ?: file
            if (!scope.textRange.contains(at.textRange)) return
            if (scope instanceof KtBlockExpression && n.textOffset > at.textOffset) return
            candidates.add([size: scope.textLength, offset: n.textOffset, type: n.typeReference?.text, node: n])
        }
        candidates.sort { a, b -> a.size <=> b.size ?: b.offset <=> a.offset }
        candidates ? candidates.first() : null
    }
    String declaredReceiver(PsiElement at, String name, Map index) {
        resolve(binding(at, name)?.type, index)
    }
    String constant(PsiElement node, int depth = 0) {
        if (!node || depth > 8) return null
        if (node instanceof KtStringTemplateExpression) {
            if (node.entries.any { !(it instanceof KtLiteralStringTemplateEntry || it instanceof KtEscapeStringTemplateEntry) }) return null
            return node.entries.collect { it instanceof KtEscapeStringTemplateEntry ? it.unescapedValue : it.text }.join('')
        }
        if (node instanceof KtDotQualifiedExpression && node.selectorExpression?.text == 'AUTHORIZATION') {
            def receiver = node.receiverExpression
            if (receiver instanceof KtNameReferenceExpression && !binding(node, receiver.text) && resolve(receiver.text) == 'org.springframework.http.HttpHeaders') return 'Authorization'
        }
        if (!(node instanceof KtNameReferenceExpression)) return null
        def b = binding(node, node.referencedName)
        def p = b?.node
        if (p instanceof KtProperty && p.parent instanceof KtFile && p.hasModifier(KtTokens.CONST_KEYWORD)) return constant(p.initializer, depth + 1)
        null
    }
    static Map call(KtCallExpression node) {
        def parent = node.parent
        def qualified = parent instanceof KtQualifiedExpression && parent.selectorExpression == node
        def receiver = qualified ? parent.receiverExpression : null
        [receiver: receiver instanceof KtNameReferenceExpression ? receiver.referencedName : null,
         method: node.calleeExpression?.text,
         args: node.valueArguments.collect { it.argumentExpression },
         node: qualified ? parent : node]
    }
    Map location(PsiElement node) {
        int offset = node.textRange.startOffset
        int start = text.lastIndexOf('\n', Math.max(-1, offset - 1)) + 1
        [line: text.substring(0, offset).count('\n') + 1, column: text.codePointCount(start, offset) + 1]
    }

    static Map index(List<KotlinSource> sources) {
        def result = [:]
        sources.each { s ->
            s.file.declarations.findAll { it instanceof KtClass }.each { KtClass n ->
                def name = s.className(n)
                if (result.containsKey(name)) throw new IllegalArgumentException("Duplicate class in source index: ${name}")
                def annotations = s.annotations(n)
                def isEntity = annotations.any { it.name in ['jakarta.persistence.Entity', 'javax.persistence.Entity'] }
                def model = [fqn: name, name: n.name, entity: isEntity ? (s.annotationValue(n, 'Entity', 'name') ?: n.name) : null,
                             table: s.annotationValue(n, 'Table', 'name'), fields: [:], supers: [], repositoryEntity: null, complex: false]
                def fields = n.primaryConstructorParameters.findAll { it.hasValOrVar() } + n.declarations.findAll { it instanceof KtProperty }
                fields.each { field ->
                    def fieldAnnotations = s.annotations(field)
                    if (fieldAnnotations.any { it.name?.endsWith('.Transient') }) return
                    def type = field.typeReference?.text
                    if (!type) { model.complex = true; return }
                    if (fieldAnnotations.any { a -> !(a.name in ['jakarta.persistence.Id', 'jakarta.persistence.Column', 'jakarta.persistence.GeneratedValue', 'javax.persistence.Id', 'javax.persistence.Column', 'javax.persistence.GeneratedValue']) }) model.complex = true
                    if (!(type.replaceAll(/\?$/, '') in ['String', 'Long', 'Int', 'Boolean', 'Double', 'Float', 'Short', 'Byte'])) model.complex = true
                    if (field instanceof KtProperty && (field.getter || field.delegateExpression)) model.complex = true
                    model.fields[field.name] = [type: type, column: s.annotationValue(field, 'Column', 'name')]
                }
                n.superTypeListEntries.each { entry ->
                    def raw = entry.typeReference?.text
                    if (!raw) return
                    def type = s.resolve(raw) ?: fqn(s.packageName, raw.split('<')[0].trim())
                    model.supers.add(type)
                    if (type in ['org.springframework.data.jpa.repository.JpaRepository', 'org.springframework.data.repository.CrudRepository', 'org.springframework.data.repository.Repository']) {
                        def match = raw =~ /<\s*([^,>]+)/
                        if (match.find()) model.repositoryEntity = s.resolve(match.group(1)) ?: fqn(s.packageName, match.group(1).trim())
                    }
                }
                if (isEntity && model.supers) model.complex = true
                if (annotations.any { a -> a.name?.startsWith('org.hibernate.') || ['.Inheritance', '.SecondaryTable', '.Convert'].any { a.name?.endsWith(it) } }) model.complex = true
                result[name] = model
            }
        }
        result
    }
    static boolean hasSuper(String fqn, List targets, Map index, int depth = 0) {
        if (fqn in targets) return true
        if (depth > 16) return false
        index[fqn]?.supers?.any { hasSuper(it, targets, index, depth + 1) } ?: false
    }
}
