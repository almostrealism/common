# FlowTree Agent — self-contained container that runs a FlowTree Server
# and executes ClaudeCodeJob instances via Claude Code CLI.
#
# Pre-build (from repo root):
#   mvn package -pl flowtree -am -DskipTests
#   mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency
#
# The image provides the same development environment as rings/devtools
# plus pre-built FlowTree JARs and an auto-start entrypoint.

FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

# ── System dependencies (matches rings/devtools) ────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        # Build tools
        build-essential gcc g++ make cmake \
        # Python
        python3 python3-pip python3-venv \
        # Version control
        git openssh-client \
        # Utilities
        curl wget unzip vim nano tmux \
        # OpenCL (GPU acceleration)
        ocl-icd-opencl-dev opencl-headers \
        ca-certificates gnupg lsb-release \
        # Playwright browser dependencies
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 \
        libcups2 libdrm2 libdbus-1-3 libxkbcommon0 \
        libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
        libgbm1 libasound2 libpango-1.0-0 libcairo2 \
        libpangocairo-1.0-0 libatspi2.0-0 libx11-6 \
        libx11-xcb1 libxcb1 libxext6 libxcursor1 libxi6 \
        libxtst6 libglib2.0-0 libgtk-3-0 libgdk-pixbuf2.0-0 \
        libxshmfence1 libglu1-mesa libenchant-2-2 \
        libsecret-1-0 libhyphen0 libmanette-0.2-0 libflite1 \
        libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 \
        libgstreamer-plugins-bad1.0-0 gstreamer1.0-libav \
        # Firefox-specific dependencies (Chromium has ARM64/macOS-host
        # Docker compatibility issues, so Firefox is the default browser).
        libdbus-glib-1-2 libxt6 \
    && rm -rf /var/lib/apt/lists/*

# ── Maven ───────────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

# ── Node.js 20 ─────────────────────────────────────────────────────
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# ── Python MCP dependencies ─────────────────────────────────────────
COPY tools/mcp/requirements.txt /tmp/mcp-requirements.txt
RUN pip3 install --no-cache-dir -r /tmp/mcp-requirements.txt \
    && rm /tmp/mcp-requirements.txt

# ── Workspace (created as root before user switch) ──────────────────
RUN mkdir -p /workspace/project

# ── Non-root user ───────────────────────────────────────────────────
RUN useradd -m -s /bin/bash agent \
    && mkdir -p /home/agent/.claude /home/agent/.npm-global \
              /home/agent/.m2 /home/agent/.config/claude \
    && chown -R agent:agent /home/agent \
    && chown -R agent:agent /workspace \
    && usermod -aG sudo agent \
    && echo "agent ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

USER agent
WORKDIR /home/agent

# ── npm global prefix (no root needed) ──────────────────────────────
RUN npm config set prefix /home/agent/.npm-global
ENV PATH="/home/agent/.npm-global/bin:${PATH}"

# ── Claude Code CLI ─────────────────────────────────────────────────
RUN npm install -g @anthropic-ai/claude-code

# ── Playwright MCP + Firefox browser ────────────────────────────────
# Pre-install @playwright/mcp globally and download the Firefox browser
# binary into /home/agent/.cache/ms-playwright/ so agents can drive a
# headless browser without a cold `playwright install` on every run.
# Firefox (not Chromium) because Chromium has known compatibility
# issues on ARM64 Linux containers running on macOS hosts.
RUN npm install -g @playwright/mcp@latest \
    && npx --yes playwright@latest install firefox

# ── Shell environment ──────────────────────────────────────────────
RUN echo 'export PS1="\u@\h:\w$ "' >> ~/.bashrc && \
    echo 'alias ll="ls -alh"' >> ~/.bashrc && \
    echo 'alias pip=pip3' >> ~/.bashrc && \
    echo 'alias python=python3' >> ~/.bashrc && \
    echo 'export EDITOR=vim' >> ~/.bashrc && \
    echo 'export PATH="$HOME/.npm-global/bin:$PATH"' >> ~/.bashrc

# ── tmux config ────────────────────────────────────────────────────
RUN echo 'set -g mouse on' >> ~/.tmux.conf && \
    echo 'set -g history-limit 10000' >> ~/.tmux.conf && \
    echo 'set -g base-index 1' >> ~/.tmux.conf && \
    echo 'set -g default-terminal "screen-256color"' >> ~/.tmux.conf

# ── FlowTree JARs (pre-built on host) ──────────────────────────────
COPY --chown=agent:agent flowtree/target/ar-flowtree-*.jar /app/lib/
COPY --chown=agent:agent flowtree/target/dependency/*.jar  /app/lib/

# ── Agent configuration ─────────────────────────────────────────────
COPY --chown=agent:agent flowtree/conf/agent.properties /app/conf/agent.properties

# ── Entrypoint ──────────────────────────────────────────────────────
COPY --chown=agent:agent flowtree/agent/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Intentionally NO `VOLUME /workspace/project` — declaring a VOLUME
# would force Docker to create an anonymous volume at that path on
# every run, tripping the writable-volume guard in entrypoint.sh.
# Each container uses /workspace/project on its private overlay
# filesystem. See FLOWTREE_COLLISIONS.md.

ENTRYPOINT ["/app/entrypoint.sh"]
