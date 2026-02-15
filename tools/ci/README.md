# AR CI Runner Fleet

Self-hosted GitHub Actions runner fleet using Docker Compose. Replaces
GitHub's `ubuntu-latest-16-cores` runners with a local fleet for all
test jobs in the Almost Realism CI pipeline.

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│                     GitHub Actions                         │
│                                                            │
│  build ──► test (×7 matrix) ──► test-ml   ──► test-music  │
│                              ──► test-audio ──►            │
│                                                            │
│  analysis (stays on ubuntu-latest)                         │
└──────────────┬─────────────────────────────────────────────┘
               │ jobs labelled [self-hosted, linux, ar-ci]
               │ are picked up by the local fleet
               ▼
┌────────────────────────────────────────────────────────────┐
│              Docker Compose Fleet (your machine)           │
│                                                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐    ┌──────────┐  │
│  │ runner-1 │ │ runner-2 │ │ runner-3 │ …  │ runner-N │  │
│  └──────────┘ └──────────┘ └──────────┘    └──────────┘  │
│       │            │            │                │         │
│       └────────────┴────────────┴────────────────┘         │
│                         │                                  │
│                  maven-repo volume                         │
│             (shared local Maven cache)                     │
└────────────────────────────────────────────────────────────┘
```

Each runner container includes:
- **JDK 17** (Eclipse Temurin)
- **Maven** (with shared local repository volume)
- **GitHub Actions runner agent** (ephemeral mode)
- **AR environment variables** pre-configured

Runners register as **ephemeral** — each picks up exactly one job, then
exits and is recreated by Docker Compose's restart policy. This ensures
clean state for every job.

## Prerequisites

- **Docker** (with Compose v2 — `docker compose` not `docker-compose`)
- **GitHub Personal Access Token** with these scopes:
  - `repo` (full control of private repositories)
  - `admin:org` (if the repo belongs to an organization)
  - Or a fine-grained token with **Administration** read/write

## Quick Start

```bash
cd tools/ci

# 1. Create your .env file from the example
cp .env.example .env

# 2. Edit .env — fill in your GITHUB_PAT, GITHUB_OWNER, GITHUB_REPO
#    Example:
#      GITHUB_PAT=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
#      GITHUB_OWNER=almostrealism
#      GITHUB_REPO=common

# 3. Build the runner image
docker compose build

# 4. Start the fleet (default: 10 runners)
docker compose up -d --scale runner=10
```

## Configuration

All configuration is via the `.env` file (see `.env.example` for
defaults).

| Variable | Default | Description |
|---|---|---|
| `GITHUB_PAT` | *(required)* | GitHub personal access token |
| `GITHUB_OWNER` | `almostrealism` | GitHub org or user |
| `GITHUB_REPO` | `common` | Repository name |
| `RUNNER_MEMORY_LIMIT` | `16g` | Memory limit per container |
| `RUNNER_CPU_LIMIT` | `4` | CPU cores per container |

### Why 10 runners?

The pipeline has these concurrent test jobs at peak:
- `test` job: 7 matrix groups (run in parallel)
- `test-ml`: 1 runner
- `test-audio`: 1 runner
- `test-music`: 1 runner (runs after ml + audio)
- `build`: 1 runner (runs first, sequentially)

Since `build` finishes before tests start, and `test-music` waits for
`test-ml` and `test-audio`, the peak concurrency is **7 (test matrix) +
1 (test-ml) + 1 (test-audio) = 9**. Having 10 runners provides a small
buffer.

### Resource Requirements

Each runner needs sufficient resources for Maven builds and test
execution. Recommended minimums per container:

| Resource | Minimum | Recommended |
|---|---|---|
| Memory | 8 GB | 16 GB |
| CPU cores | 2 | 4 |
| Disk | 10 GB | 20 GB |

**Total host requirements** (10 runners): 80–160 GB RAM, 20–40 CPU
cores.

For smaller machines, reduce the fleet size:

```bash
# Fewer runners — jobs will queue instead of running in parallel
docker compose up -d --scale runner=4
```

## Operations

### Start the fleet

```bash
docker compose up -d --scale runner=10
```

### Check runner status

```bash
# See running containers
docker compose ps

# View logs from all runners
docker compose logs -f

# View logs from a specific runner
docker compose logs -f runner-3
```

### Verify runners registered with GitHub

Go to **Settings → Actions → Runners** in your GitHub repository. You
should see runners named like `<container-hostname>` with the labels
`self-hosted`, `linux`, `ar-ci`.

Or use the GitHub CLI:

```bash
gh api repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners \
    --jq '.runners[] | {name, status, labels: [.labels[].name]}'
```

### Stop the fleet

```bash
docker compose down
```

This sends SIGTERM to each container. The entrypoint script catches the
signal and deregisters the runner from GitHub before exiting.

### Rebuild after changes

```bash
docker compose build --no-cache
docker compose up -d --scale runner=10
```

### Scale up or down

```bash
# Scale up to 15 runners
docker compose up -d --scale runner=15

# Scale down to 5 runners
docker compose up -d --scale runner=5
```

### Clean up

```bash
# Stop and remove containers + volumes (wipes Maven cache)
docker compose down -v

# Also remove the built image
docker compose down -v --rmi local
```

## How It Works

### Runner Lifecycle

1. Container starts → `entrypoint.sh` runs
2. Requests a **registration token** from GitHub API using `GITHUB_PAT`
3. Configures the runner in **ephemeral** mode (one job, then exit)
4. Runs the GitHub Actions runner agent
5. Agent picks up a job matching labels `[self-hosted, linux, ar-ci]`
6. Job executes inside the container
7. Runner exits after the job completes
8. Docker Compose restarts the container (due to `restart: unless-stopped`)
9. New container registers a fresh runner → back to step 2

### Maven Cache Sharing

All runners share a Docker volume (`maven-repo`) mounted at
`/home/runner/.m2/repository`. This means:

- The first build downloads all dependencies
- Subsequent builds (on any runner) reuse the cached artifacts
- `mvn install -DskipTests` in test jobs benefits from previously built
  project modules

### GitHub Actions Integration

The workflow (`.github/workflows/analysis.yaml`) uses:

```yaml
runs-on: [self-hosted, linux, ar-ci]
```

for all build and test jobs. GitHub matches these labels against
registered runners. The `analysis` job stays on `ubuntu-latest` because
it needs Qodana and CodeQL SARIF upload, which require GitHub-hosted
runner features.

### Environment Variables

The Docker image pre-configures the AR environment:

```
AR_HARDWARE_LIBS=/tmp/ar_libs/
AR_HARDWARE_DRIVER=native
```

The `actions/setup-java` step in the workflow remains for JDK path
configuration and Maven cache setup. It is idempotent — having JDK 17
already installed just means the step completes faster.

## Troubleshooting

### Runners don't appear in GitHub

- Verify `GITHUB_PAT` has the correct scopes (`repo` + `admin:org`)
- Check runner logs: `docker compose logs runner`
- Look for "ERROR: Failed to obtain registration token"

### Runners appear but jobs don't get picked up

- Verify the runner labels match: `self-hosted`, `linux`, `ar-ci`
- Check that runners show as **Idle** (not **Offline**) in GitHub
  Settings → Actions → Runners
- Ensure enough runners are available for the job concurrency

### Maven builds fail with missing dependencies

- The shared Maven volume may be corrupted. Recreate it:
  ```bash
  docker compose down -v
  docker compose up -d --scale runner=10
  ```

### Out of memory errors

- Increase `RUNNER_MEMORY_LIMIT` in `.env`
- Ensure the host has enough total RAM for all runners

### Tests fail with native library errors

- The AR_HARDWARE_LIBS directory (`/tmp/ar_libs/`) is container-local
  and ephemeral — this is expected. Libraries are regenerated per job.
- If you see `NoClassDefFoundError: PackedCollection`, verify
  `AR_HARDWARE_DRIVER=native` is set (it is by default in the image).

### Container keeps restarting in a loop

- This usually means registration is failing repeatedly
- Check logs: `docker compose logs --tail=50 runner`
- The GitHub API has rate limits — if you're scaling up/down frequently,
  you may hit the registration token rate limit

## Security Notes

- The `GITHUB_PAT` is passed as an environment variable to the
  container. Do not commit the `.env` file (it is excluded via patterns
  in `.gitignore`).
- Runners are configured in **ephemeral** mode, so they self-destruct
  after each job. This limits the window for any potential compromise.
- The Docker containers run as a non-root `runner` user (with
  passwordless sudo for package installation if needed by actions).

## Files

```
tools/ci/
├── .env.example        # Template for environment configuration
├── docker-compose.yml  # Compose definition for the runner fleet
├── Dockerfile          # Runner image (Ubuntu 22.04 + JDK 17 + Maven + GH runner)
├── entrypoint.sh       # Container entrypoint (register, run, cleanup)
├── settings.xml        # Maven settings (shared local repo)
└── README.md           # This file
```
