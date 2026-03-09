FROM gradle:8.14-jdk17 AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common ./common
COPY server-v2 ./server-v2

RUN chmod +x gradlew && ./gradlew --no-daemon :server-v2:shadowJar

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /workspace/server-v2/build/libs/server-v2-1.0-SNAPSHOT-all.jar /app/server-v2-all.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/server-v2-all.jar"]
CMD ["8080", "0", "server-v2-local"]
