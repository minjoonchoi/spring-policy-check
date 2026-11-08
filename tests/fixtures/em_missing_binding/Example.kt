package com.example.persistence
import jakarta.persistence.EntityManager
class Reader(private val em: EntityManager) {
 fun read(email: String): List<*> {
 
 return em.createQuery("select u from User u where u.email = :email", User::class.java).resultList
 }
}