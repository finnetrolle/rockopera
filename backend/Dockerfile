FROM gradle:8.12-jdk21 AS build

WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Cache dependencies
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle shadowJar --no-daemon

# --- Runtime ---
FROM eclipse-temurin:21-jre-alpine

# Install Node.js and Claude Code CLI
RUN apk add --no-cache nodejs npm bash git && \
    npm install -g @anthropic-ai/claude-code

RUN addgroup -S rockopera && adduser -S rockopera -G rockopera

WORKDIR /app

COPY --from=build /app/build/libs/rockopera-0.1.0.jar ./rockopera.jar

RUN mkdir -p /app/log /app/workspaces && \
    chown -R rockopera:rockopera /app

USER rockopera

ENV ROCKOPERA_LOG_DIR=/app/log

EXPOSE 4000

ENTRYPOINT ["java", "-jar", "rockopera.jar"]
CMD ["--i-understand-that-this-will-be-running-without-the-usual-guardrails", "--port", "4000", "/app/workflow/WORKFLOW.md"]
