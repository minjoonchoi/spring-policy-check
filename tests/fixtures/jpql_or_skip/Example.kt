package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
interface UserRepository : JpaRepository<User, Long> {
 
 @Query("select u from User u where u.email = :email or u.id = :id")
 fun search(email: String, id: Long): List<User>
}
