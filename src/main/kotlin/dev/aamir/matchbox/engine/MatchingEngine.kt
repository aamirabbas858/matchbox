package dev.aamir.matchbox.engine

import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A limit order book with price-time priority.
 *
 * Two rules decide everything:
 *
 *   PRICE PRIORITY  the best price is filled first - the cheapest ask, the
 *                   highest bid.
 *
 *   TIME PRIORITY   among orders at the same price, the one that arrived
 *                   first is filled first.
 *
 * The second rule is the fairness guarantee an exchange exists to provide.
 * Break it and later traders are quietly advantaged over earlier ones, which
 * is invisible until somebody tests for it - so there is a test for it.
 *
 * Both rules fall out of the data structure rather than being enforced by
 * code. A TreeMap keyed by price gives price ordering; an ArrayDeque at each
 * price gives arrival ordering. Nothing has to remember to sort.
 *
 * --- trades execute at the RESTING order's price ---
 *
 * If a buyer bids 51 and the best ask is 50, the trade happens at 50. The
 * price improvement goes to the arriving order, and the resting order gets
 * exactly what it asked for and no less. This is the standard rule and it is
 * the one most first attempts get wrong - they execute at the incoming price,
 * which silently overcharges every taker.
 *
 * --- single-threaded on purpose ---
 *
 * Every mutating operation is synchronized. Two orders arriving at the same
 * instant must produce one deterministic outcome, and the cheapest way to
 * guarantee that is to serialise matching. Real engines do the same thing with
 * more machinery; the property is identical.
 */
class MatchingEngine {

    /**
     * Bids descend: the highest price is the best bid, so it must sort first.
     * Asks ascend: the lowest price is the best ask.
     */
    private val bids = TreeMap<Long, ArrayDeque<Order>>(reverseOrder())
    private val asks = TreeMap<Long, ArrayDeque<Order>>()

    /** Every order the engine has seen, by id, so cancels can find them. */
    private val byId = HashMap<String, Order>()

    private val sequence = AtomicLong(0)
    private val executed = mutableListOf<Trade>()

    private fun bookFor(side: Side) = if (side == Side.BUY) bids else asks

    /**
     * Submits an order and matches it against the opposite side as far as it
     * will go. Anything left over rests in the book - unless it is a MARKET
     * order, which never rests.
     */
    @Synchronized
    fun submit(order: Order): MatchResult {
        require(order.id !in byId) { "duplicate order id ${order.id}" }

        var working = order.copy(sequence = sequence.incrementAndGet())
        val trades = mutableListOf<Trade>()
        val opposite = bookFor(working.side.opposite)

        // Walk the opposite book from its best price outward, taking whole
        // price levels until the order is filled or no price crosses.
        while (working.remaining > 0 && opposite.isNotEmpty()) {
            val (restingPrice, queue) = opposite.firstEntry()
            if (!crosses(working, restingPrice)) break

            while (working.remaining > 0 && queue.isNotEmpty()) {
                val resting = queue.first()
                val quantity = minOf(working.remaining, resting.remaining)

                trades += Trade(
                    buyOrderId = if (working.side == Side.BUY) working.id else resting.id,
                    sellOrderId = if (working.side == Side.SELL) working.id else resting.id,
                    price = restingPrice,   // the resting order's price, never the incoming one
                    quantity = quantity,
                    sequence = sequence.incrementAndGet(),
                )

                working = working.fill(quantity)
                val restingAfter = resting.fill(quantity)
                byId[resting.id] = restingAfter

                // Fully filled orders leave the queue; partially filled ones
                // keep their place at the front, which is what preserves time
                // priority across a partial execution.
                if (restingAfter.remaining == 0L) queue.removeFirst()
                else queue[0] = restingAfter
            }

            // An emptied price level must go, or firstEntry() keeps returning
            // a level with nothing in it and the outer loop spins forever.
            if (queue.isEmpty()) opposite.remove(restingPrice)
        }

        working = when {
            working.remaining == 0L -> working.copy(status = OrderStatus.FILLED)
            // A market order that could not be filled does not wait for a
            // better book - there is no price at which it was willing to rest.
            working.type == OrderType.MARKET -> working.copy(
                status = if (working.filled > 0) OrderStatus.PARTIALLY_FILLED else OrderStatus.REJECTED
            )
            else -> {
                bookFor(working.side).getOrPut(working.price) { ArrayDeque() }.addLast(working)
                working.copy(
                    status = if (working.filled > 0) OrderStatus.PARTIALLY_FILLED else OrderStatus.OPEN
                )
            }
        }

        byId[working.id] = working
        executed += trades
        return MatchResult(working, trades)
    }

    /**
     * Removes an order's remaining quantity from the book.
     *
     * Returns false when the order is unknown or already gone. Cancelling
     * cannot undo a fill: quantity already traded belongs to somebody else.
     */
    @Synchronized
    fun cancel(orderId: String): Boolean {
        val order = byId[orderId] ?: return false
        if (order.status == OrderStatus.FILLED || order.status == OrderStatus.CANCELLED) return false

        val queue = bookFor(order.side)[order.price] ?: return false
        if (!queue.removeIf { it.id == orderId }) return false
        if (queue.isEmpty()) bookFor(order.side).remove(order.price)

        byId[orderId] = order.copy(status = OrderStatus.CANCELLED)
        return true
    }

    /**
     * Clears the book, the order index and the trade list.
     *
     * Start-of-session behaviour rather than a test hook: a real book does not
     * carry resting orders across a trading session, and day orders expire at
     * the close. Tests use it for the same reason - a clean book - which is a
     * happy coincidence rather than the justification.
     */
    @Synchronized
    fun reset() {
        bids.clear()
        asks.clear()
        byId.clear()
        executed.clear()
        sequence.set(0)
    }

    @Synchronized
    fun orderById(orderId: String): Order? = byId[orderId]

    @Synchronized
    fun trades(): List<Trade> = executed.toList()

    /** Aggregated depth, best prices first. [depth] limits the levels shown. */
    @Synchronized
    fun snapshot(depth: Int = 10): BookSnapshot = BookSnapshot(
        bids = bids.levels(depth),
        asks = asks.levels(depth),
    )

    private fun TreeMap<Long, ArrayDeque<Order>>.levels(depth: Int) =
        entries.take(depth).map { (price, queue) ->
            Level(price, queue.sumOf { it.remaining }, queue.size)
        }

    /**
     * Whether an incoming order is willing to trade at [restingPrice].
     *
     * A market order crosses anything - that is what makes it a market order.
     * A limit buy crosses an ask at or below its price; a limit sell crosses a
     * bid at or above its price.
     */
    private fun crosses(incoming: Order, restingPrice: Long): Boolean = when {
        incoming.type == OrderType.MARKET -> true
        incoming.side == Side.BUY -> restingPrice <= incoming.price
        else -> restingPrice >= incoming.price
    }
}

private fun Order.fill(quantity: Long): Order {
    val total = filled + quantity
    check(total <= this.quantity) { "overfill: $total > ${this.quantity} on $id" }
    return copy(
        filled = total,
        status = if (total == this.quantity) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED,
    )
}
