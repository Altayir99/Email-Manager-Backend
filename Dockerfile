# ── Build stage ───────────────────────────────────────────────────────────────
# Use the official Maven+Java 21 image — no apt-get needed, avoids JDK mismatch
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Cache dependencies first (layer reuse on source-only changes)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Runtime stage ──────────────────────────────────────────────────────────────
# Slim JRE-only image for production
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/email-manager-backend-1.2.1.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=optional:file:/app/firebase-service-account.json"]
