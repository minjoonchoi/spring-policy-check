package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
interface UserRepository : JpaRepository<User, Long> {
 
 @Query("select u from User u where u.age >= ?1 order by u.id desc")
 fun search(age: Int): List<User>
}
