# One Dockerfile for every service. docker-compose.yml picks the service with MODULE.
#
# The build stage is identical for all services, so Docker builds the code once and reuses
# that layer; only the small final stage differs.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/build/libs/${MODULE}-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
