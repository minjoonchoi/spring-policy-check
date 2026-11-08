package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
const val SQL = "select u from User u where u.email = :email"
interface UserRepository : JpaRepository<User, Long> {
 
 @Query(SQL)
 fun search(email: String): List<User>
}
