package dev.aamir.matchbox.api

import dev.aamir.matchbox.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * The HTTP layer over the engine.
 *
 * The engine's own rules are tested directly in MatchingEngineTest and are not
 * re-tested here. What these cover is the part the engine cannot: that a
 * request is translated faithfully, that failures come back as status codes a
 * client can branch on, and that nothing internal leaks into a response body.
 *
 * Each test gets a fresh context, and therefore a fresh book.
 *
 * The engine is a singleton bean - deliberately, since two engines would each
 * be internally consistent and collectively wrong. That makes the order book
 * shared mutable state across tests: a bid left resting by one test crosses
 * the sell another test expected to rest, and both fail for reasons that have
 * nothing to do with what they are checking. Rebuilding the context is the
 * blunt fix, and on an application this size it costs about a second.
 */
@AutoConfigureMockMvc
class OrderControllerTest(@Autowired val mvc: MockMvc) : PostgresTest() {

    private fun placeJson(side: String, price: Long, qty: Long) =
        """{"side":"$side","type":"LIMIT","price":$price,"quantity":$qty}"""

    private fun place(side: String, price: Long, qty: Long) =
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(placeJson(side, price, qty)))

    @Test
    fun `placing an order returns 201 and the resting order`() {
        place("BUY", 5000, 10)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.order.status").value("OPEN"))
            .andExpect(jsonPath("$.order.remaining").value(10))
            .andExpect(jsonPath("$.filledQuantity").value(0))
    }

    @Test
    fun `a crossing order returns its trades`() {
        place("SELL", 6000, 5).andExpect(status().isCreated)
        place("BUY", 6000, 5)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.order.status").value("FILLED"))
            .andExpect(jsonPath("$.trades[0].price").value(6000))
            .andExpect(jsonPath("$.trades[0].notional").value(30000))
    }

    @Test
    fun `the book reports both sides and the spread`() {
        place("BUY", 7000, 3)
        place("SELL", 7100, 3)

        mvc.perform(get("/api/v1/book"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bestBid").value(7000))
            .andExpect(jsonPath("$.bestAsk").value(7100))
            .andExpect(jsonPath("$.spread").value(100))
    }

    @Test
    fun `a rejected order returns 400, not 500`() {
        // Zero quantity fails bean validation before the engine ever sees it.
        mvc.perform(
            post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("""{"side":"BUY","type":"LIMIT","price":5000,"quantity":0}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid request"))
    }

    @Test
    fun `an unknown side is a 400 with a usable message`() {
        mvc.perform(
            post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("""{"side":"SIDEWAYS","type":"LIMIT","price":5000,"quantity":1}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("malformed request"))
    }

    @Test
    fun `cancelling distinguishes never existed from too late`() {
        mvc.perform(delete("/api/v1/orders/does-not-exist")).andExpect(status().isNotFound)

        val body = place("SELL", 8000, 2).andReturn().response.contentAsString
        val id = Regex(""""id":"([^"]+)"""").find(body)!!.groupValues[1]

        // Fill it, so the order exists but has nothing left to cancel.
        place("BUY", 8000, 2)

        mvc.perform(delete("/api/v1/orders/$id"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("not cancellable"))
    }

    @Test
    fun `an error response carries no internal detail`() {
        val body = mvc.perform(
            post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("""{"side":"BUY","type":"LIMIT","price":5000,"quantity":-5}""")
        ).andExpect(status().isBadRequest).andReturn().response.contentAsString

        // No package names, no class names, no stack frames.
        listOf("dev.aamir", "java.lang", "Exception", "at ").forEach {
            check(!body.contains(it)) { "error body leaked internals: $body" }
        }
    }
}
