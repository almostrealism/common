# Self-hosted GitHub Actions Runner for the Almost Realism OpenCL CI lane
#
# This image is NOT a general-purpose ROCm image and is NOT portable. It ships
# the OpenCL ICD loader and registers an ICD entry, but deliberately contains no
# ROCm userspace: the host's ROCm is bind-mounted read-only at /opt/rocm (see the
# Volume lines in ar-ci-cl-runner@.container). Without that mount the image is
# inert and the entrypoint's preflight refuses to register a runner.
#
# The reason is that the host's ROCm comes from a platform-specific package
# matched to its kernel driver. Pinning a public rocm/* base image would couple
# CI to a ROCm release chosen independently of the host's kernel module. The
# bind-mount makes userspace and kernel agree by construction, and keeps this
# image small enough that rebuilds stay cheap.
#
# It provides:
#   - GitHub Actions runner agent (org- or repo-scoped registration)
#   - JDK 17 (Eclipse Temurin) + Maven
#   - OpenCL ICD loader, clinfo, and an ICD entry naming the ROCm runtime
#   - build-essential, required by the `native` JNI backend that the CL jobs also
#     exercise via AR_HARDWARE_DRIVER=native,cl
#
# Build:
#   docker build -t ar-ci-rocm-runner:latest tools/ci/rocm/
#
# See README.md for the ICD findings that make this configuration necessary.

FROM ubuntu:22.04

# Empty means "resolve the current release at build time" (see below). Pin it
# only to reproduce a specific build.
ARG RUNNER_VERSION=
# Keep in sync with tools/ci/docker/Dockerfile, from which this image is derived.
ARG MAVEN_VERSION=3.9.6

# Absolute path to the ROCm OpenCL runtime, as it will appear under the
# read-only bind-mount at container run time.
#
# It must be ABSOLUTE. The ICD that ROCm itself ships (under
# /opt/rocm/etc/OpenCL/vendors) contains the bare name "libamdocl64.so", and
# because the library lives in the lib/opencl/ SUBDIRECTORY rather than lib/,
# that name is not on the loader's search path and resolves to nothing.
#
# Verified on the target platform (therock-gfx1151):
#   /opt/rocm/lib/opencl/libamdocl64.so -> libamdocl64.so.2
# Override with --build-arg if a host's ROCm layout differs; the entrypoint's
# preflight reports the candidates it finds.
ARG ROCM_OPENCL_LIB=/opt/rocm/lib/opencl/libamdocl64.so

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# ---------- System packages ----------
# libatomic1 is not optional: without it the ROCm OpenCL runtime fails to load
# entirely, and the resulting error names neither ROCm nor OpenCL.
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
        ocl-icd-libopencl1 \
        ocl-icd-opencl-dev \
        libatomic1 \
        clinfo \
    && rm -rf /var/lib/apt/lists/*

# ocl-icd-opencl-dev is not optional, and not just headers. ocl-icd-libopencl1
# installs only the versioned soname, libOpenCL.so.1. Programs linked at build
# time against that soname — clinfo among them — resolve it through ldconfig and
# work anywhere. JOCL does not: it dlopens the UNVERSIONED "libOpenCL.so" at
# runtime, and the unversioned symlink is shipped by the -dev package.
#
# Without it the only unversioned libOpenCL.so on the image is ROCm's, under
# /opt/rocm/lib, reachable solely via LD_LIBRARY_PATH. Any job that replaces
# LD_LIBRARY_PATH instead of appending to it then loses OpenCL from the JVM while
# clinfo keeps working — so the entrypoint's preflight passes, the CL backend
# silently fails to initialize, and the suite runs on the native backend and
# reports green. That is exactly what happened; see README.md.

# ---------- OpenCL ICD registration ----------
# The ICD loader discovers vendors by reading /etc/OpenCL/vendors. Writing the
# entry here, into the image, is the mechanism that works.
#
# OCL_ICD_VENDORS is NOT an alternative: on this host's ICD loader build it is
# silently ignored. It was tried as a directory, as a single file, and with an
# absolute-path ICD; all three enumerated zero platforms with no diagnostic.
RUN mkdir -p /etc/OpenCL/vendors && \
    echo "${ROCM_OPENCL_LIB}" > /etc/OpenCL/vendors/amdocl64.icd

# ROCm binaries and libraries live under the runtime bind-mount, so these paths
# resolve to nothing at build time and to the host's ROCm at run time.
#
# lib/opencl is listed alongside lib because the OpenCL runtime lives there and
# is not otherwise on the loader path — the ICD names it by absolute path, but
# any sibling it loads by soname still needs the directory to be searchable.
ENV PATH="/opt/rocm/bin:${PATH}"
ENV LD_LIBRARY_PATH="/opt/rocm/lib:/opt/rocm/lib/opencl:${LD_LIBRARY_PATH:-}"

# ---------- JDK 17 (Eclipse Temurin / Adoptium) ----------
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] \
        https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-17-jdk && \
    rm -rf /var/lib/apt/lists/* && \
    ln -s /usr/lib/jvm/temurin-17-jdk-* /usr/lib/jvm/temurin-17-jdk

ENV JAVA_HOME=/usr/lib/jvm/temurin-17-jdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Both CL jobs run actions/setup-java@v4 with java-version 17. setup-java honours
# JAVA_HOME_<version>_<arch> for a pre-installed JDK, so advertising the image's
# Temurin here keeps every job from downloading its own copy.
ENV JAVA_HOME_17_X64=/usr/lib/jvm/temurin-17-jdk

# ---------- Maven ----------
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar xz -C /opt && \
    ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# ---------- AR environment ----------
# AR_HARDWARE_LIBS is intentionally NOT set here — the system auto-detects a
# suitable directory. The CL jobs set it per-step to a runner-temp path.
# AR_HARDWARE_DRIVER is intentionally left unset; the jobs pass native,cl.

# ---------- Runner user ----------
RUN useradd -m -s /bin/bash runner && \
    echo "runner ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# ---------- GitHub Actions Runner ----------
WORKDIR /home/runner

# The version is resolved from the API rather than pinned, because these runners
# are ephemeral. GitHub forces an agent older than the current release to
# self-update before it will accept a job, and the container is discarded when
# that job ends — so a stale pin means every job re-downloads the agent, and a
# lane of sixteen matrix groups does it sixteen times. That surfaced as
# "Runner update in progress ... Downloading <newer> runner" followed by request
# timeouts. Building against the current release keeps the update path cold.
#
# Rebuild periodically to stay current; the entrypoint does not check.
RUN ARCH=$(dpkg --print-architecture) && \
    case "${ARCH}" in \
        amd64) RUNNER_ARCH="x64" ;; \
        arm64) RUNNER_ARCH="arm64" ;; \
        *)     RUNNER_ARCH="x64" ;; \
    esac && \
    VERSION="${RUNNER_VERSION}" && \
    if [ -z "${VERSION}" ]; then \
        VERSION=$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
            | jq -r '.tag_name // empty' | sed 's/^v//'); \
    fi && \
    if [ -z "${VERSION}" ]; then \
        echo "ERROR: could not determine the actions/runner release to install." >&2; \
        exit 1; \
    fi && \
    echo "Installing GitHub Actions runner ${VERSION} (${RUNNER_ARCH})" && \
    curl -fsSL -o runner.tar.gz \
        "https://github.com/actions/runner/releases/download/v${VERSION}/actions-runner-linux-${RUNNER_ARCH}-${VERSION}.tar.gz" && \
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
