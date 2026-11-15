FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data/cache && chown -R 10001:10001 /app
COPY --from=build /build/target/goniec-z-wiejskiej-1.0.0.jar /app/app.jar
USER 10001:10001
ENV DATA_DIR=/app/data/cache SEED_PATH=/app/data/seed-bills.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
