package com.example.security
import jakarta.servlet.http.HttpServletRequest
class TokenReader { fun extract(request: HttpServletRequest): String? = request.getHeader("Authorization") }
