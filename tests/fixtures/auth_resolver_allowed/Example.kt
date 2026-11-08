package com.example.security
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
class TokenResolver : BearerTokenResolver {
 override fun resolve(request: HttpServletRequest): String? = request.getHeader("Authorization")
}
