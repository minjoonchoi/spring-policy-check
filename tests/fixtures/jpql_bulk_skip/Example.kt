package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
interface UserRepository : JpaRepository<User, Long> {
 
 @Query("delete from User u where u.email = :email")
 fun search(email: String): Int
}
