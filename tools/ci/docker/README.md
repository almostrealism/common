# AR CI Runner Fleet

Self-hosted GitHub Actions runner fleet using Docker Compose.

## Quick Start

```bash
cd tools/ci/docker

# 1. Create your .env file
cp .env.example .env
# Edit .env — set GITHUB_PAT and RUNNER_PREFIX

# 2. Build and launch
docker compose up -d --build --scale runner=5
```

Runners register as `<RUNNER_PREFIX>-1`, `<RUNNER_PREFIX>-2`, etc.
Set `RUNNER_PREFIX` per machine (e.g., `mac-studio`, `linux-build`).

## Configuration

All configuration is via the `.env` file (see `.env.example`).

| Variable | Default | Description |
|---|---|---|
| `GITHUB_PAT` | *(required)* | GitHub personal access token |
| `RUNNER_SCOPE` | `repo` | `repo` (single repository) or `org` (shared across the org) |
| `GITHUB_OWNER` | `almostrealism` | GitHub org or user |
| `GITHUB_REPO` | `common` | Repository name (ignored when `RUNNER_SCOPE=org`) |
| `RUNNER_PREFIX` | `ar-runner` | Name prefix for this machine |
| `RUNNER_MEMORY_LIMIT` | `16g` | Memory limit per container |
| `RUNNER_CPU_LIMIT` | `4` | CPU cores per container |

## Operations

```bash
# Start 5 runners on this machine
RUNNER_PREFIX=mac-studio docker compose up -d --scale runner=5

# Check status
docker compose ps

# View logs
docker compose logs -f

# Stop and deregister
docker compose down

# Rebuild after changes
docker compose up -d --build --scale runner=5
```

## Running a build-only runner (the `ar-build` label)

The `build` job in `.github/workflows/analysis.yaml` can be moved off the
GitHub-hosted runner onto a self-hosted one, chosen by the `BUILD_RUNS_ON`
repository variable (see "The `BUILD_RUNS_ON` repository variable" in
[`../../../.github/CLAUDE.md`](../../../.github/CLAUDE.md)). The runner it lands
on should carry a single **`ar-build`** label — not `ar-ci` (the CPU test lane)
and not `ar-ci-cuda` (the CUDA capability) — so build jobs never queue behind the
tens-of-minutes test jobs. `build` produces only jars, so this runner needs no
GPU, no sample library, and no particular architecture.

This image is exactly that runner: the plain CPU fleet image with a different
label and its own Maven cache. Run it as a **separate compose project** so it
coexists with any test fleet on the same host (including the CUDA fleet on the
DGX Spark — see [`../cuda/README.md`](../cuda/README.md)) without tearing down
the other's containers or sharing its Maven cache volume:

```bash
cd tools/ci/docker
cp .env.example .env        # set GITHUB_PAT; set RUNNER_SCOPE=org on a shared host

# A distinct project name (-p), label, and RUNNER_PREFIX. The project name gives
# this fleet its own Maven cache volume (ar-build_ar-maven-cache), isolated from
# the test fleets'. No GPU reservation and no sample mount: this compose file has
# neither, which is exactly right for build-only.
RUNNER_LABELS=ar-build RUNNER_PREFIX=dgx-build \
  docker compose -p ar-build up -d --build --scale runner=1
```

Then enable the variable **after the runner reports online** (Settings → Actions
→ Variables, repository scope):

```
BUILD_RUNS_ON = ["self-hosted","linux","ar-build"]
```

Verify the runner registered with the right label:

```bash
gh api orgs/almostrealism/actions/runners \
    --jq '.runners[] | select(.labels[].name == "ar-build") | {name, status}'
```

**Roll back** to the GitHub-hosted build with no commit: delete the
`BUILD_RUNS_ON` variable. `build` returns to `ubuntu-latest` on the next run.
Stop the build-only runner when it is no longer wanted:

```bash
cd tools/ci/docker
RUNNER_LABELS=ar-build docker compose -p ar-build down
```

One `ar-build` runner is enough: it holds a single `build` at a time, and a
validate run starts at most one `build` per push. The first job on a fresh
runner resolves the dependency set from Maven Central into its
`ar-build_ar-maven-cache` volume; later jobs reuse it (see "Maven dependency
cache" below).

## How It Works

Each runner container:
1. Queries GitHub for existing runners with the same prefix
2. Claims the lowest available index (e.g., `mac-studio-3`)
3. Removes any stale offline runner with that name
4. Registers as an **ephemeral** runner (one job, then exit)
5. Picks up a job matching labels `[self-hosted, linux, ar-ci]`
6. Exits after the job completes
7. Docker Compose restarts the container, which re-registers

Each container has its own Maven repository to avoid concurrency
issues when multiple runners build in parallel.

## Maven dependency cache

These runners are ephemeral, so without a persistent cache every job re-resolves
the whole plugin and dependency set from Maven Central and the pipeline hits its
429 rate limit (see "Maven dependency caching" in
[`../README.md`](../README.md#maven-dependency-caching) for the full design).
`docker-compose.yml` mounts a **named volume** `ar-maven-cache` at
`/opt/ar-maven-cache`; the CI jobs point `maven.repo.local` at a per-runner
subdirectory of it, so the dependency set resolved once survives the ephemeral
containers.

**Enable it** (safe to apply any time — CI falls back to the empty per-container
repository until you do):

```bash
# Rebuild the image (it creates the /opt/ar-maven-cache mount point owned by the
# runner user) and recreate the containers so they pick up the named volume.
docker compose up -d --build --scale runner=5
```

**Verify** the volume exists and warms:

```bash
docker volume inspect ar-ci_ar-maven-cache          # exists after `up`
docker run --rm -v ar-ci_ar-maven-cache:/c busybox ls /c   # per-runner subdirs after a job
# In a job log, "Select Maven repository" prints:
#   using persistent shared Maven cache /opt/ar-maven-cache/<runner>
```

**Roll back / force a cold rebuild** (always safe — a cold cache resolves from
Central, only slower):

```bash
docker compose down
docker volume rm ar-ci_ar-maven-cache   # discard the cache; it rebuilds on next job
docker compose up -d --scale runner=5
```

## JDK Version (Baked Into the Image)

The image ships the JDK; the CI jobs do **not** download one with
`actions/setup-java`. The `test` job instead verifies the
runner's `java` against the workflow-level `EXPECTED_JDK_MAJOR` in
`.github/workflows/analysis.yaml` and fail fast, naming the runner, if it does
not match. The major version is the single `JDK_MAJOR` build arg in the
[`Dockerfile`](Dockerfile) (default `17`); `JAVA_HOME` and `PATH` in the image
point at that JDK, which is what Maven and the forked surefire JVMs use.

Because the runners are ephemeral (one job per container, then a fresh one),
the JDK only changes when the **image** is rebuilt. To move the fleet to a new
major version:

1. Change `EXPECTED_JDK_MAJOR` in `.github/workflows/analysis.yaml` (one value,
   used by every self-hosted job and lane) and open that as its own `ci/...`
   pull request, since CI files are locked to `ci/` branches.
2. On each host running this fleet, rebuild the image and restart the runners
   so the new container carries the new JDK:

   ```bash
   cd tools/ci/docker
   docker compose down                               # deregister the old runners
   docker compose build --build-arg JDK_MAJOR=21     # or omit the arg for the default (17)
   docker compose up -d --scale runner=5
   ```

   With no `--build-arg`, the image builds the default (`17`). A plain
   `docker compose up -d --build` also works once the default is the version you
   want. The verify step turns red on any host still running an old image, so a
   missed host is reported rather than silently running the wrong JDK.

A single `JDK_MAJOR` value and the workflow's `EXPECTED_JDK_MAJOR` must agree;
a mismatch is exactly what the verify step exists to catch.

## Sharing Runners Across Repositories (Org-Level)

By default each container registers against the single repository named by
`GITHUB_REPO`. To let several repositories in the org share the fleet, set
`RUNNER_SCOPE=org` in `.env` — the containers then register at the
organization level and `GITHUB_REPO` is ignored:

```bash
# In .env
RUNNER_SCOPE=org
GITHUB_OWNER=almostrealism
```

Then, once per org, grant the relevant repositories access to the runner group
the containers join (`RUNNER_GROUP`, default `Default`) under **Org Settings ->
Actions -> Runner groups -> (group) -> Repository access**. Without that grant,
jobs queue forever against runners that report healthy.

The PAT needs the `admin:org` scope (classic) or the organization
"Self-hosted runners" read/write permission (fine-grained). Workflows keep
targeting the fleet by label (`runs-on: [self-hosted, linux, ar-ci]`); nothing
in the workflow files changes.

Switching an existing fleet from repo to org scope: `docker compose down`
deregisters the containers from their repo-level registrations (the dying
processes still hold the old env), and the next `up` registers them at the org
level. The same contract, with the same names, is what
[`../macos/runner.sh`](../macos/README.md#sharing-runners-across-repositories-org-level)
and [`../rocm/entrypoint.sh`](../rocm/) use.

Verify org-level runners:

```bash
gh api orgs/almostrealism/actions/runners \
    --jq '.runners[] | select(.labels[].name == "ar-ci") | {name, status, labels: [.labels[].name]}'
```

## macOS Runners

For macOS self-hosted runners (no Docker), see [`../macos/`](../macos/).
