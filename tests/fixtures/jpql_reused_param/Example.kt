package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
interface UserRepository : JpaRepository<User, Long> {
 
 @Query("select u from User u where u.id = :n and u.createdAt = :n")
 fun search(n: Long): List<User>
}
