FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 finflow
COPY --from=build /workspace/target/finbtp-backend-*.jar app.jar
RUN mkdir -p /app/data && chown -R finflow:finflow /app
USER finflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
