# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS base
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew
COPY src src

FROM base AS test
RUN --mount=type=cache,target=/root/.gradle ./gradlew check --no-daemon

FROM base AS builder
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon \
    && cp $(ls build/libs/*.jar | grep -v plain) /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar

# Runs as an unprivileged user. A process that never needs to write outside its own heap has no
# reason to hold root inside the container — if the application is ever compromised, the blast
# radius stops at a user that owns nothing.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# MaxRAMPercentage instead of a fixed -Xmx: the JVM sizes its heap from the container's memory
# limit, so the same image behaves correctly whether it is given 512MB locally or 4GB in
# production, with no rebuild and no environment-specific flag.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
