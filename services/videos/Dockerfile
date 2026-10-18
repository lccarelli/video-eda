FROM gradle:9-jdk25 AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY videos videos
RUN --mount=type=cache,target=/root/.gradle ./gradlew :videos:bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre
RUN rm -f /usr/bin/pebble \
    && useradd --system --uid 10001 videos
USER 10001
WORKDIR /app
COPY --from=build /workspace/videos/build/libs/videos.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
