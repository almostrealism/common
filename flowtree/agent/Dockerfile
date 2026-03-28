# FlowTree Agent — self-contained container that runs a FlowTree Server
# and executes ClaudeCodeJob instances via Claude Code CLI.
#
# Pre-build (from repo root):
#   mvn package -pl flowtree -am -DskipTests
#   mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency
#
# The image includes:
#   - Eclipse Temurin JRE 17 (runs the FlowTree Server)
#   - Node.js 20 + Claude Code CLI (executes agent jobs)
#   - Git (clone repos, branch management, commit/push)

FROM eclipse-temurin:17-jre-jammy

# ── System dependencies ──────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl git openssh-client \
    && rm -rf /var/lib/apt/lists/*

# ── Node.js 20 (required for Claude Code CLI) ───────────────────────
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# ── Non-root user ───────────────────────────────────────────────────
RUN useradd -m -s /bin/bash agent \
    && mkdir -p /home/agent/.claude /home/agent/.npm-global \
    && chown -R agent:agent /home/agent

USER agent
WORKDIR /home/agent

# npm global prefix under home (no root needed)
RUN npm config set prefix /home/agent/.npm-global
ENV PATH="/home/agent/.npm-global/bin:${PATH}"

# ── Claude Code CLI ─────────────────────────────────────────────────
RUN npm install -g @anthropic-ai/claude-code

# ── FlowTree JARs (pre-built on host) ──────────────────────────────
COPY --chown=agent:agent flowtree/target/ar-flowtree-*.jar /app/lib/
COPY --chown=agent:agent flowtree/target/dependency/*.jar  /app/lib/

# ── Agent configuration ─────────────────────────────────────────────
COPY --chown=agent:agent flowtree/conf/agent.properties /app/conf/agent.properties

# ── Entrypoint ──────────────────────────────────────────────────────
COPY --chown=agent:agent flowtree/agent/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Default workspace for repo checkouts
RUN mkdir -p /workspace/project
VOLUME /workspace/project

ENTRYPOINT ["/app/entrypoint.sh"]
