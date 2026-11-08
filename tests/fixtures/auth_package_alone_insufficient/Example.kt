package com.example.security
import jakarta.servlet.http.HttpServletRequest
class FakeFilter { fun run(request: HttpServletRequest) = request.getHeader("Authorization") }