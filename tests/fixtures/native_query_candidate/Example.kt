package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
interface UserRepository : JpaRepository<User, Long> {
 
 @Query("select u.* from users u where u.user_email = :email", nativeQuery = true)
 fun search(email: String): List<User>
}
