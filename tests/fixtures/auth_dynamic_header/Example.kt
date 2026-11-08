package com.example.application
import jakarta.servlet.http.HttpServletRequest
class Handler { fun run(request: HttpServletRequest, name: String) = request.getHeader(name) }