FROM gradle:8.14-jdk21 AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common ./common
COPY consumer-v3 ./consumer-v3

RUN chmod +x gradlew && ./gradlew --no-daemon :consumer-v3:shadowJar

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/consumer-v3/build/libs/consumer-v3-1.0-SNAPSHOT-all.jar /app/consumer-v3-all.jar

ENTRYPOINT ["java", "-jar", "/app/consumer-v3-all.jar"]
