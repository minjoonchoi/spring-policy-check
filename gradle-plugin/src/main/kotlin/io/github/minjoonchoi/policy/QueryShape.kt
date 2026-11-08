package io.github.minjoonchoi.policy

import java.util.Locale

/** Closed JPQL/SQL subset. Never accepts only a prefix of a query. */
data class QueryShape(val entity: String, val alias: String, val predicates: List<Predicate>, val order: Order?) {
    data class Predicate(val property: String, val operator: String, val parameter: String)
    data class Order(val property: String, val direction: String)
    class Unsupported(val code: String) : RuntimeException(code)

    private class Cursor(val tokens: List<String>) {
        var position = 0
        fun take(): String = if (position < tokens.size) tokens[position++] else reject("UNSUPPORTED_QUERY")
        fun peek(token: String): Boolean = position < tokens.size && tokens[position].equals(token, ignoreCase = true)
        fun expect(token: String) { if (!take().equals(token, ignoreCase = true)) reject("UNSUPPORTED_QUERY") }
        fun ident(): String = take().also { if (!it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) reject("UNSUPPORTED_QUERY") }
        fun property(alias: String): String { expect(alias); expect("."); return ident() }
    }

    companion object {
        fun reject(code: String): Nothing = throw Unsupported(code)

        fun parse(query: String, nativeQuery: Boolean = false): QueryShape {
            val tokens = mutableListOf<String>()
            val tokenPattern = Regex("[A-Za-z_][A-Za-z0-9_]*|[:?][A-Za-z0-9_]+|[<>]=?|[.*=]").toPattern()
            var offset = 0
            while (offset < query.length) {
                if (Character.isWhitespace(query[offset])) { offset++; continue }
                val matcher = tokenPattern.matcher(query).region(offset, query.length)
                if (!matcher.lookingAt()) reject("UNSUPPORTED_QUERY")
                tokens.add(matcher.group())
                offset = matcher.end()
            }
            val cursor = Cursor(tokens)
            if (listOf("delete", "update", "insert").any { cursor.peek(it) }) reject("BULK_DML")
            cursor.expect("select")
            val selected = cursor.ident()
            if (nativeQuery) { cursor.expect("."); cursor.expect("*") }
            cursor.expect("from")
            val entity = cursor.ident()
            val alias = cursor.ident()
            if (selected != alias) reject("PROJECTION")
            cursor.expect("where")
            val predicates = mutableListOf<Predicate>()
            while (true) {
                val property = cursor.property(alias)
                val operator = cursor.take()
                if (operator !in listOf("=", ">", ">=", "<", "<=")) reject("UNSUPPORTED_OPERATOR")
                val parameter = cursor.take()
                if (!parameter.startsWith(':') && !parameter.startsWith('?')) reject("NON_PARAMETER_OPERAND")
                predicates.add(Predicate(property, operator, parameter))
                if (!cursor.peek("and")) break
                cursor.take()
            }
            val order = if (cursor.peek("order")) {
                cursor.take(); cursor.expect("by")
                val property = cursor.property(alias)
                val direction = if (cursor.peek("asc") || cursor.peek("desc")) cursor.take().lowercase(Locale.ROOT) else "asc"
                Order(property, direction)
            } else null
            if (cursor.position != cursor.tokens.size) reject("UNSUPPORTED_QUERY")
            return QueryShape(entity, alias, predicates, order)
        }

        fun method(shape: QueryShape): String {
            val suffix = mapOf("=" to "", ">" to "GreaterThan", ">=" to "GreaterThanEqual", "<" to "LessThan", "<=" to "LessThanEqual")
            val name = "findBy" + shape.predicates.joinToString("And") { upper(it.property) + suffix.getValue(it.operator) }
            return name + (shape.order?.let { "OrderBy" + upper(it.property) + upper(it.direction) } ?: "")
        }

        fun jpql(shape: QueryShape, entity: String, columns: Map<String, String>): String {
            fun attr(column: String): String = columns[column] ?: reject("UNMAPPED_COLUMN")
            val where = shape.predicates.joinToString(" and ") { "${shape.alias}.${attr(it.property)} ${it.operator} ${it.parameter}" }
            val result = "select ${shape.alias} from $entity ${shape.alias} where $where"
            return result + (shape.order?.let { " order by ${shape.alias}.${attr(it.property)} ${it.direction}" } ?: "")
        }

        private fun upper(value: String): String = value.substring(0, 1).uppercase(Locale.ROOT) + value.substring(1)
    }
}
