# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache Maven dependencies before copying source (layer-cache friendly)
COPY acmq-prov-backend/pom.xml .
RUN mvn dependency:go-offline -q

COPY acmq-prov-backend/src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/mq-provisioning-service.jar app.jar

# UseContainerSupport  – JVM respects Kubernetes memory limits (default on Java 11+, explicit for clarity)
# MaxRAMPercentage     – heap may use up to 75 % of the container's memory limit
# InitialRAMPercentage – start at 50 % to avoid reserving too much upfront
# ExitOnOutOfMemoryError – crash cleanly on OOM instead of hanging
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
