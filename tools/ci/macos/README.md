# AR CI macOS Runner

Self-hosted GitHub Actions runner for macOS. Runs natively (no Docker)
as a simple shell script loop, picking up jobs labelled
`[self-hosted, macos, ar-ci]`.

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                        GitHub Actions                              │
│                                                                    │
│  build ──► test (linux) ──► test-ml (linux)  ──► test-music (lin) │
│                          ──► test-audio (lin) ──►                  │
│              │                  │                      │           │
│              ▼                  ▼                      ▼           │
│          test-mac          test-ml-mac           test-music-mac    │
│                            test-audio-mac                          │
└──────────┬───────────────────────┬────────────────────────────────┘
           │ [self-hosted,         │ [self-hosted,
           │  linux, ar-ci]        │  macos, ar-ci]
           ▼                       ▼
  Docker fleet (Linux)       Native runner (macOS)
```

Each macOS test job depends on its Linux counterpart passing, but does
**not** block subsequent Linux jobs. This means macOS tests run as a
trailing verification — Linux tests continue progressing while macOS
catches up.

## Prerequisites

- **macOS** (Intel or Apple Silicon)
- **JDK 17**: `brew install --cask temurin@17`
- **Maven**: `brew install maven`
- **jq**: `brew install jq`
- **GitHub Personal Access Token** with `repo` + `admin:org` scopes

## Quick Start

```bash
cd tools/ci/macos

# 1. Configure credentials
cp .env.example .env
# Edit .env — fill in GITHUB_PAT, GITHUB_OWNER, GITHUB_REPO

# 2. Start the runner (installs runner agent automatically if needed)
chmod +x runner.sh
./runner.sh
```

The runner registers with GitHub, picks up one job, completes it, then
re-registers for the next job (ephemeral mode in a loop). If the runner
dies or its registration is deleted server-side, the script automatically
removes the local configuration and re-registers.

## How It Works

### Runner Lifecycle

1. `runner.sh` loads `.env` configuration
2. Installs the runner agent if not already present
3. **Removes** any existing runner configuration (avoids stale state)
4. Requests a **registration token** from GitHub API
5. Configures the runner in **ephemeral** mode
6. Runner agent waits for a job matching `[self-hosted, macos, ar-ci]`
7. Job executes natively on the Mac
8. Runner exits after the job completes
9. Script loops back to step 3

The remove-before-configure cycle ensures the runner never gets stuck
in a "Cannot configure because already configured" state, even if a
previous run died unexpectedly or the server-side registration was
deleted.

This is equivalent to the Docker Compose `restart: unless-stopped`
behavior used by the Linux fleet, but implemented as a shell loop
since Docker is not available.

### Signal Handling

Pressing Ctrl+C (SIGINT) or sending SIGTERM triggers a graceful
shutdown: the runner deregisters from GitHub before exiting.

### Environment

The runner script exports AR environment variables automatically:

```
# AR_HARDWARE_LIBS is auto-detected — do not set manually
```

`AR_HARDWARE_DRIVER` is intentionally left unset to auto-detect the best available backend.

JDK and Maven must already be installed on the system. The
`actions/setup-java` step in the workflow ensures correct PATH
configuration.

## Running in the Background

To keep the runner running after closing the terminal:

```bash
# Using nohup
nohup ./runner.sh > runner.log 2>&1 &

# Or using a tmux/screen session
tmux new-session -d -s ar-runner './runner.sh'
```

### Restarting After Editing `.env`

`.env` is read **once**, when `runner.sh` starts. The re-registration that
happens between jobs does **not** re-read it, so changes (e.g. `RUNNER_SCOPE`,
`RUNNER_CPU_LIMIT`, labels) only take effect after the wrapper script itself is
restarted — restarting just the runner agent is not enough.

Do this while the runner is **Idle** (not mid-job) so you don't interrupt a build:

```bash
# 1. See what's running, with PID and parent PID. Match on the script name
#    (NOT a full path) — when started via `cd ... && nohup ./runner.sh` the
#    command line is just `bash ./runner.sh`, so a path-prefixed pattern misses
#    it. Note `runner\.sh` does not match the agent's `run.sh`.
ps -Ao pid,ppid,command | grep -Ei 'runner\.sh|run\.sh|Runner\.Listener' | grep -v grep

# If more than one `runner.sh` appears you have multiple wrappers running (e.g.
# nohup was started more than once). Kill them ALL — concurrent wrappers default
# to the same RUNNER_NAME and clobber each other's registration.

# 2. Stop gracefully — signal BOTH the wrapper loop and the runner agent.
#    The wrapper's SIGINT trap then deregisters the runner from GitHub.
pkill -INT -f 'runner\.sh'
pkill -INT -f 'Runner\.Listener'

# 3. Confirm everything is gone (should print nothing)
pgrep -fl 'runner\.sh|run\.sh|Runner\.Listener'

# 4. Relaunch ONE instance with the new .env
nohup ./runner.sh > runner.log 2>&1 &

# 5. Watch startup — the banner echoes the active scope/settings
tail -f runner.log
```

Signal **both** processes: the wrapper is normally blocked waiting on the runner
agent (`Runner.Listener`), so signaling the agent lets that foreground command
return, after which the wrapper's `cleanup` trap fires and deregisters cleanly.
Signaling only the agent would just make the loop re-register with the *old*
in-memory configuration.

A wrapper with parent PID `1` was orphaned by `nohup` (its launching shell
exited) — that is normal and does **not** mean it is supervised. Only an actual
launchd service (see below) respawns the runner automatically; if you used
launchd, stop it with `launchctl bootout` instead of `pkill`, or it will
relaunch instantly.

Avoid `kill -9` — it bypasses the deregister trap and leaves a stale offline
runner in GitHub. It is not fatal (the script calls `remove_runner` before
re-registering, and ephemeral runners are cleaned up server-side), but a graceful
stop is tidier.

> When switching a runner from repo to org scope (`RUNNER_SCOPE=org`), the
> graceful stop above deregisters it from its old repo-level registration (the
> dying process still holds the old env), and the fresh start registers it at the
> org level. Make sure the runner group grants the relevant repositories access
> first, or jobs will queue.

### launchd Service (Auto-Start on Boot)

To start the runner automatically on login, create a launchd plist:

```bash
cat > ~/Library/LaunchAgents/com.almostrealism.ci-runner.plist << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.almostrealism.ci-runner</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>-c</string>
        <string>REPLACE_WITH_FULL_PATH/tools/ci/macos/runner.sh</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/ar-ci-runner.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/ar-ci-runner.log</string>
</dict>
</plist>
PLIST

# Load the service
launchctl load ~/Library/LaunchAgents/com.almostrealism.ci-runner.plist
```

## Configuration

All configuration is via the `.env` file (see `.env.example`).

| Variable | Default | Description |
|---|---|---|
| `GITHUB_PAT` | *(required)* | GitHub personal access token |
| `RUNNER_SCOPE` | `repo` | `repo` (single repository) or `org` (shared across the org) |
| `GITHUB_OWNER` | `almostrealism` | GitHub org or user |
| `GITHUB_REPO` | `common` | Repository name (required for `repo` scope, ignored for `org`) |
| `RUNNER_NAME` | `$(hostname)-macos` | Runner display name in GitHub |
| `RUNNER_GROUP` | `Default` | Runner group |
| `RUNNER_WORKDIR` | `~/actions-runner/_work` | Job working directory |
| `RUNNER_CPU_LIMIT` | *(unset — no limit)* | Max CPUs for jobs (requires `cpulimit`) |

## Sharing Runners Across Repositories (Org-Level)

A repository-scoped runner only serves the one repo it registered against. To
let several repos in the `almostrealism` org (e.g. `common` and `ringsdesktop`)
share the same physical Macs, register the runners at the **organization**
level instead:

```bash
# In .env
RUNNER_SCOPE=org
GITHUB_OWNER=almostrealism
# GITHUB_REPO is ignored in org scope
```

Then, **once per org**, grant the relevant repositories access to the runner
group the runners join (the script uses `RUNNER_GROUP`, default `Default`):

**Org Settings -> Actions -> Runner groups -> (group) -> Repository access** —
select "All repositories" or add `common` and `ringsdesktop` explicitly.

Workflows continue to target the runners by label
(`runs-on: [self-hosted, macos, ar-ci]`); nothing in the workflow files needs to
change. The PAT needs the `admin:org` scope (classic) or the organization
"Self-hosted runners" administration permission (fine-grained).

> **Security note:** GitHub recommends against using self-hosted runners with
> **public** repositories, because a pull request from a fork can execute
> arbitrary code on the runner host. If any repo sharing the group is public,
> restrict the group to private repositories and/or require approval for
> fork-PR workflow runs.

Verify org-level runners:

```bash
gh api orgs/almostrealism/actions/runners \
    --jq '.runners[] | select(.labels[].name == "ar-ci") | {name, status, labels: [.labels[].name]}'
```

## CPU Limiting

On shared machines you may want to prevent the runner from saturating all
cores. Set `RUNNER_CPU_LIMIT` in `.env` to the maximum number of CPUs the
job is allowed to use:

```bash
# Allow up to 4 CPUs on a shared Mac Mini
RUNNER_CPU_LIMIT=4
```

This requires `cpulimit` (`brew install cpulimit`). If `cpulimit` is not
installed the limit is silently ignored and a warning is printed at
startup. The limit applies to the runner process and all its children
(Maven, JVM forks, etc.).

Choose a value appropriate for the machine — for example 4 on a Mac Mini
that also runs other services, or 8 on a dedicated Mac Studio.

## Verify Runner Registration

After starting, check that the runner appears in GitHub:

**Settings -> Actions -> Runners** — look for a runner with labels
`self-hosted`, `macos`, `ar-ci`.

Or via CLI:

```bash
gh api repos/almostrealism/common/actions/runners \
    --jq '.runners[] | select(.labels[].name == "ar-ci") | {name, status, os, labels: [.labels[].name]}'
```

## Troubleshooting

### Runner doesn't appear in GitHub

- Verify `GITHUB_PAT` has correct scopes (`repo` + `admin:org`)
- Check terminal output for registration errors
- Verify network connectivity to `api.github.com`

### Jobs don't get picked up

- Verify labels match: `self-hosted`, `macos`, `ar-ci`
- Check runner shows as **Idle** in GitHub Settings
- Only one job runs at a time per runner

### Native library errors on macOS

- macOS uses `DYLD_LIBRARY_PATH` instead of `LD_LIBRARY_PATH`, but
  SIP may strip it. The `-DAR_HARDWARE_LIBS=Extensions` flag is the
  primary mechanism and should work regardless.
- If you see `NoClassDefFoundError: PackedCollection`, verify the
  auto-detected library directory is writable. `AR_HARDWARE_LIBS` is
  auto-detected — do not set it manually. `AR_HARDWARE_DRIVER` should be
  left unset to auto-detect the best available backend.

### JDK not found after setup

- Verify `java` is on PATH: `which java && java -version`
- If using Homebrew: `brew info --cask temurin@17`
- The `actions/setup-java` workflow step will also configure the path

## Files

```
tools/ci/macos/
├── .env.example    # Template for environment configuration
├── runner.sh       # Setup + run with auto-recovery
└── README.md       # This file
```
