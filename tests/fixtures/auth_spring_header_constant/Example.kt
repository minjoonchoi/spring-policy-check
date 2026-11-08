package com.example.application
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
class Handler { fun run(request: HttpServletRequest) = request.getHeader(HttpHeaders.AUTHORIZATION) }