# Build the Spring Boot application with Java 21.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
RUN mvn -B -DskipTests package

# Run the packaged application on a small Java 21 runtime.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/backend-0.0.1-SNAPSHOT.jar app.jar
ENV PORT=8080
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Xms256m -Xmx512m"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
