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
