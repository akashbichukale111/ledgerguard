# Build stage. The base image must carry Maven — an earlier revision of this file ran `mvn`
# inside a plain JDK image, where it does not exist, so the build could never have succeeded.
# The whole reactor is copied because `-am` needs the sibling library modules on disk.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY libs libs
COPY services services

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/transaction-service -am package -DskipTests -Dspotless.check.skip=true

# Runtime stage: JRE only, so the compiler and build tooling are not shipped.
FROM eclipse-temurin:21-jre-alpine

# wget backs the HEALTHCHECK below; busybox provides it on Alpine, but be explicit.
RUN apk add --no-cache wget \
    && addgroup -g 1000 appuser \
    && adduser -D -u 1000 -G appuser appuser

WORKDIR /app
COPY --from=build --chown=appuser:appuser /build/services/transaction-service/target/transaction-service-*.jar app.jar

# Read-only jar, directory not writable by the runtime user.
RUN chmod 440 app.jar && chmod 550 /app

USER appuser
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1

# MaxRAMPercentage reads the container memory limit rather than the host's, so the heap
# stays inside the cgroup budget.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", \
            "-Dspring.profiles.active=docker", "-jar", "app.jar"]
