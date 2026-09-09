# Multi-stage build: compile with a JDK, ship only the runtime jar --
# mirrors app/sync/Dockerfile's "don't ship a build toolchain at runtime"
# convention. Build context is the repo root (see docker-compose.app.yml),
# same as app/sync/Dockerfile, since both are modules of the one
# multi-module Gradle build (settings.gradle/build.gradle/gradle/ at the
# root are shared, unlike app/ui's fully self-contained Node build).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
ENV GRADLE_USER_HOME=/gradle-cache
COPY .docker-gradle-cache /gradle-cache
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY app/nl-api/build.gradle app/nl-api/build.gradle
RUN ./gradlew :app:nl-api:dependencies --no-daemon --offline || true
COPY app/nl-api/src app/nl-api/src
RUN ./gradlew :app:nl-api:bootJar --no-daemon --offline

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/app/nl-api/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
