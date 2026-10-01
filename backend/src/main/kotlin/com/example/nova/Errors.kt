package com.example.nova

import io.ktor.http.HttpStatusCode

/** Fachlicher Fehler mit Code für die App. `messageKey` verweist auf einen UI-Text im CMS. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

fun badRequest(code: String, message: String) = ApiException(HttpStatusCode.BadRequest, code, message)
fun unauthorized(message: String = "Nicht angemeldet") = ApiException(HttpStatusCode.Unauthorized, "unauthorized", message)
fun forbidden(message: String = "Keine Berechtigung") = ApiException(HttpStatusCode.Forbidden, "forbidden", message)
fun notFound(message: String = "Nicht gefunden") = ApiException(HttpStatusCode.NotFound, "not_found", message)
fun tooMany(message: String) = ApiException(HttpStatusCode.TooManyRequests, "rate_limited", message)
