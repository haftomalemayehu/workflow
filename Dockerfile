# --- Build stage ---
FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache curl
WORKDIR /app

# Resolve dependencies first so they layer-cache independently of source changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline

COPY src ./src
RUN ./mvnw -q -B package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S workflow && adduser -S workflow -G workflow
COPY --from=build /app/target/workflow-*.jar app.jar
USER workflow

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
