FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -o 10001 -g 10001 /app/data
COPY --from=build --chown=10001:10001 /workspace/target/zhida-learning-agent-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/api/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
