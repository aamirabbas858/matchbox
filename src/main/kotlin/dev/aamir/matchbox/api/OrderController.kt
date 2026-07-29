package dev.aamir.matchbox.api

import dev.aamir.matchbox.engine.MatchingEngine
import dev.aamir.matchbox.engine.Order
import dev.aamir.matchbox.engine.OrderType
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class OrderController(private val engine: MatchingEngine) {

    /**
     * Submits an order. Matches immediately against the book; whatever is left
     * rests, unless it is a MARKET order.
     *
     * The id is generated here rather than accepted from the client. A client
     * that picks its own id can collide with another client's, and the engine
     * rejects duplicates — so accepting them would turn one caller's mistake
     * into another caller's failure.
     */
    @PostMapping("/orders")
    fun place(@Valid @RequestBody request: PlaceOrderRequest): ResponseEntity<PlaceOrderResponse> {
        val order = Order(
            id = UUID.randomUUID().toString(),
            side = request.side,
            type = request.type,
            price = if (request.type == OrderType.MARKET) 0 else request.price,
            quantity = request.quantity,
        )
        val result = engine.submit(order)
        return ResponseEntity.status(HttpStatus.CREATED).body(PlaceOrderResponse.from(result))
    }

    @GetMapping("/orders/{id}")
    fun get(@PathVariable id: String): ResponseEntity<OrderResponse> =
        engine.orderById(id)
            ?.let { ResponseEntity.ok(OrderResponse.from(it)) }
            ?: ResponseEntity.notFound().build()

    /**
     * Cancels an order's remaining quantity.
     *
     * 409 rather than 404 when the order exists but has nothing left to
     * cancel — already filled, already cancelled. The distinction matters to a
     * client deciding whether to retry: "never existed" is a bug on their side,
     * "too late" is a race they lost.
     */
    @DeleteMapping("/orders/{id}")
    fun cancel(@PathVariable id: String): ResponseEntity<Any> = when {
        engine.cancel(id) -> ResponseEntity.noContent().build()
        engine.orderById(id) == null -> ResponseEntity.notFound().build()
        else -> ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("not cancellable", "order is already filled or cancelled"))
    }

    @GetMapping("/book")
    fun book(@RequestParam(defaultValue = "10") depth: Int): BookResponse =
        BookResponse.from(engine.snapshot(depth.coerceIn(1, 100)))

    @GetMapping("/trades")
    fun trades(@RequestParam(defaultValue = "50") limit: Int): List<TradeResponse> =
        engine.trades().takeLast(limit.coerceIn(1, 500)).map(TradeResponse::from)
}
