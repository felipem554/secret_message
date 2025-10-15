Self Destructing Secret Message Service
Overview

This project implements a self-destructing secret message service. The service allows you to store secret messages that will self-destruct after being fetched. The service is built using Docker, Spring Boot, Redis, and NATS for communication.
Features

    Secure Message Storage: Encrypts and stores messages securely.
    Auto-Generated Passwords: Generates secure passwords automatically for decrypting messages.
    Self-Destruct: Messages are deleted after being fetched or after three failed attempts to fetch them.
    Redis Integration: Uses Redis for managing message expiry.
    NATS Messaging: Utilizes NATS for messaging.
    Docker: Runs the entire service in Docker containers for easy deployment.

Prerequisites

    Docker
    Docker Compose

Project Structure

    compose.yml: Docker Compose configuration file.
    Dockerfile: Dockerfile for building the application container.
    src/: Source code of the Spring Boot application.
    build.gradle and settings.gradle: Gradle build configuration files.

Setup and Running the Application
Step 1: Build the Docker Images

Build the Docker images using the following command:

sh

docker compose build

Step 2: Start the Services

Start the Docker services using the following command:

sh

docker compose up

This command will start the following services:

    Redis: Running on port 6379.
    App: Spring Boot application running on port 8080.
    NATS: NATS server running on port 4222 with management on 8222.
    NATS Box: Utility for interacting with NATS server.

Step 3: Running Tests
NATS Box Interaction

The tests can be run using the nats-box for NATS interactions. Access the NATS Box container:

sh

docker compose exec nats-box /bin/sh

Once inside the container, you can use nats-sub and nats-pub commands to interact with the NATS server for testing.
Example Commands for Testing

Publish a message:

sh

nats pub save.msg "Super secret message!" --server nats

JUnit Tests

You can run the JUnit tests using Gradle. Ensure that the Docker services are running, then execute the following command in the project directory:

sh

./gradlew test

You also can run the test via compose.yml file *--NOT FINISHED*

docker compose up test


This will run the integration tests defined in the project, including the tests for Redis and NATS integration.
Environment Variables

The application can be configured using the following environment variables:

    REDIS_HOST: Hostname for the Redis server.
    REDIS_PORT: Port for the Redis server.
    NATS_URL: URL for connecting to the NATS server.

These variables are set in the docker-compose.yml file.
Remaining Tasks

Contact

For any queries or further discussions, please contact:

Felipe M.

Email: felipemarcelo554@gmail.com