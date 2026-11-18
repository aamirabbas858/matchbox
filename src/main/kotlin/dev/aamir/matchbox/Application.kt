package dev.aamir.matchbox

import dev.aamir.matchbox.engine.MatchingEngine
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean

@SpringBootApplication
class Application {

    /**
     * One engine for the whole application.
     *
     * The book lives in memory rather than in the database, which is how real
     * matching engines are built: matching has to be fast and deterministic,
     * and a round trip to Postgres per price level is neither. Trades are
     * persisted because they are the permanent record; the book is derived
     * state that can be rebuilt from open orders.
     *
     * A singleton is also what makes the engine's own synchronization
     * meaningful. Two engine instances would each be internally consistent and
     * collectively wrong.
     */
    @Bean
    fun matchingEngine(): MatchingEngine = MatchingEngine()

    /**
     * Validation messages without Jakarta EL.
     *
     * Hibernate Validator interpolates constraint messages through an EL
     * expression engine by default, which fails outright under Gradle's test
     * worker classloader - "HV000149: An exception occurred during message
     * interpolation" - turning every 400 into a 500 in tests while working
     * fine when run normally. A validation layer that behaves differently
     * under test than in production is worse than no validation layer.
     *
     * ParameterMessageInterpolator substitutes {min}, {value} and the like
     * without evaluating expressions. It is also the safer default: EL
     * interpolation evaluates whatever ends up inside a constraint message,
     * and CVE-2020-10693 exists because that can include user input.
     */
    @Bean
    fun validator(): LocalValidatorFactoryBean = LocalValidatorFactoryBean().apply {
        messageInterpolator = ParameterMessageInterpolator()
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
