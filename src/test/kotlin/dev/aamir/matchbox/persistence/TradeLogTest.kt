package dev.aamir.matchbox.persistence

import dev.aamir.matchbox.TradingService
import dev.aamir.matchbox.engine.Order
import dev.aamir.matchbox.engine.OrderType
import dev.aamir.matchbox.engine.Side
import dev.aamir.matchbox.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trade log, against real Postgres.
 *
 * Testcontainers rather than H2. An in-memory database has different SQL,
 * different type coercion and different constraint behaviour, so it accepts
 * things production rejects - and the failures then arrive in production,
 * which is the one place they are expensive. The CHECK constraints in V1 are
 * exactly the kind of thing H2 would handle differently.
 *
 * Flyway runs against this container the same way it will against a real
 * database, so the migration itself is under test too.
 */
class TradeLogTest(
    @Autowired val trading: TradingService,
    @Autowired val trades: TradeRepository,
) : PostgresTest() {

    private var n = 0
    private fun limit(side: Side, price: Long, qty: Long) = Order(
        id = "t${++n}-${System.nanoTime()}",
        side = side,
        type = OrderType.LIMIT,
        price = price,
        quantity = qty,
    )

    @Test
    fun `a match is written to the log`() {
        trading.place(limit(Side.SELL, 5000, 10))
        val result = trading.place(limit(Side.BUY, 5000, 10))

        assertEquals(1, result.trades.size)

        val logged = trades.findAll()
        assertEquals(1, logged.size)
        assertEquals(5000, logged.single().price)
        assertEquals(10, logged.single().quantity)
        assertEquals(50_000, logged.single().notional)
    }

    @Test
    fun `an order that rests writes nothing`() {
        trading.place(limit(Side.BUY, 4000, 5))
        assertTrue(trades.findAll().isEmpty(), "a resting order is not a trade")
    }

    @Test
    fun `a sweep across price levels writes one row per fill`() {
        trading.place(limit(Side.SELL, 5000, 3))
        trading.place(limit(Side.SELL, 5100, 3))
        trading.place(limit(Side.SELL, 5200, 3))

        trading.place(limit(Side.BUY, 5200, 9))

        val logged = trades.findAll().sortedBy { it.sequence }
        assertEquals(3, logged.size)
        assertEquals(listOf(5000L, 5100L, 5200L), logged.map { it.price })
        // 3×5000 + 3×5100 + 3×5200
        assertEquals(45_900, trades.totalNotional())
    }

    @Test
    fun `trades can be found by either side of the order`() {
        val sell = limit(Side.SELL, 6000, 4)
        trading.place(sell)
        val buy = limit(Side.BUY, 6000, 4)
        trading.place(buy)

        assertEquals(1, trades.findByOrderId(sell.id).size)
        assertEquals(1, trades.findByOrderId(buy.id).size)
        assertTrue(trades.findByOrderId("never-traded").isEmpty())
    }

    @Test
    fun `the sequence is unique across every trade`() {
        repeat(5) { trading.place(limit(Side.SELL, 7000, 2)) }
        trading.place(limit(Side.BUY, 7000, 10))

        val sequences = trades.findAll().map { it.sequence }
        assertEquals(5, sequences.size)
        assertEquals(sequences.size, sequences.toSet().size, "sequence must be unique")
    }

    @Test
    fun `the database refuses a trade between an order and itself`() {
        // The engine can never produce this - a resting order cannot cross
        // itself. The constraint exists so a future code path cannot either.
        val selfTrade = TradeRecord(
            sequence = 999_999,
            buyOrderId = "same-order",
            sellOrderId = "same-order",
            price = 100,
            quantity = 1,
        )
        try {
            trades.saveAndFlush(selfTrade)
            throw AssertionError("no_self_trade constraint did not fire")
        } catch (e: Exception) {
            assertTrue(
                generateSequence(e as Throwable) { it.cause }
                    .any { it.message?.contains("no_self_trade") == true },
                "expected the no_self_trade constraint, got: ${e.message}",
            )
        }
    }

    @Test
    fun `the database refuses a non-positive price`() {
        try {
            trades.saveAndFlush(
                TradeRecord(sequence = 888_888, buyOrderId = "b", sellOrderId = "s", price = 0, quantity = 1)
            )
            throw AssertionError("price CHECK did not fire")
        } catch (e: Exception) {
            assertTrue(
                generateSequence(e as Throwable) { it.cause }.any { it.message?.contains("trade_price_check") == true },
                "expected the price check, got: ${e.message}",
            )
        }
    }
}
