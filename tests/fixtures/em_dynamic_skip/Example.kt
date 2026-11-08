package com.example.persistence
import jakarta.persistence.EntityManager
class Reader(private val em: EntityManager) {
 fun read(email: String): List<*> {
 val sql = "select u from User u where u.email = :email"
 return em.createQuery(sql, User::class.java).setParameter("email", email).resultList
 }
}