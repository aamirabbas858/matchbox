package dev.aamir.matchbox.api

import dev.aamir.matchbox.engine.BookSnapshot
import dev.aamir.matchbox.engine.MatchResult
import dev.aamir.matchbox.engine.Order
import dev.aamir.matchbox.engine.OrderType
import dev.aamir.matchbox.engine.Side
import dev.aamir.matchbox.engine.Trade
import dev.aamir.matchbox.persistence.TradeRecord
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero

/**
 * What crosses the wire.
 *
 * Kept separate from the engine's own types on purpose. The domain is free to
 * change shape without breaking every client, and — more importantly here —
 * the API cannot accidentally expose an internal field by adding one. Engine
 * types are not annotated with anything web-related and never will be.
 *
 * Prices and quantities are minor units, as everywhere else. The API does not
 * accept decimals: "50.50" would have to be parsed into a float somewhere, and
 * the whole point is that no float ever touches a price.
 */

data class PlaceOrderRequest(
    val side: Side,
    val type: OrderType = OrderType.LIMIT,
    /** Minor units. Ignored for MARKET orders, which have no price. */
    @field:PositiveOrZero
    val price: Long = 0,
    @field:Positive
    val quantity: Long,
)

data class OrderResponse(
    val id: String,
    val side: Side,
    val type: OrderType,
    val price: Long,
    val quantity: Long,
    val filled: Long,
    val remaining: Long,
    val status: String,
) {
    companion object {
        fun from(o: Order) = OrderResponse(
            id = o.id,
            side = o.side,
            type = o.type,
            price = o.price,
            quantity = o.quantity,
            filled = o.filled,
            remaining = o.remaining,
            status = o.status.name,
        )
    }
}

data class TradeResponse(
    val buyOrderId: String,
    val sellOrderId: String,
    val price: Long,
    val quantity: Long,
    /** price × quantity, computed here so clients never multiply money themselves. */
    val notional: Long,
    val sequence: Long,
) {
    companion object {
        fun from(t: Trade) = TradeResponse(
            buyOrderId = t.buyOrderId,
            sellOrderId = t.sellOrderId,
            price = t.price,
            quantity = t.quantity,
            notional = t.notional,
            sequence = t.sequence,
        )

        /** Same shape whether the trade came from the engine or the log. */
        fun from(t: TradeRecord) = TradeResponse(
            buyOrderId = t.buyOrderId,
            sellOrderId = t.sellOrderId,
            price = t.price,
            quantity = t.quantity,
            notional = t.notional,
            sequence = t.sequence,
        )
    }
}

data class PlaceOrderResponse(
    val order: OrderResponse,
    val trades: List<TradeResponse>,
    val filledQuantity: Long,
) {
    companion object {
        fun from(r: MatchResult) = PlaceOrderResponse(
            order = OrderResponse.from(r.order),
            trades = r.trades.map(TradeResponse::from),
            filledQuantity = r.filledQuantity,
        )
    }
}

data class LevelResponse(val price: Long, val quantity: Long, val orders: Int)

data class BookResponse(
    val bids: List<LevelResponse>,
    val asks: List<LevelResponse>,
    val bestBid: Long?,
    val bestAsk: Long?,
    val spread: Long?,
) {
    companion object {
        fun from(b: BookSnapshot) = BookResponse(
            bids = b.bids.map { LevelResponse(it.price, it.quantity, it.orders) },
            asks = b.asks.map { LevelResponse(it.price, it.quantity, it.orders) },
            bestBid = b.bestBid,
            bestAsk = b.bestAsk,
            spread = b.spread,
        )
    }
}

/** A failure the caller can act on. No stack traces, no internal type names. */
data class ApiError(val error: String, val detail: String? = null)
