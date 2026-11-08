package com.example.persistence
class FakeManager { fun createQuery(q: String, type: Any): FakeManager = this; fun setParameter(k: String,v: Any): FakeManager = this; val resultList: List<User> = emptyList() }
class Reader(private val em: FakeManager) {
 fun read(email: String): List<*> {
 
 return em.createQuery("select u from User u where u.email = :email", User::class.java).setParameter("email", email).resultList
 }
}