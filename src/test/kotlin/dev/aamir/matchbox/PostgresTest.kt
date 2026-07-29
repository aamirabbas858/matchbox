package dev.aamir.matchbox

import dev.aamir.matchbox.engine.MatchingEngine
import dev.aamir.matchbox.persistence.TradeRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer

/**
 * One Postgres container and one Spring context, cleaned between tests.
 *
 * Real Postgres rather than H2: an in-memory stand-in has different SQL,
 * different type coercion and different constraint behaviour, so it accepts
 * things production rejects. The CHECK constraints in V1 are exactly that.
 *
 * State is reset rather than rebuilt. @DirtiesContext would give each test a
 * fresh engine, but it tears down and recreates the whole context — which
 * re-runs Flyway and re-binds the container's connection details every time,
 * and that rebinding is fragile. Truncating one table and clearing the book is
 * both faster and less to go wrong.
 *
 * The container is started once for the run and left to Ryuk to remove.
 */
@SpringBootTest
abstract class PostgresTest {

    @Autowired lateinit var engine: MatchingEngine
    @Autowired lateinit var tradeRepository: TradeRepository

    @BeforeEach
    fun clean() {
        // The engine is a singleton bean, deliberately — two engines would
        // each be internally consistent and collectively wrong. That makes the
        // order book shared state across tests, so it has to be cleared.
        engine.reset()
        tradeRepository.deleteAll()
    }

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17-alpine").apply { start() }
    }
}
