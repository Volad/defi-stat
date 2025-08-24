# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build

# Set workdir
WORKDIR /app

# Leverage Docker layer caching: copy only gradle files first
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle ./
COPY build.gradle ./
# If you have additional gradle files (libs), copy them too:
# COPY gradle.properties ./

# Pre-fetch dependencies
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Copy sources last (to avoid busting cache on every code change in deps)
COPY src ./src

# Build fat jar
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: use a non-root user
RUN addgroup -S app && adduser -S app -G app
USER app

WORKDIR /app

# Copy the built jar
COPY --from=build /app/build/libs/*.jar /app/app.jar

# Default port (override at run if needed)
EXPOSE 8080

# Tune JVM via JAVA_OPTS if needed
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
ENV SPRING_PROFILES_ACTIVE="prod"

# Health-friendly start
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -jar /app/app.jar" ]