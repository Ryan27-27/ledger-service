# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# tests run in CI (they need Docker for Testcontainers); the image build just packages
RUN mvn -B -q clean package -Dmaven.test.skip=true

# --- Run stage ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 $JAVA_OPTS -jar app.jar"]
