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
# /data is where docker-compose mounts the SQLite volume (WORKFLOW_DB=/data/workflow.db); it must
# exist and be owned by the app user before the volume is first attached, or the mount inherits
# root ownership and the app can't create the database file.
RUN addgroup -S workflow && adduser -S workflow -G workflow \
    && mkdir -p /data && chown -R workflow:workflow /app /data
COPY --chown=workflow:workflow --from=build /app/target/workflow-*.jar app.jar
USER workflow

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
