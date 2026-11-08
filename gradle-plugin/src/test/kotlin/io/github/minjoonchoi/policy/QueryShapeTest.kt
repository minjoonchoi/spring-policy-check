package io.github.minjoonchoi.policy

import org.junit.Assert.*
import org.junit.Test

class QueryShapeTest {
    @Test fun equalityAndOrder() {
        assertEquals("findByEmailOrderByIdDesc", QueryShape.method(QueryShape.parse("select u from User u where u.email = :email order by u.id desc")))
    }
    @Test fun comparison() {
        assertEquals("findByAgeGreaterThanEqualAndIdLessThan", QueryShape.method(QueryShape.parse("select u from User u where u.age >= ?1 and u.id < ?2")))
    }
    @Test fun neverAcceptPrefix() { fails("select u from User u where u.id = ?1 union select x from X x") }
    @Test fun rejectsOr() { fails("select u from User u where u.id = ?1 or u.age = ?2") }
    @Test fun rejectsFunctions() { fails("select u from User u where lower(u.email) = :email") }
    @Test fun rejectsBulkDml() {
        val error = assertThrows(QueryShape.Unsupported::class.java) { QueryShape.parse("delete from User u where u.id = ?1") }
        assertEquals("BULK_DML", error.code)
    }
    @Test fun mapsNativeColumns() {
        val shape = QueryShape.parse("select u.* from users u where u.user_email = :email order by u.id", true)
        assertEquals("select u from User u where u.email = :email order by u.id asc", QueryShape.jpql(shape, "User", mapOf("user_email" to "email", "id" to "id")))
    }
    @Test fun rejectsUnmappedColumns() {
        val shape = QueryShape.parse("select u.* from users u where u.unknown = :email", true)
        assertEquals("UNMAPPED_COLUMN", assertThrows(QueryShape.Unsupported::class.java) { QueryShape.jpql(shape, "User", emptyMap()) }.code)
    }
    private fun fails(query: String) { assertThrows(QueryShape.Unsupported::class.java) { QueryShape.parse(query) } }
}
