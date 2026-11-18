# Self-hosted GitHub Actions Runner for Almost Realism CI
#
# This image provides:
#   - GitHub Actions runner agent
#   - JDK (Eclipse Temurin), major version from the JDK_MAJOR build arg
#   - Maven (with settings for local repo caching)
#   - AR environment variables pre-configured
#
# Build:
#   docker build -t ar-ci-runner:latest tools/ci/
#
# The image is used by docker-compose.yml to launch a fleet of runners.

FROM ubuntu:22.04

ARG RUNNER_VERSION=2.337.0

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# ---------- System packages ----------
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        jq \
        unzip \
        wget \
        gnupg \
        lsb-release \
        build-essential \
        libicu70 \
        libssl3 \
        zlib1g \
        sudo \
    && rm -rf /var/lib/apt/lists/*

# ---------- JDK (Eclipse Temurin / Adoptium) ----------
# JDK_MAJOR is the single source of truth for the JDK major version baked into
# this image. The self-hosted CI jobs verify the runner's java against it (the
# workflow-level EXPECTED_JDK_MAJOR in .github/workflows/analysis.yaml), so a
# JDK upgrade is this build arg plus a rebuild. The CUDA fleet reuses this image
# unchanged (tools/ci/cuda), so it inherits this version too.
ARG JDK_MAJOR=17
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] \
        https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends "temurin-${JDK_MAJOR}-jdk" && \
    rm -rf /var/lib/apt/lists/* && \
    ln -s /usr/lib/jvm/temurin-${JDK_MAJOR}-jdk-* /usr/lib/jvm/temurin-${JDK_MAJOR}-jdk

ENV JAVA_HOME=/usr/lib/jvm/temurin-${JDK_MAJOR}-jdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# ---------- Maven ----------
ARG MAVEN_VERSION=3.9.6
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar xz -C /opt && \
    ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# Retry a Maven Central download that fails transiently (HTTP 429 rate-limit, a
# dropped connection, a 5xx) with backoff instead of failing the job. MAVEN_ARGS
# is read by the Maven 3.9+ launcher and prepended to every `mvn` invocation, so
# this applies to every CI step without editing each command. These are Maven
# Resolver native-transport properties; unknown ones are ignored, so this is
# safe across Maven upgrades. It is a backstop under the shared dependency cache
# (see "Maven dependency caching" in tools/ci/README.md), not a substitute: a
# warm cache makes almost no Central requests in the first place.
ENV MAVEN_ARGS="-Daether.connector.http.retryHandler.count=10 -Daether.connector.http.retryHandler.interval=5000 -Daether.connector.http.retryHandler.intervalMax=60000"

# ---------- AR environment ----------
# AR_HARDWARE_LIBS is intentionally NOT set — the system auto-detects a suitable directory.
# Setting it to /tmp/ar_libs/ causes permission errors on shared or sandboxed systems.
# AR_HARDWARE_DRIVER is intentionally left unset to auto-detect the best available backend.

# ---------- Runner user ----------
RUN useradd -m -s /bin/bash runner && \
    echo "runner ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# ---------- GitHub Actions Runner ----------
WORKDIR /home/runner

RUN ARCH=$(dpkg --print-architecture) && \
    case "${ARCH}" in \
        amd64) RUNNER_ARCH="x64" ;; \
        arm64) RUNNER_ARCH="arm64" ;; \
        *)     RUNNER_ARCH="x64" ;; \
    esac && \
    curl -fsSL -o runner.tar.gz \
        "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz" && \
    tar xzf runner.tar.gz && \
    rm runner.tar.gz && \
    ./bin/installdependencies.sh || true

# ---------- Maven local repo (shared volume mount point) ----------
RUN mkdir -p /home/runner/.m2/repository && \
    chown -R runner:runner /home/runner

# ---------- Persistent shared Maven cache mount point ----------
# A host can mount a persistent, shared Maven repository here (a named volume in
# docker-compose.yml, see "Maven dependency caching" in tools/ci/README.md). The
# CI jobs then point maven.repo.local at a per-runner subdirectory of it (the
# select-maven-repo action), so the plugin and dependency set resolved once
# survives this ephemeral container and is reused by every later job instead of
# being re-downloaded from Maven Central. Creating it here with runner ownership
# means a fresh named volume mounted over it inherits that ownership, so the
# non-root runner can write to it. Without the mount this directory stays empty
# and the jobs fall back to the per-runner repository under $HOME.
RUN mkdir -p /opt/ar-maven-cache && \
    chown -R runner:runner /opt/ar-maven-cache

# Copy entrypoint and settings
COPY entrypoint.sh /home/runner/entrypoint.sh
COPY settings.xml /home/runner/.m2/settings.xml
RUN chmod +x /home/runner/entrypoint.sh && \
    chown -R runner:runner /home/runner

USER runner

ENTRYPOINT ["/home/runner/entrypoint.sh"]
