# Stage 1: Build Frontend
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build Backend
FROM maven:3.9-eclipse-temurin-21 AS backend
# Note: Using Java 21 build image as 25 might be unstable in standard maven images 
# or specific config needed. However, runtime will use the requested version if available/stable.
# Let's try to find a Java 25 maven image or install it. 
# actually, eclipse-temurin:25-ea might exist, but let's stick to standard and see.
# The user asked for Java 25. Let's use a base image that supports it or just use 25 for runtime.
# For simplicity and stability, I'll use a generic maven image and let it download the JDK defined in toolchains or similar if strictly needed,
# but since pom says 25, we need a JDK 25 enabled builder.
# Since official maven images for 25 might be scarce, I will manually install or use a base capable of it.
# Alternative: Use openjdk:25-ea-jdk-slim and install maven.

FROM openjdk:25-ea-jdk-slim AS backend-builder
WORKDIR /app
RUN apt-get update && apt-get install -y maven

COPY pom.xml .
COPY src ./src
# Copy built frontend to static resources
COPY --from=frontend /app/frontend/dist ./src/main/resources/static

# Build the application
RUN mvn clean package -DskipTests

# Stage 3: Runtime
FROM openjdk:25-ea-jdk-slim
WORKDIR /app
COPY --from=backend-builder /app/target/*.jar app.jar
EXPOSE 8081 5060/udp 5060/tcp
ENTRYPOINT ["java", "-jar", "app.jar"]
