# Multi-stage build for VaahanSetu Spring Boot Backend
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/backend-0.0.1-SNAPSHOT.jar app.jar

ENV PORT=8080
ENV SPRING_PROFILES_ACTIVE=default
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Xms256m -Xmx512m"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
