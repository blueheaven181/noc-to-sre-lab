# Build/runtime image for game-service, added for the casino-lab work
# (Stage 0) so the Flyway/leaderboard proof can run in Docker without
# needing Java or Maven installed on the host - only Docker itself.
# Not used by the real lab deployment (that still goes through the
# jvm_app Ansible role + the CI/CD pipeline's built jar) - this is a
# test-harness image only.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/target/game-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
