FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy pre-built JARs from host build
# Requires: mvn package -pl flowtree -am -DskipTests
#           mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency
COPY flowtree/target/ar-flowtree-*.jar /app/lib/
COPY flowtree/target/dependency/*.jar /app/lib/

# No MCP tool source files needed — ar-manager is the single
# centralized tool, running as a separate Docker service.

# Working directory for config files (workstreams.yaml, notification tokens, etc.)
# Mount /Users/Shared/flowtree/controller here at runtime
WORKDIR /config

# HTTP API port
EXPOSE 7780
# FlowTree agent connection port
EXPOSE 7766

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:7780/api/health || exit 1

ENTRYPOINT ["java", "-cp", "/app/lib/*", "io.flowtree.slack.FlowTreeController", \
    "--config", "/config/workstreams.yaml", \
    "--log-file", "/config/flowtree-controller.log"]
