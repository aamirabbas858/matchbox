package dev.aamir.matchbox

import dev.aamir.matchbox.engine.MatchResult
import dev.aamir.matchbox.engine.MatchingEngine
import dev.aamir.matchbox.engine.Order
import dev.aamir.matchbox.persistence.TradeRecord
import dev.aamir.matchbox.persistence.TradeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Matching, then recording.
 *
 * This layer exists so the engine does not have to know that a database
 * exists. [MatchingEngine] is a pure in-memory data structure - no I/O, no
 * Spring, no annotations - which is what makes its 17 tests fast and its
 * behaviour reproducible. Persistence is a concern of the layer above, and
 * this is that layer.
 *
 * --- the ordering, and what it costs ---
 *
 * Matching happens first and is not part of the transaction. That is
 * deliberate but it is a trade-off worth stating plainly: if the insert fails
 * after a match succeeds, the book has moved and the trade is not on disk.
 *
 * The alternative - holding a database transaction open across matching -
 * would serialise every order behind a round trip to Postgres and make
 * throughput a function of database latency, which for a matching engine is
 * the wrong thing to be slow at. Real exchanges resolve this with a durable
 * sequenced log the engine writes to before acknowledging. That is the correct
 * answer and it is out of scope here; what is in scope is not pretending the
 * problem does not exist.
 */
@Service
class TradingService(
    private val engine: MatchingEngine,
    private val trades: TradeRepository,
) {

    @Transactional
    fun place(order: Order): MatchResult {
        val result = engine.submit(order)

        if (result.trades.isNotEmpty()) {
            // saveAll in one batch: a sweep across five price levels is five
            // trades, and five separate round trips for one order is five
            // times the latency for no benefit.
            trades.saveAll(result.trades.map(TradeRecord::from))
        }

        return result
    }

    fun cancel(orderId: String): Boolean = engine.cancel(orderId)

    fun orderById(orderId: String) = engine.orderById(orderId)

    fun book(depth: Int) = engine.snapshot(depth)

    /**
     * Recent trades, from the database rather than the engine's own list.
     *
     * The engine keeps trades in memory for its tests; the database is the
     * record. Reading from the engine here would mean a restart silently
     * erased history, and nobody would notice until they looked.
     */
    @Transactional(readOnly = true)
    fun recentTrades(limit: Int): List<TradeRecord> =
        trades.findAllByOrderBySequenceDesc().take(limit)

    @Transactional(readOnly = true)
    fun tradesForOrder(orderId: String): List<TradeRecord> = trades.findByOrderId(orderId)

    @Transactional(readOnly = true)
    fun totalNotional(): Long = trades.totalNotional()
}
