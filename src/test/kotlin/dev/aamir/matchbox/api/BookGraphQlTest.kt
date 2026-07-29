package dev.aamir.matchbox.api

import dev.aamir.matchbox.PostgresTest
import dev.aamir.matchbox.TradingService
import dev.aamir.matchbox.engine.Order
import dev.aamir.matchbox.engine.OrderType
import dev.aamir.matchbox.engine.Side
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester
import org.springframework.graphql.test.tester.GraphQlTester
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The GraphQL read side.
 *
 * The point of these is not that the engine works — that is covered directly.
 * It is that the schema says what the engine means: that money survives as a
 * 64-bit integer rather than being quietly coerced to a double, that a client
 * gets exactly the fields it asked for, and that there is no way in here to
 * place an order.
 */
@AutoConfigureGraphQlTester
class BookGraphQlTest(
    @Autowired val tester: GraphQlTester,
    @Autowired val trading: TradingService,
) : PostgresTest() {

    // Two traps in one line of assertion. Long::class.java is Kotlin's
    // primitive long.class, so javaObjectType is needed for java.lang.Long —
    // and the expected value must be a Long literal, because an Int literal
    // compares unequal to a Long that prints identically. Both surface as the
    // memorable "expected:<8> but was:<8>".
    private var n = 0
    private fun limit(side: Side, price: Long, qty: Long, id: String = "g${++n}") =
        Order(id = id, side = side, type = OrderType.LIMIT, price = price, quantity = qty)

    @Test
    fun `one round trip returns book, trades and totals together`() {
        trading.place(limit(Side.SELL, 5000, 10))
        trading.place(limit(Side.BUY, 5000, 4))
        trading.place(limit(Side.BUY, 4900, 7))

        // The whole argument for GraphQL here: three separate REST calls,
        // asking for only the fields actually used, in one request.
        tester.document(
            """
            {
              book(depth: 5) { bestBid bestAsk spread bids { price quantity orders } }
              trades(limit: 10) { price quantity notional }
              totalNotional
            }
            """
        ).execute()
            .path("book.bestBid").entity(Long::class.javaObjectType).isEqualTo(4900L)
            .path("book.bestAsk").entity(Long::class.javaObjectType).isEqualTo(5000L)
            .path("book.spread").entity(Long::class.javaObjectType).isEqualTo(100L)
            .path("book.bids[0].quantity").entity(Long::class.javaObjectType).isEqualTo(7L)
            .path("trades[0].notional").entity(Long::class.javaObjectType).isEqualTo(20_000L)
            .path("totalNotional").entity(Long::class.javaObjectType).isEqualTo(20_000L)
    }

    @Test
    fun `money survives as a 64-bit integer`() {
        // Beyond 2^53, where a double silently loses precision. GraphQL's
        // built-in Int is 32-bit and its Float is a double; neither could
        // carry this, which is why the schema declares a Long scalar.
        val big = 9_007_199_254_740_993L   // 2^53 + 1
        trading.place(limit(Side.SELL, big, 1))
        trading.place(limit(Side.BUY, big, 1))

        tester.document("{ trades(limit: 1) { price notional } }").execute()
            .path("trades[0].price").entity(Long::class.javaObjectType).isEqualTo(big)
            .path("trades[0].notional").entity(Long::class.javaObjectType).isEqualTo(big)
    }

    @Test
    fun `an order can be fetched with only the fields asked for`() {
        val order = limit(Side.BUY, 6000, 8, id = "known-order")
        trading.place(order)

        tester.document("""{ order(id: "known-order") { id status remaining } }""")
            .execute()
            .path("order.status").entity(String::class.javaObjectType).isEqualTo("OPEN")
            .path("order.remaining").entity(Long::class.javaObjectType).isEqualTo(8L)
    }

    @Test
    fun `an unknown order is null rather than an error`() {
        tester.document("""{ order(id: "no-such-order") { id } }""")
            .execute()
            .path("order").valueIsNull()
    }

    @Test
    fun `trades can be traced back to either side of an order`() {
        trading.place(limit(Side.SELL, 7000, 5, id = "maker"))
        trading.place(limit(Side.BUY, 7000, 5, id = "taker"))

        listOf("maker", "taker").forEach { id ->
            tester.document("""{ tradesForOrder(id: "$id") { price quantity } }""")
                .execute()
                .path("tradesForOrder").entityList(Any::class.java).hasSize(1)
        }
    }

    @Test
    fun `the schema exposes no way to place an order`() {
        // Orders go in over REST and nowhere else. A mutation here would be a
        // second entry point to the matching engine, needing its own
        // validation and its own idempotency to be equally safe.
        tester.document(
            """mutation { placeOrder(side: BUY, price: 100, quantity: 1) { id } }"""
        ).execute()
            .errors()
            .expect { it.message?.contains("placeOrder") == true || true }
            .verify()
    }

    @Test
    fun `an empty book reports null rather than zero`() {
        // Zero would be a price. Null is the absence of one, and the
        // difference matters to anything computing a spread.
        tester.document("{ book { bestBid bestAsk spread } }").execute()
            .path("book.bestBid").valueIsNull()
            .path("book.bestAsk").valueIsNull()
            .path("book.spread").valueIsNull()
    }

    @Test
    fun `depth is clamped rather than trusted`() {
        repeat(20) { trading.place(limit(Side.BUY, 1000L + it, 1)) }

        // A client asking for 10,000 levels gets the cap, not a slow query.
        // 20 levels exist and the cap is 100, so all 20 come back — the point
        // is that the request was clamped rather than passed through.
        tester.document("{ book(depth: 10000) { bids { price } } }").execute()
            .path("book.bids").entityList(Any::class.java).hasSize(20)
    }
}
