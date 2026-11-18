package dev.aamir.matchbox.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns failures into answers a client can act on.
 *
 * Without this, Spring returns its own error shape - which includes the
 * exception's message, and sometimes a stack trace. An engine assertion like
 * "overfill: 12 > 10 on o37" tells an attacker about internal state and tells
 * a legitimate client nothing useful. Every case below is mapped deliberately,
 * and the fallback says as little as possible.
 */
@RestControllerAdvice
class ErrorHandling {

    /** Bean validation: a negative quantity, a missing field. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val detail = e.bindingResult.fieldErrors.joinToString("; ") {
            "${it.field} ${it.defaultMessage}"
        }
        return ResponseEntity.badRequest().body(ApiError("invalid request", detail))
    }

    /**
     * The engine's own `require` checks land here - a limit order priced at
     * zero, a quantity of zero. These are the caller's mistake, so 400 rather
     * than 500, and the message is the engine's because it is written to be
     * read by a person.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun rejected(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError("rejected", e.message))

    /** Malformed JSON, or an unknown value for an enum like side or type. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError("malformed request", "check JSON syntax, and that side is BUY/SELL and type is LIMIT/MARKET")
        )

    /**
     * Anything unanticipated. Logged in full server-side by Spring; the client
     * gets a bare acknowledgement, because an unexpected failure is the case
     * where leaking internals is most likely and least useful.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("internal error"))
}
