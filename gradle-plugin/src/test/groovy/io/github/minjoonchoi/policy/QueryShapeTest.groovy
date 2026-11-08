package io.github.minjoonchoi.policy

import org.junit.Test
import static org.junit.Assert.*

class QueryShapeTest {
    @Test void equalityAndOrder() {
        assertEquals('findByEmailOrderByIdDesc', QueryShape.method(QueryShape.parse('select u from User u where u.email = :email order by u.id desc')))
    }
    @Test void comparison() {
        assertEquals('findByAgeGreaterThanEqualAndIdLessThan', QueryShape.method(QueryShape.parse('select u from User u where u.age >= ?1 and u.id < ?2')))
    }
    @Test void neverAcceptPrefix() { fails('select u from User u where u.id = ?1 union select x from X x') }
    @Test void rejectsOr() { fails('select u from User u where u.id = ?1 or u.age = ?2') }
    @Test void rejectsFunctions() { fails('select u from User u where lower(u.email) = :email') }
    @Test void rejectsBulkDml() {
        def error = assertThrows(QueryShape.Unsupported) { QueryShape.parse('delete from User u where u.id = ?1') }
        assertEquals('BULK_DML', error.code)
    }
    @Test void mapsNativeColumns() {
        def shape = QueryShape.parse('select u.* from users u where u.user_email = :email order by u.id', true)
        assertEquals('select u from User u where u.email = :email order by u.id asc', QueryShape.jpql(shape, 'User', [user_email: 'email', id: 'id']))
    }
    @Test void rejectsUnmappedColumns() {
        def shape = QueryShape.parse('select u.* from users u where u.unknown = :email', true)
        assertEquals('UNMAPPED_COLUMN', assertThrows(QueryShape.Unsupported) { QueryShape.jpql(shape, 'User', [:]) }.code)
    }
    private static void fails(String query) { assertThrows(QueryShape.Unsupported) { QueryShape.parse(query) } }
}
