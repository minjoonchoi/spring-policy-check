package com.example.persistence
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Id
import jakarta.persistence.Column
import jakarta.persistence.Convert
@Entity
@Table(name = "users")
class User(
    @Id @Column(name = "id") val id: Long,
    @Convert(disableConversion = true) @Column(name = "user_email") val email: String,
    @Column(name = "age") val age: Int,
    @Column(name = "created_at") val createdAt: Long
)
