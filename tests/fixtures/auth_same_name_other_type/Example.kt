package com.example.application
class OtherRequest {
    fun getHeader(name: String): String? = null
}
class Handler {
    fun run(request: OtherRequest) = request.getHeader("Authorization")
}
