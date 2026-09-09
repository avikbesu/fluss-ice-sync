# Multi-stage build: compile with a JDK, ship only the runtime jar -- see
# app/nl-api/Dockerfile's identical pattern/reasoning. Build context is the
# repo root (both are modules of the one multi-module Gradle build).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
ENV GRADLE_USER_HOME=/gradle-cache
COPY .docker-gradle-cache /gradle-cache
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY app/config/build.gradle app/config/build.gradle
RUN ./gradlew :app:config:dependencies --no-daemon --offline || true
COPY app/config/src app/config/src
RUN ./gradlew :app:config:bootJar --no-daemon --offline

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/app/config/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
