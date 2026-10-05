package dev.jaganet.server

import dev.jaganet.api.ErrorCode

class AppError(val status: Int, val code: ErrorCode, message: String) : Exception(message)

fun badRequest(m: String) = AppError(400, ErrorCode.BAD_REQUEST, m)
fun unauthorized() = AppError(401, ErrorCode.UNAUTHORIZED, "Sign in again")
fun forbidden() = AppError(403, ErrorCode.FORBIDDEN, "Not allowed")
fun notFound(m: String = "Not found") = AppError(404, ErrorCode.NOT_FOUND, m)
