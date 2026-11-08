package com.example.application
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
class Handler { fun run(@AuthenticationPrincipal principal: UserDetails) = principal.username }
