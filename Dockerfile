# syntax=docker/dockerfile:1

# Use the Eclipse Temurin JDK for building the application
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Copy the gradle wrapper and gradle configuration files
COPY gradlew .
COPY gradle gradle
RUN chmod +x gradlew

COPY build.gradle settings.gradle ./
COPY src src

RUN ./gradlew build -x test --no-daemon

# Test stage
FROM build AS test
WORKDIR /app
# Run tests
RUN ./gradlew test

# Use the Eclipse Temurin JRE for running the application
FROM eclipse-temurin:21-jre-jammy AS final

# Create a non-privileged user that the app will run under
ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    appuser
USER appuser

COPY --from=build /app/build/libs/*.jar app.jar

# Only expose port 8080 for production
# For debugging, set DEBUG=true environment variable and map port 5005
EXPOSE 8080

# Use a shell script to conditionally enable debug mode
COPY docker-entrypoint.sh /docker-entrypoint.sh
USER root
RUN chmod +x /docker-entrypoint.sh
USER appuser

ENTRYPOINT ["/docker-entrypoint.sh"]

