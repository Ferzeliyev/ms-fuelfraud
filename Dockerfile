FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle clean bootJar --no-daemon
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENV SERVER_PORT=8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
