package com.example.application
import jakarta.servlet.http.HttpServletRequest as Request
class Handler { fun run(request: Request) = request.getHeader("Authorization") }