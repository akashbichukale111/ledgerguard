# Multi-stage build: compile and package in build stage, run in minimal runtime stage
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Copy Maven POM files
COPY pom.xml .
COPY libs libs/
COPY services/transaction-service services/transaction-service/

# Build the application
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -f services/transaction-service/pom.xml -DskipTests -q

# Runtime stage: minimal image
FROM eclipse-temurin:21-jre-alpine

# Security: Create unprivileged user for application
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser

WORKDIR /app

# Copy JAR from build stage
COPY --from=build --chown=appuser:appuser /build/services/transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar app.jar

# Security: Set restrictive file permissions
RUN chmod 500 app.jar && \
    chmod 700 /app

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1

# Security: Drop capabilities, run as unprivileged user
USER appuser

# Expose port (documentation only, doesn't open port)
EXPOSE 8080

# Run application
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-Dspring.profiles.active=docker", "-jar", "app.jar"]
