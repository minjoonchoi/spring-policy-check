package com.example.application
import org.springframework.web.bind.annotation.RequestHeader as Header
class Handler { fun run(@Header(name = "Authorization") token: String) = token }
