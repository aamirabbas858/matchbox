# Multi-stage: the image that builds the application is not the image that runs
# it. A JDK, Gradle, the Gradle cache and the source tree together come to over
# a gigabyte, and none of it is needed to run a jar. Shipping it would mean
# shipping a compiler to production, which is both weight and attack surface.

# ─── build ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# The wrapper and dependency declarations are copied first, on their own.
# Docker caches each layer, and this one only changes when a dependency
# changes — so editing a Kotlin file re-runs the build without re-downloading
# Spring Boot. Copying everything at once would invalidate that on every edit.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet || true

COPY src ./src

# -x test because CI runs the tests. Running them here would need a Postgres
# container inside the build, and a green build that never ran the tests is
# more honest than one that skipped them silently.
RUN ./gradlew bootJar --no-daemon -x test

# ─── run ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# A non-root user. A container process that is root is root on the host kernel
# if anything escapes, and nothing here needs the privilege.
RUN addgroup -S matchbox && adduser -S -G matchbox matchbox

WORKDIR /app
COPY --from=build --chown=matchbox:matchbox /build/build/libs/*.jar app.jar

USER matchbox
EXPOSE 8080

# Container memory is not the JVM's default idea of available memory. Without
# this the heap is sized from the host's RAM and the kernel kills the process
# when it grows past the container limit — which looks like a crash with no
# stack trace and no explanation.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# Docker's own healthcheck rather than an orchestrator's, so `docker ps` alone
# tells the truth about whether the application is actually serving.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# exec form, so the JVM is PID 1 and receives SIGTERM directly. Through a shell
# it would not, and every stop would be a ten-second wait followed by SIGKILL.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
