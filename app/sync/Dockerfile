FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY app/sync/build.gradle app/sync/build.gradle
RUN ./gradlew :app:sync:dependencies --no-daemon || true
COPY app/sync/src app/sync/src
RUN ./gradlew :app:sync:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/app/sync/build/install/sync /app
ENTRYPOINT ["/app/bin/sync"]
