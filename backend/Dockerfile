# ---- Build: compile and package the Spring Boot jar ----
FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/pitsch-backend.jar extract --layers --launcher --destination target/extracted

# ---- Runtime: JRE only, non-root, layered jar for small rebuilds ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 pitsch && adduser -S -u 10001 -G pitsch pitsch
WORKDIR /app
COPY --from=build --chown=pitsch:pitsch /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=pitsch:pitsch /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=pitsch:pitsch /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=pitsch:pitsch /workspace/target/extracted/application/ ./
USER 10001
ENV PITSCH_MODE=production \
    PORT=8080 \
    MANAGEMENT_PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/health/live" >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
