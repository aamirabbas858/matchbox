package dev.aamir.matchbox.persistence

import dev.aamir.matchbox.engine.Trade
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

/**
 * A persisted trade.
 *
 * Deliberately a different type from [Trade]. The engine's Trade is a value in
 * a matching loop and knows nothing about databases; this one carries a
 * surrogate key and a timestamp and knows nothing about matching. Sharing one
 * class between the two would mean the engine's hot path drags JPA annotations
 * around, and any change to storage becomes a change to the engine.
 *
 * `var` rather than `val` on the id only because JPA assigns it on insert.
 * Everything else is immutable, matching the table: there is no update path.
 */
@Entity
@Table(name = "trade")
class TradeRecord(
    @Column(nullable = false, unique = true)
    val sequence: Long,

    @Column(name = "buy_order_id", nullable = false, length = 64)
    val buyOrderId: String,

    @Column(name = "sell_order_id", nullable = false, length = 64)
    val sellOrderId: String,

    /** Minor units. BIGINT in the schema - never a floating type. */
    @Column(nullable = false)
    val price: Long,

    @Column(nullable = false)
    val quantity: Long,

    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
) {
    /**
     * Derived, never stored. Persisting it would create a second source of
     * truth that could disagree with price × quantity after any migration.
     */
    val notional: Long get() = price * quantity

    companion object {
        fun from(t: Trade) = TradeRecord(
            sequence = t.sequence,
            buyOrderId = t.buyOrderId,
            sellOrderId = t.sellOrderId,
            price = t.price,
            quantity = t.quantity,
        )
    }
}

interface TradeRepository : JpaRepository<TradeRecord, Long> {

    fun findAllByOrderBySequenceDesc(): List<TradeRecord>

    /** Every trade an order took part in, on either side of it. */
    @Query("SELECT t FROM TradeRecord t WHERE t.buyOrderId = :orderId OR t.sellOrderId = :orderId ORDER BY t.sequence")
    fun findByOrderId(orderId: String): List<TradeRecord>

    /**
     * Total traded volume in minor units.
     *
     * Summed in SQL rather than by loading every row and adding in Kotlin -
     * the second approach works fine until the table is large, and then stops
     * working suddenly rather than gradually.
     */
    @Query("SELECT COALESCE(SUM(t.price * t.quantity), 0) FROM TradeRecord t")
    fun totalNotional(): Long
}
