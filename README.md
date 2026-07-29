# matchbox

[![CI](https://github.com/aamirabbas858/matchbox/actions/workflows/ci.yml/badge.svg)](https://github.com/aamirabbas858/matchbox/actions/workflows/ci.yml)

**A limit order book with price-time priority.** Kotlin, Spring Boot, Postgres,
Docker.

The core of an exchange, small enough to read in one sitting and strict enough
to be worth reading. 39 tests, each one a rule the exchange has to keep rather
than a restatement of the code.

---

## What an order book is

Everyone who wants to trade sends an **order**:

- *"I'll buy 100 at up to 50.00"* — a **bid**
- *"I'll sell 100 at 51.00 or more"* — an **ask**

They rest in a book, sorted. When a new order overlaps a resting one, they
**match** and a trade happens.

```
        ASKS (sellers)
   5100   x  200
   5050   x  100   <- best ask
   ------------------
   5000   x  150   <- best bid
   4950   x  300
        BIDS (buyers)
```

Two rules decide everything:

**PRICE PRIORITY** — the best price fills first. Cheapest ask, highest bid.

**TIME PRIORITY** — among orders at the *same* price, the one that arrived
first fills first.

The second rule is the fairness guarantee an exchange exists to provide, and it
**fails silently**. A book that fills the wrong order still returns plausible
trades at plausible prices. Nobody notices until the trader who queued first
asks why they were skipped.

## Decisions worth defending

### Both rules fall out of the data structure

```kotlin
private val bids = TreeMap<Long, ArrayDeque<Order>>(reverseOrder())
private val asks = TreeMap<Long, ArrayDeque<Order>>()
```

A sorted map keyed by price gives price ordering. A deque at each price gives
arrival ordering. Nothing in the matching loop has to remember to sort, so
neither rule can be forgotten by a later change.

The hard case is a **partial fill**: an order filled 4 of 10 must keep its place
at the *front* of the queue, not go to the back. There is a test for exactly
that, because it is the one an implementation gets wrong.

### Trades execute at the resting order's price

Bid 5200 into an ask of 5000 and the trade happens at **5000**. The price
improvement goes to the incoming order; the resting order gets exactly what it
asked for.

This is the standard rule and the one first attempts most often get wrong —
executing at the incoming price silently overcharges every taker, and every
number still looks reasonable.

### Time priority uses a sequence number, not a clock

Two orders can share a millisecond. They cannot share a sequence number.

Ordering by wall-clock time would make the outcome depend on how fast the
machine is, which is the one property an exchange must not have.

### Money is never a floating-point number

`0.1 + 0.2` is `0.30000000000000004`. Every price, quantity and total in this
codebase is a `Long` count of minor units — cents, not euros — from the domain
model through the JSON, the GraphQL schema and the `BIGINT` columns.

The API does not accept decimals at all. `"50.50"` would have to be parsed into
a float somewhere.

### The book is in memory; trades are in Postgres

Matching must be fast and deterministic, so the book is a data structure, not a
table. Trades are the permanent record, so they are persisted.

`MatchingEngine` has no I/O, no Spring and no annotations. `TradingService`
sits above it and does the persisting, which is what keeps the engine's 17
tests fast and its behaviour reproducible.

**The trade-off is stated rather than hidden.** Matching happens outside the
transaction: if the insert fails after a match succeeds, the book has moved and
the trade is not on disk. Holding a transaction open across matching would make
throughput a function of database latency, which for a matching engine is the
wrong thing to be slow at. Real exchanges use a durable sequenced log — the
correct answer, out of scope here, and worth naming rather than pretending
away.

### Matching is single-threaded on purpose

Two orders arriving at once must produce one deterministic outcome. Serialising
the matching loop guarantees it. Real engines do the same with more machinery;
the property is identical.

### GraphQL reads, REST writes

Orders go in over REST and nowhere else. A mutation would be a second entry
point to the thing that decides who gets filled, needing its own validation and
idempotency to be equally safe. **A test asserts the schema rejects a
mutation.**

What GraphQL earns its place for is the read side — the book, recent trades and
one order's fills in a single round trip, asking for exactly the fields used:

```graphql
{
  book(depth: 5) { bestBid bestAsk spread bids { price quantity orders } }
  trades(limit: 10) { price quantity notional }
  totalNotional
}
```

The schema declares its own `Long` scalar. GraphQL's built-in `Int` is 32-bit
and overflows at roughly 21 million euros of notional in cents; its `Float` is
an IEEE-754 double. A test places an order at **2^53 + 1** and reads it back
intact.

## Running it

Everything, from a machine with only Docker:

```bash
docker compose up --build
```

Then:

```bash
# a resting sell
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"side":"SELL","type":"LIMIT","price":5000,"quantity":10}'

# a buy willing to pay more — fills at 5000, not 5200
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"side":"BUY","type":"LIMIT","price":5200,"quantity":10}'

curl localhost:8080/api/v1/book
```

GraphiQL is at `/graphiql` when `GRAPHIQL_ENABLED=true`, which compose sets.
It is off by default, because an interactive schema explorer is not something
to leave reachable in production.

Locally, without Docker for the app:

```bash
./gradlew test        # Testcontainers starts its own Postgres
./gradlew bootRun
```

## API

| | |
|---|---|
| `POST /api/v1/orders` | place a limit or market order |
| `DELETE /api/v1/orders/{id}` | cancel remaining quantity |
| `GET /api/v1/orders/{id}` | order state |
| `GET /api/v1/orders/{id}/trades` | that order's fills |
| `GET /api/v1/book?depth=10` | current depth |
| `GET /api/v1/trades?limit=50` | recent trades |
| `POST /graphql` | read-only queries |
| `GET /actuator/health` | health, and nothing else |

Cancel answers **409** when the order exists but is already filled or
cancelled, and **404** only when it never existed. The distinction is what a
client needs to decide whether to retry: a bug on their side, or a race they
lost.

## Tests

```
17  matching engine    price priority, time priority, price improvement,
                       partial fills, sweeps, market orders, cancellation,
                       and 300 random orders asserting units bought equal
                       units sold and the book is never crossed
 7  REST               status codes a client can branch on, and that no
                       internal detail reaches an error body
 7  persistence        against real Postgres via Testcontainers, including
                       the CHECK constraints
 8  GraphQL            64-bit money survives, no mutation exists
```

Real Postgres rather than H2. An in-memory stand-in has different SQL, type
coercion and constraint behaviour, so it accepts things production rejects —
and those failures then arrive in production, which is where they are
expensive.

## Stack

Kotlin 2.1 · Spring Boot 3.4 · Spring GraphQL · Postgres 17 · Flyway ·
Hibernate · Testcontainers · JUnit 5 · Gradle · Docker · GitHub Actions

## What is deliberately missing

- **No authentication.** Every caller is trusted, which no exchange can be.
- **No idempotency on order placement.** A retried POST places a second order.
  Real venues require a client order id for exactly this reason.
- **No durable sequenced log**, so a crash between matching and insert loses a
  trade. See above.
- **No self-trade prevention** between orders from the same participant —
  there are no participants yet.
- **No order types beyond LIMIT and MARKET.** No stop, no iceberg, no
  time-in-force.
- **One instrument.** A real book is one per symbol.

## Notes

Four failures on the way were environmental rather than logical, and are
recorded in the git history because they cost real time:

**Docker 29 refuses API versions below 1.40** and docker-java still negotiates
1.32, so every Testcontainers probe came back `HTTP 400` with an empty body.
That surfaces as "Could not find a valid Docker environment" on a machine where
`docker ps` works perfectly. Probing the daemon settled it in one command:
`/v1.32/info` returns 400, `/v1.41/info` returns 200.

**Hibernate Validator's EL message interpolation** fails under Gradle's test
worker classloader, turning every `400` into a `500` in tests while the running
application answered correctly. A validation layer that behaves differently
under test than in production is worse than none.

**Kotlin classes have no no-arg constructor** and Hibernate instantiates
entities reflectively.

**`Long::class.java` is Kotlin's primitive `long.class`**, and an `Int` literal
compares unequal to a `Long` that prints identically — `expected:<8> but
was:<8>`.

---

Built by [Abbas Aamir](https://www.linkedin.com/in/abbas-aamir-474969353/) —
CS undergraduate in Berlin.
