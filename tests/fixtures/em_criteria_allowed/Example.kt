package com.example.persistence
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaQuery
class Reader(private val em: EntityManager) {
 fun read(criteria: CriteriaQuery<User>): List<*> {
 
 return em.createQuery(criteria).resultList
 }
}