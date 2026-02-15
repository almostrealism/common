# Self-hosted GitHub Actions Runner for Almost Realism CI
#
# This image provides:
#   - GitHub Actions runner agent
#   - JDK 17 (Eclipse Temurin)
#   - Maven (with settings for local repo caching)
#   - AR environment variables pre-configured
#
# Build:
#   docker build -t ar-ci-runner:latest tools/ci/
#
# The image is used by docker-compose.yml to launch a fleet of runners.

FROM ubuntu:22.04

ARG RUNNER_VERSION=2.321.0
ARG TARGETPLATFORM=linux/amd64

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

# ---------- JDK 17 (Eclipse Temurin / Adoptium) ----------
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] \
        https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-17-jdk && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# ---------- Maven ----------
ARG MAVEN_VERSION=3.9.6
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar xz -C /opt && \
    ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# ---------- AR environment ----------
ENV AR_HARDWARE_LIBS=/tmp/ar_libs/
ENV AR_HARDWARE_DRIVER=native
ENV AR_HARDWARE_MEMORY_SCALE=7

# ---------- Runner user ----------
RUN useradd -m -s /bin/bash runner && \
    echo "runner ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# ---------- GitHub Actions Runner ----------
WORKDIR /home/runner

RUN ARCH=$(case "${TARGETPLATFORM}" in \
        "linux/amd64") echo "x64" ;; \
        "linux/arm64") echo "arm64" ;; \
        *) echo "x64" ;; \
    esac) && \
    curl -fsSL -o runner.tar.gz \
        "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-${ARCH}-${RUNNER_VERSION}.tar.gz" && \
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
