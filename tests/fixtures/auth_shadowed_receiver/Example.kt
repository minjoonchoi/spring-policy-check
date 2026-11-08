package com.example.application
import jakarta.servlet.http.HttpServletRequest
class OtherRequest { fun getHeader(name: String): String? = null }
class Handler(private val request: HttpServletRequest) {
 fun run(): String? { val request: OtherRequest = OtherRequest(); return request.getHeader("Authorization") }
}
