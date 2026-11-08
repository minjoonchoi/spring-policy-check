package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
annotation class Query(val value: String)
interface UserRepository : JpaRepository<User, Long> {
 
 @Query("select u from User u where u.email = :email")
 fun search(email: String): List<User>
}
