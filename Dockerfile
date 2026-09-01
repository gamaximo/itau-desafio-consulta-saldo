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

# Roda com um usuário sem privilégios. Um processo que nunca precisa escrever fora do próprio heap
# não tem motivo para ser root dentro do contêiner — se a aplicação for comprometida algum dia, o
# raio de alcance para num usuário que não é dono de nada.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# MaxRAMPercentage em vez de um -Xmx fixo: a JVM dimensiona o heap a partir do limite de memória
# do contêiner, então a mesma imagem se comporta corretamente recebendo 512MB localmente ou 4GB em
# produção, sem rebuild e sem flag específica por ambiente.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
