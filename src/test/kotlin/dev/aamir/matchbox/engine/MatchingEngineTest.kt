package dev.aamir.matchbox.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules an exchange exists to guarantee.
 *
 * Each test here is a property that either holds or reveals a real defect —
 * not a check that the code does what the code does. Price-time priority in
 * particular fails silently: a book that fills the wrong order still returns
 * plausible trades at plausible prices, and nobody notices until somebody who
 * queued first asks why they did not get filled.
 */
class MatchingEngineTest {

    private var n = 0
    private fun id() = "o${++n}"

    private fun limit(side: Side, price: Long, qty: Long, id: String = id()) =
        Order(id = id, side = side, type = OrderType.LIMIT, price = price, quantity = qty)

    private fun market(side: Side, qty: Long, id: String = id()) =
        Order(id = id, side = side, type = OrderType.MARKET, price = 0, quantity = qty)

    /* ── price priority ─────────────────────────────────────────────── */

    @Test
    fun `buyer fills against the cheapest ask first`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5100, qty = 10, id = "expensive"))
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "cheap"))

        val result = e.submit(limit(Side.BUY, price = 5200, qty = 10))

        assertEquals(1, result.trades.size)
        assertEquals("cheap", result.trades.single().sellOrderId)
        assertEquals(5000, result.trades.single().price)
    }

    @Test
    fun `seller fills against the highest bid first`() {
        val e = MatchingEngine()
        e.submit(limit(Side.BUY, price = 4900, qty = 10, id = "low"))
        e.submit(limit(Side.BUY, price = 5000, qty = 10, id = "high"))

        val result = e.submit(limit(Side.SELL, price = 4800, qty = 10))

        assertEquals("high", result.trades.single().buyOrderId)
        assertEquals(5000, result.trades.single().price)
    }

    /* ── time priority ──────────────────────────────────────────────── */

    @Test
    fun `at the same price the earlier order fills first`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "first"))
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "second"))

        // Only enough demand for one of them.
        val result = e.submit(limit(Side.BUY, price = 5000, qty = 10))

        assertEquals("first", result.trades.single().sellOrderId)
        assertEquals(OrderStatus.FILLED, e.orderById("first")!!.status)
        assertEquals(OrderStatus.OPEN, e.orderById("second")!!.status)
    }

    @Test
    fun `a partial fill keeps its place at the front of the queue`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "first"))
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "second"))

        e.submit(limit(Side.BUY, price = 5000, qty = 4))   // first: 4 of 10
        val result = e.submit(limit(Side.BUY, price = 5000, qty = 4))   // must hit first again

        assertEquals("first", result.trades.single().sellOrderId)
        assertEquals(8, e.orderById("first")!!.filled)
        assertEquals(0, e.orderById("second")!!.filled)
    }

    /* ── execution price ────────────────────────────────────────────── */

    @Test
    fun `price improvement goes to the incoming order`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "resting"))

        // Willing to pay 5200, but the book is offering 5000.
        val result = e.submit(limit(Side.BUY, price = 5200, qty = 10))

        // Executing at 5200 would silently overcharge every taker. This is the
        // rule first attempts most often get wrong.
        assertEquals(5000, result.trades.single().price)
    }

    /* ── quantity ───────────────────────────────────────────────────── */

    @Test
    fun `a large order sweeps several price levels`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 5, id = "a"))
        e.submit(limit(Side.SELL, price = 5100, qty = 5, id = "b"))
        e.submit(limit(Side.SELL, price = 5200, qty = 5, id = "c"))

        val result = e.submit(limit(Side.BUY, price = 5200, qty = 12))

        assertEquals(listOf(5000L, 5100L, 5200L), result.trades.map { it.price })
        assertEquals(listOf(5L, 5L, 2L), result.trades.map { it.quantity })
        assertEquals(12, result.filledQuantity)
        // The last level is partly consumed; 3 must remain resting.
        assertEquals(3, e.snapshot().asks.single().quantity)
    }

    @Test
    fun `an unfilled remainder rests in the book`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 4))

        val result = e.submit(limit(Side.BUY, price = 5000, qty = 10))

        assertEquals(OrderStatus.PARTIALLY_FILLED, result.order.status)
        assertEquals(6, e.snapshot().bids.single().quantity)
    }

    @Test
    fun `no order ever fills beyond its quantity`() {
        val e = MatchingEngine()
        repeat(5) { e.submit(limit(Side.SELL, price = 5000, qty = 10)) }

        val result = e.submit(limit(Side.BUY, price = 5000, qty = 7))

        assertEquals(7, result.filledQuantity)
        assertEquals(7, result.order.filled)
        assertEquals(OrderStatus.FILLED, result.order.status)
    }

    /* ── no crossed book ────────────────────────────────────────────── */

    @Test
    fun `the best bid is never at or above the best ask`() {
        val e = MatchingEngine()
        // Deliberately submit overlapping prices in both directions.
        listOf(4900L, 5000L, 5100L).forEach { e.submit(limit(Side.BUY, it, 10)) }
        listOf(5000L, 5100L, 5200L).forEach { e.submit(limit(Side.SELL, it, 10)) }

        val book = e.snapshot()
        val bid = book.bestBid
        val ask = book.bestAsk
        if (bid != null && ask != null) {
            assertTrue(bid < ask, "book is crossed: bid $bid >= ask $ask")
            assertTrue(book.spread!! > 0)
        }
    }

    /* ── market orders ──────────────────────────────────────────────── */

    @Test
    fun `a market order takes whatever the book offers`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 5))
        e.submit(limit(Side.SELL, price = 9999, qty = 5))

        val result = e.submit(market(Side.BUY, qty = 8))

        assertEquals(8, result.filledQuantity)
        assertEquals(listOf(5000L, 9999L), result.trades.map { it.price })
    }

    @Test
    fun `a market order never rests`() {
        val e = MatchingEngine()
        val result = e.submit(market(Side.BUY, qty = 10))

        assertEquals(OrderStatus.REJECTED, result.order.status)
        assertTrue(result.trades.isEmpty())
        assertTrue(e.snapshot().bids.isEmpty(), "a market order must not join the book")
    }

    /* ── cancellation ───────────────────────────────────────────────── */

    @Test
    fun `cancelling removes remaining quantity from the book`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "doomed"))

        assertTrue(e.cancel("doomed"))
        assertEquals(OrderStatus.CANCELLED, e.orderById("doomed")!!.status)
        assertTrue(e.snapshot().asks.isEmpty())

        // Nothing left to match against.
        assertTrue(e.submit(limit(Side.BUY, price = 5000, qty = 10)).trades.isEmpty())
    }

    @Test
    fun `cancelling cannot undo a fill`() {
        val e = MatchingEngine()
        e.submit(limit(Side.SELL, price = 5000, qty = 10, id = "partly"))
        e.submit(limit(Side.BUY, price = 5000, qty = 4))

        assertTrue(e.cancel("partly"))
        // The 4 already traded belong to the buyer and stay filled.
        assertEquals(4, e.orderById("partly")!!.filled)
        assertEquals(1, e.trades().size)
    }

    @Test
    fun `cancelling an unknown or finished order is refused, not an error`() {
        val e = MatchingEngine()
        assertFalse(e.cancel("never-existed"))

        e.submit(limit(Side.SELL, price = 5000, qty = 5, id = "gone"))
        e.submit(limit(Side.BUY, price = 5000, qty = 5))
        assertFalse(e.cancel("gone"), "a filled order has nothing left to cancel")
    }

    /* ── conservation ───────────────────────────────────────────────── */

    @Test
    fun `every unit bought is a unit sold`() {
        val e = MatchingEngine()
        val rng = java.util.Random(42)

        repeat(300) {
            val side = if (rng.nextBoolean()) Side.BUY else Side.SELL
            val price = 4900L + rng.nextInt(200)
            e.submit(limit(side, price, 1L + rng.nextInt(20)))
        }

        val trades = e.trades()
        val bought = trades.groupBy { it.buyOrderId }.mapValues { it.value.sumOf { t -> t.quantity } }
        val sold = trades.groupBy { it.sellOrderId }.mapValues { it.value.sumOf { t -> t.quantity } }

        assertEquals(bought.values.sum(), sold.values.sum(), "units bought must equal units sold")

        // And no order exceeded what it asked for.
        (bought.keys + sold.keys).forEach { orderId ->
            val order = e.orderById(orderId)!!
            assertTrue(order.filled <= order.quantity, "$orderId overfilled")
        }

        // After 300 random orders the book must still not be crossed.
        val book = e.snapshot(depth = 100)
        val bid = book.bestBid
        val ask = book.bestAsk
        if (bid != null && ask != null) assertTrue(bid < ask, "crossed after random load")
    }

    /* ── validation ─────────────────────────────────────────────────── */

    @Test
    fun `an empty book has no best prices and no spread`() {
        val book = MatchingEngine().snapshot()
        assertNull(book.bestBid)
        assertNull(book.bestAsk)
        assertNull(book.spread)
    }

    @Test
    fun `nonsense orders are refused at construction`() {
        listOf(
            { limit(Side.BUY, price = 5000, qty = 0) },
            { limit(Side.BUY, price = 5000, qty = -1) },
            { limit(Side.BUY, price = 0, qty = 10) },
            { limit(Side.BUY, price = -100, qty = 10) },
        ).forEach { build ->
            try {
                build()
                throw AssertionError("expected rejection")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
