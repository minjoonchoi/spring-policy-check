package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.NativeQuery
interface UserRepository : JpaRepository<User, Long> {
 
 @NativeQuery("select u.* from users u where u.user_email = :email")
 fun search(email: String): List<User>
}
