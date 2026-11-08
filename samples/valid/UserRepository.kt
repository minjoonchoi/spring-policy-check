package com.example.persistence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
interface UserRepository : JpaRepository<User, Long> { fun findByEmail(email: String): List<User> }
