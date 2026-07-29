plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    // Hibernate instantiates entities reflectively and needs a no-arg
    // constructor, which Kotlin classes do not have. This plugin synthesises
    // one for @Entity classes at compile time, so the entity can keep its
    // immutable vals rather than being rewritten as nullable vars with
    // defaults purely to satisfy the ORM.
    kotlin("plugin.jpa") version "2.1.0"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.aamir"
version = "0.1.0"

// Spring Boot 3.4's BOM pins Testcontainers 1.20.4, which speaks a Docker API
// version that Docker 29 answers with HTTP 400 and an empty payload — so every
// container-backed test fails with "Could not find a valid Docker environment"
// on a machine where `docker ps` works perfectly. Overriding the BOM is the
// fix; the alternative is pinning Docker, which is the wrong thing to pin.
extra["testcontainers.version"] = "1.21.3"

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    // Health endpoint, used by the container healthcheck.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.graphql:spring-graphql-test")
    // Real Postgres in tests rather than H2. An in-memory stand-in has
    // different SQL, different types and different constraint behaviour, so it
    // passes for things production would reject.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // -Xjsr305=strict makes Spring's @Nullable annotations bind to Kotlin's
        // null safety, so a Java API that can return null is a Kotlin type that
        // admits null rather than one that lies about it.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }

    // Docker 29 refuses API versions below 1.40 (MinAPIVersion), and the
    // docker-java client Testcontainers uses still negotiates 1.32 by default.
    // The daemon answers that with HTTP 400 and an empty body, which surfaces
    // as "Could not find a valid Docker environment" — a message that sends you
    // looking at sockets and permissions on a machine where `docker ps` works.
    //
    //   /v1.32/info -> 400
    //   /v1.41/info -> 200
    //
    // Pinning the API version is the fix. 1.45 sits well inside the daemon's
    // supported 1.40-1.55 range and does not need bumping every Docker release.
    environment("DOCKER_API_VERSION", "1.45")
    // docker-java reads the negotiated version from this system property too;
    // the environment variable alone is not honoured by every strategy.
    systemProperty("api.version", "1.45")
}
