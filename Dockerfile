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

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
