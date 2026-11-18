package dev.aamir.matchbox.api

import dev.aamir.matchbox.TradingService
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import org.springframework.stereotype.Controller
import java.math.BigInteger
import java.util.Locale

/**
 * The read side.
 *
 * Everything here is a query and nothing is a mutation. Orders are placed over
 * REST and only over REST - a second entry point to the matching engine is one
 * more than an exchange should have, and it would need its own idempotency and
 * its own validation to be equally safe.
 *
 * What GraphQL earns its place for is reading. A client that wants the book,
 * the last few trades and one order's fills gets all three in a single round
 * trip, asking for exactly the fields it will use. The same thing over REST is
 * three requests returning three fixed shapes, most of which the caller throws
 * away.
 */
@Controller
class BookGraphQl(private val trading: TradingService) {

    @QueryMapping
    fun book(@Argument depth: Int): BookResponse =
        BookResponse.from(trading.book(depth.coerceIn(1, 100)))

    @QueryMapping
    fun trades(@Argument limit: Int): List<TradeResponse> =
        trading.recentTrades(limit.coerceIn(1, 500)).map(TradeResponse::from)

    @QueryMapping
    fun order(@Argument id: String): OrderResponse? =
        trading.orderById(id)?.let(OrderResponse::from)

    @QueryMapping
    fun tradesForOrder(@Argument id: String): List<TradeResponse> =
        trading.tradesForOrder(id).map(TradeResponse::from)

    @QueryMapping
    fun totalNotional(): Long = trading.totalNotional()
}

/**
 * A 64-bit integer scalar.
 *
 * GraphQL's built-in Int is 32-bit and its Float is an IEEE-754 double.
 * Neither can carry money: Int overflows at about 21 million euros of notional
 * expressed in cents, and Float cannot represent 0.1 exactly, which is the
 * whole reason prices are minor units in the first place.
 *
 * Serialised as a JSON number, but parsed from a string too - JavaScript
 * clients lose precision above 2^53 and send large values as strings to avoid
 * it.
 */
@Configuration
class GraphQlScalars {

    private val longScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("Long")
        .description("A 64-bit integer. Money is minor units, never a float.")
        .coercing(object : Coercing<Long, Long> {

            override fun serialize(
                dataFetcherResult: Any,
                graphQLContext: GraphQLContext,
                locale: Locale,
            ): Long = when (dataFetcherResult) {
                is Long -> dataFetcherResult
                is Int -> dataFetcherResult.toLong()
                is BigInteger -> dataFetcherResult.toLong()
                else -> throw IllegalArgumentException("not a Long")
            }

            override fun parseValue(
                input: Any,
                graphQLContext: GraphQLContext,
                locale: Locale,
            ): Long = when (input) {
                is Long -> input
                is Int -> input.toLong()
                is BigInteger -> input.toLong()
                // Accepted as a string because JavaScript clients lose
                // precision above 2^53 and send large integers as text.
                is String -> input.toLongOrNull()
                    ?: throw IllegalArgumentException("not a Long")
                else -> throw IllegalArgumentException("not a Long")
            }

            override fun parseLiteral(
                input: Value<*>,
                variables: CoercedVariables,
                graphQLContext: GraphQLContext,
                locale: Locale,
            ): Long = when (input) {
                is IntValue -> input.value.toLong()
                is StringValue -> input.value.toLongOrNull()
                    ?: throw IllegalArgumentException("not a Long")
                else -> throw IllegalArgumentException("not a Long literal")
            }
        })
        .build()

    @Bean
    fun longScalarConfigurer() = RuntimeWiringConfigurer { it.scalar(longScalar) }
}
