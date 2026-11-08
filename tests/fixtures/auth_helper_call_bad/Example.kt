package com.example.application
import com.example.security.TokenReader
import jakarta.servlet.http.HttpServletRequest
class Handler(private val tokens: TokenReader) { fun run(request: HttpServletRequest) = tokens.extract(request) }
