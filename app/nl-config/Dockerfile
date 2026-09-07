# Multi-stage build: compile with a JDK, ship only the runtime jar -- see
# app/nl-api/Dockerfile's identical pattern/reasoning. Build context is the
# repo root (both are modules of the one multi-module Gradle build).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY app/nl-config/build.gradle app/nl-config/build.gradle
RUN ./gradlew :app:nl-config:dependencies --no-daemon || true
COPY app/nl-config/src app/nl-config/src
RUN ./gradlew :app:nl-config:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/app/nl-config/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
