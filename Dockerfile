# Dockerfile -- fieldops (Spring Boot 3.2.3, lk.slt:fieldops:1.0.0)
#
# Replaces a prior single-stage Dockerfile that shipped the full JDK 21 toolchain in the final
# image, defaulted to port 10000 via a $PORT env var, and expected `sh -c "java -jar
# target/*.jar --server.port=${PORT:-10000}"` -- all of that matched a different deployment
# target (Render's own $PORT convention, via the now-deleted .github/workflows/ci.yml's
# RENDER_DEPLOY_HOOK_BACKEND step), not this project's real, established config: pom.xml pins
# <java.version>17</java.version> (confirmed directly, not 21), and application.yml's own
# `server.port: ${SERVER_PORT:8080}` already defaults to 8080 -- so no Dockerfile-level port
# substitution is needed at all; Spring Boot's own config handles a SERVER_PORT override if one
# is ever set.
#
# Multi-stage: Stage 1 builds the jar with the full JDK; Stage 2 runs it on a JRE-only base, so
# the shipped image doesn't carry the Maven/JDK build toolchain it no longer needs at runtime.

# ─── Stage 1: Builder ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Copy the wrapper and pom first -- maximises Docker layer cache on dependency-only changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw

# Warm the local Maven repo from pom.xml alone, before the source changes on every build.
RUN ./mvnw -B dependency:go-offline

COPY src src

RUN ./mvnw -B clean package -DskipTests

# ─── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre AS runtime

LABEL description="SLT Field Ops backend -- Spring Boot REST API + WebSocket"

# Non-root user, matching slt-ai-module's Dockerfile's own established convention for the
# sibling service in the same docker-compose stack.
RUN groupadd -r fieldops && useradd -r -g fieldops -d /app -s /sbin/nologin fieldops

WORKDIR /app

# target/*.jar rather than a hardcoded fieldops-1.0.0.jar -- matches the actual Spring Boot
# repackage output (the one real executable fat jar; version bumps in pom.xml need no Dockerfile
# change) without also picking up the plugin's renamed *.jar.original.
COPY --from=builder --chown=fieldops:fieldops /app/target/*.jar app.jar

RUN mkdir -p /app/logs /app/uploads && chown -R fieldops:fieldops /app/logs /app/uploads

USER fieldops

# Real, established port (application.yml: server.port: ${SERVER_PORT:8080}) -- not the prior
# Dockerfile's Render-oriented 10000.
EXPOSE 8080

# curl is present on eclipse-temurin's Ubuntu-based JRE image by default (Actuator's own real,
# already-relied-on health endpoint -- the same one docker-compose.yml's springboot service
# already healthchecks and frontend-admin/slt-mobile-app's live-backend CI workflows already
# poll during Testcontainers-backed run-mode startup).
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# No shell/${PORT} substitution -- SERVER_PORT (if ever overridden) is handled entirely by
# application.yml's own ${SERVER_PORT:8080}, not duplicated here.
ENTRYPOINT ["java", "-jar", "app.jar"]
