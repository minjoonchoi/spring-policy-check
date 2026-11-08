package io.github.minjoonchoi.policy

/** Closed JPQL/SQL subset. Never accepts only a prefix of a query. */
class QueryShape {
    static class Unsupported extends RuntimeException {
        final String code
        Unsupported(String code) { super(code); this.code = code }
    }
    static void reject(String code) { throw new Unsupported(code) }

    private static class Cursor {
        List<String> tokens = []
        int position
        String take() {
            if (position >= tokens.size()) reject('UNSUPPORTED_QUERY')
            tokens[position++]
        }
        boolean peek(String token) { position < tokens.size() && tokens[position].equalsIgnoreCase(token) }
        void expect(String token) { if (!take().equalsIgnoreCase(token)) reject('UNSUPPORTED_QUERY') }
        String ident() {
            def token = take()
            if (!(token ==~ /[A-Za-z_][A-Za-z0-9_]*/)) reject('UNSUPPORTED_QUERY')
            token
        }
        String property(String alias) { expect(alias); expect('.'); ident() }
    }

    static Map parse(String query, boolean nativeQuery = false) {
        def cursor = new Cursor()
        def tokenPattern = ~/[A-Za-z_][A-Za-z0-9_]*|[:?][A-Za-z0-9_]+|[<>]=?|[.*=]/
        int offset = 0
        while (offset < query.length()) {
            if (Character.isWhitespace(query.charAt(offset))) { offset++; continue }
            def matcher = tokenPattern.matcher(query).region(offset, query.length())
            if (!matcher.lookingAt()) reject('UNSUPPORTED_QUERY')
            cursor.tokens.add(matcher.group())
            offset = matcher.end()
        }
        if (['delete', 'update', 'insert'].any { cursor.peek(it) }) reject('BULK_DML')
        cursor.expect('select')
        def selected = cursor.ident()
        if (nativeQuery) { cursor.expect('.'); cursor.expect('*') }
        cursor.expect('from')
        def entity = cursor.ident()
        def alias = cursor.ident()
        if (selected != alias) reject('PROJECTION')
        cursor.expect('where')
        def predicates = []
        while (true) {
            def property = cursor.property(alias)
            def operator = cursor.take()
            if (!(operator in ['=', '>', '>=', '<', '<='])) reject('UNSUPPORTED_OPERATOR')
            def parameter = cursor.take()
            if (!(parameter.startsWith(':') || parameter.startsWith('?'))) reject('NON_PARAMETER_OPERAND')
            predicates.add([property: property, operator: operator, parameter: parameter])
            if (!cursor.peek('and')) break
            cursor.take()
        }
        def order = null
        if (cursor.peek('order')) {
            cursor.take(); cursor.expect('by')
            def property = cursor.property(alias)
            def direction = cursor.peek('asc') || cursor.peek('desc') ? cursor.take().toLowerCase(Locale.ROOT) : 'asc'
            order = [property: property, direction: direction]
        }
        if (cursor.position != cursor.tokens.size()) reject('UNSUPPORTED_QUERY')
        [entity: entity, alias: alias, predicates: predicates, order: order]
    }

    static String method(Map shape) {
        def suffix = ['=': '', '>': 'GreaterThan', '>=': 'GreaterThanEqual', '<': 'LessThan', '<=': 'LessThanEqual']
        def name = 'findBy' + shape.predicates.collect { upper(it.property) + suffix[it.operator] }.join('And')
        if (shape.order) name += 'OrderBy' + upper(shape.order.property) + upper(shape.order.direction)
        name
    }
    static String jpql(Map shape, String entity, Map columns) {
        def attr = { String column ->
            if (!columns.containsKey(column)) reject('UNMAPPED_COLUMN')
            columns[column]
        }
        def where = shape.predicates.collect { "${shape.alias}.${attr(it.property)} ${it.operator} ${it.parameter}" }.join(' and ')
        def result = "select ${shape.alias} from ${entity} ${shape.alias} where ${where}"
        if (shape.order) result += " order by ${shape.alias}.${attr(shape.order.property)} ${shape.order.direction}"
        result
    }
    private static String upper(String s) { s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1) }
}
