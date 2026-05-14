FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy pre-built JARs from host build
# Requires: mvn package -pl flowtree -am -DskipTests
#           mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency
COPY flowtree/target/ar-flowtree-*.jar /app/lib/
COPY flowtree/target/dependency/*.jar /app/lib/

# Bundle MCP tool source files that the controller distributes to agent
# containers via the pushed-tools mechanism. Currently this is just
# ar-secrets — a small stdio MCP server that ClaudeCodeJob downloads into
# each agent's filesystem so workspace-secret rendering happens in the
# agent's namespace rather than ar-manager's.
#
# IMPORTANT: FlowTreeController.registerPushedTools resolves the source
# via configDir → FLOWTREE_APP_DIR (defaults to /app). With WORKDIR /app
# above, the file MUST land at /app/tools/mcp/secrets/server.py.  If you
# move it, FlowTreeController refuses to start (by design — silent
# misconfiguration here used to produce empty pushed-tool configs and
# agents that could not access workspace secrets at all).
COPY tools/mcp/secrets/ /app/tools/mcp/secrets/

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
    "--config", "/config/workstreams.yaml"]
