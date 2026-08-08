# Build stage
FROM maven:3.9.9-eclipse-temurin-21 as build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /app/target/social-app-backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
