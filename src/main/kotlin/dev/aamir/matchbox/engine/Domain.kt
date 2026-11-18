package dev.aamir.matchbox.engine

/**
 * The vocabulary of an order book.
 *
 * Money is an integer count of minor units - cents, not euros. Never a Double.
 * `0.1 + 0.2` is `0.30000000000000004` in binary floating point, and a price
 * that is a fraction of a cent out is a trade that cannot be settled. Every
 * price and total in this engine is a Long, and formatting for humans happens
 * at the very edge or not at all.
 */

enum class Side {
    BUY,
    SELL;

    val opposite: Side get() = if (this == BUY) SELL else BUY
}

enum class OrderType {
    /** Executes only at [Order.price] or better; rests in the book if unfilled. */
    LIMIT,

    /** Executes at whatever the book offers and never rests. */
    MARKET,
}

enum class OrderStatus { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }

/**
 * A resting or incoming order.
 *
 * [sequence] is assigned by the engine on arrival and is what time priority is
 * decided by - not a wall-clock timestamp. Two orders can share a millisecond;
 * they cannot share a sequence number. Using a clock here would make the
 * outcome depend on how fast the machine is, which is exactly the property an
 * exchange must not have.
 */
data class Order(
    val id: String,
    val side: Side,
    val type: OrderType,
    /** In minor units. Ignored for MARKET orders. */
    val price: Long,
    val quantity: Long,
    val filled: Long = 0,
    val sequence: Long = 0,
    val status: OrderStatus = OrderStatus.OPEN,
) {
    val remaining: Long get() = quantity - filled

    init {
        require(quantity > 0) { "quantity must be positive, was $quantity" }
        require(filled in 0..quantity) { "filled $filled outside 0..$quantity" }
        // A limit order at zero or below is not a price, it is a mistake. Market
        // orders carry no price at all, so the check does not apply to them.
        if (type == OrderType.LIMIT) {
            require(price > 0) { "limit price must be positive, was $price" }
        }
    }
}

/**
 * An executed trade. Immutable and append-only: this is the record, and a
 * record that can be edited is not one.
 */
data class Trade(
    val buyOrderId: String,
    val sellOrderId: String,
    /** The resting order's price - see [MatchingEngine] for why. */
    val price: Long,
    val quantity: Long,
    val sequence: Long,
) {
    /** Total consideration in minor units. Long arithmetic throughout. */
    val notional: Long get() = price * quantity
}

/** One price level as shown to the outside world. */
data class Level(val price: Long, val quantity: Long, val orders: Int)

/** A snapshot of the book, best prices first. */
data class BookSnapshot(val bids: List<Level>, val asks: List<Level>) {
    val bestBid: Long? get() = bids.firstOrNull()?.price
    val bestAsk: Long? get() = asks.firstOrNull()?.price

    /**
     * The gap between the best bid and best ask. Null when either side is
     * empty. Should never be negative - see the invariant test.
     */
    val spread: Long? get() = bestBid?.let { b -> bestAsk?.let { a -> a - b } }
}

/** What happened to a submitted order. */
data class MatchResult(
    val order: Order,
    val trades: List<Trade>,
) {
    val filledQuantity: Long get() = trades.sumOf { it.quantity }
}
