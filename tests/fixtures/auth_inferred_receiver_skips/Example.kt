package com.example.application
import jakarta.servlet.http.HttpServletRequest
class Handler {
 fun run(request: HttpServletRequest): String? { val alias = request; return alias.getHeader("Authorization") }
}
