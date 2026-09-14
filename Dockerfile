# ─── Stage 1: build ────────────────────────────────────────────────────────
# eclipse-temurin:17-jdk (not 21 -- pom.xml pins java.version/maven-compiler-plugin
# source/target to 17; the repo's own CI workflow (.github/workflows/test.yml) already
# builds and tests against Temurin 17, confirmed by reading it, not assumed).
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY . .

RUN chmod +x mvnw
RUN ./mvnw clean package -DskipTests

# ─── Stage 2: runtime ──────────────────────────────────────────────────────
# JRE only, not the full JDK the build stage needed -- the compiled jar is the only
# thing that has to survive into the runtime image.
FROM eclipse-temurin:17-jre AS runtime

# curl for the HEALTHCHECK below -- eclipse-temurin's JRE base doesn't ship it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root: a container running as root is a real, avoidable privilege-escalation
# surface if the JVM or a dependency is ever compromised.
RUN groupadd --system spring && useradd --system --gid spring spring

WORKDIR /app

# pom.xml's artifactId/version (fieldops/1.0.0) with no <finalName> override, so Maven's
# default jar name is fieldops-1.0.0.jar -- confirmed by reading pom.xml, not assumed.
# The wildcard still guards against that version drifting without this file needing
# a matching edit.
COPY --from=build /app/target/*.jar app.jar

RUN chown spring:spring app.jar
USER spring

EXPOSE 8080

# spring-boot-starter-actuator is already a real dependency (pom.xml), so
# /actuator/health is genuinely live -- not a hopeful guess at an endpoint that
# might not exist. --fail so a non-2xx response marks the container unhealthy
# rather than curl's own exit 0 on a 4xx/5xx.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail http://localhost:8080/actuator/health || exit 1

CMD ["java", "-jar", "app.jar"]
