package dev.jaganet.server

import dev.jaganet.api.ErrorCode

/**
 * An error for the client. [message] is the English text and the translation key
 * (see shared/.../i18n/strings/Server.kt); `{placeholders}` are filled from [args].
 * Plural messages have forms separated by `|` and take their count from args["n"].
 */
class AppError(val status: Int, val code: ErrorCode, message: String, val args: Map<String, Any?> = emptyMap()) : Exception(message)

fun badRequest(m: String) = AppError(400, ErrorCode.BAD_REQUEST, m)
fun unauthorized() = AppError(401, ErrorCode.UNAUTHORIZED, "Sign in again")
fun forbidden() = AppError(403, ErrorCode.FORBIDDEN, "Not allowed")
fun notFound(m: String = "Not found") = AppError(404, ErrorCode.NOT_FOUND, m)
