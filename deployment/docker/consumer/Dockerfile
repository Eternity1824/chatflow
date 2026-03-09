FROM gradle:8.14-jdk17 AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common ./common
COPY consumer ./consumer

RUN chmod +x gradlew && ./gradlew --no-daemon :consumer:shadowJar

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /workspace/consumer/build/libs/consumer-1.0-SNAPSHOT-all.jar /app/consumer-all.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/consumer-all.jar"]
