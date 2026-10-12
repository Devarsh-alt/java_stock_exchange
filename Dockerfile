# Multi-stage build for Java Stock Exchange Simulator
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy POM and dependencies first to leverage Docker layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build fat JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# Minimal runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Set non-root user for security
RUN groupadd -r exchange && useradd -r -g exchange exchange

# Copy fat JAR from build stage
COPY --from=build /app/target/stock-exchange-0.1.0-SNAPSHOT.jar /app/app.jar

# Configuration defaults
ENV PORT=8080
ENV SIMULATOR_ENABLED=false

EXPOSE 8080

USER exchange

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
