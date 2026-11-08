package com.example.application
import jakarta.servlet.http.HttpServletRequest
const val TOKEN = "Authorization"
class Handler { fun run(request: HttpServletRequest) = request.getHeader(TOKEN) }