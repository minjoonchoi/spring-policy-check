package com.example.persistence
import jakarta.persistence.EntityManager
class Reader(private val em: EntityManager) {
 fun read(email: String): List<*> {
 
 return em.createNativeQuery("select u.* from users u where u.user_email = :email", User::class.java).setParameter("email", email).resultList
 }
}