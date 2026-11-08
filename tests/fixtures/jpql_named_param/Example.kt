package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
interface UserRepository : JpaRepository<User, Long> {
 
 @Query("select u from User u where u.email = :e")
 fun search(@Param("e") email: String): List<User>
}
