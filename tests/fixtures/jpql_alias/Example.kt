package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query as Q
interface UserRepository : JpaRepository<User, Long> {
 
 @Q("select u from User u where u.email = :email")
 fun search(email: String): List<User>
}
