# Build stage. Maven must be in the base image — see the sibling services for the note on why.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY libs libs
COPY services services

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/ledgerguard-gateway -am package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget \
    && addgroup -g 1000 appuser \
    && adduser -D -u 1000 -G appuser appuser

WORKDIR /app
COPY --from=build --chown=appuser:appuser /build/services/ledgerguard-gateway/target/ledgerguard-gateway-*.jar app.jar

RUN chmod 440 app.jar && chmod 550 /app

USER appuser
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", \
            "-Dspring.profiles.active=docker", "-jar", "app.jar"]
