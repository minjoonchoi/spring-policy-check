package com.example.application
import jakarta.servlet.http.HttpServletRequest
class Handler { fun run(request: HttpServletRequest) = request.getHeader("Authorization") }