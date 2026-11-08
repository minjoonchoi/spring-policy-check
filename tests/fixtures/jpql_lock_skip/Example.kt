package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
interface UserRepository : JpaRepository<User, Long> {
 @Lock(LockModeType.PESSIMISTIC_READ)
 @Query("select u from User u where u.email = :email")
 fun search(email: String): List<User>
}
