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
| `GITHUB_OWNER` | `almostrealism` | GitHub org or user |
| `GITHUB_REPO` | `common` | Repository name |
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

## macOS Runners

For macOS self-hosted runners (no Docker), see [`../macos/`](../macos/).
