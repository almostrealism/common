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

# Copy entrypoint and settings
COPY entrypoint.sh /home/runner/entrypoint.sh
COPY settings.xml /home/runner/.m2/settings.xml
RUN chmod +x /home/runner/entrypoint.sh && \
    chown -R runner:runner /home/runner

USER runner

ENTRYPOINT ["/home/runner/entrypoint.sh"]
