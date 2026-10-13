# Multi-stage build for Java Stock Exchange Simulator
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Use a Central mirror (Render's build IPs get HTTP 429 from repo.maven.apache.org)
COPY settings.xml /root/.m2/settings.xml

# Build the fat JAR. No `dependency:go-offline`: it tries to download every default
# Maven plugin (release, site, antrun, ...) and was the step that hung and failed.
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests \
      -Dmaven.wagon.http.retryHandler.count=5 \
      -Dmaven.wagon.http.retryHandler.requestSentEnabled=true \
      -Dmaven.wagon.rto=60000 \
      clean package

# Minimal runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd -r exchange && useradd -r -g exchange exchange

COPY --from=build /app/target/stock-exchange-0.1.0-SNAPSHOT.jar /app/app.jar

ENV PORT=8080
ENV SIMULATOR_ENABLED=false

EXPOSE 8080
USER exchange

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]