package com.example.application
import org.springframework.security.oauth2.jwt.JwtDecoder
class Handler(private val decoder: JwtDecoder) { fun run(token: String) = decoder.decode(token) }
