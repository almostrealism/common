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

# 1. Run one-time setup (installs runner agent)
chmod +x setup.sh run.sh
./setup.sh

# 2. Configure credentials
cp .env.example .env
# Edit .env — fill in GITHUB_PAT, GITHUB_OWNER, GITHUB_REPO

# 3. Start the runner
./run.sh
```

The runner registers with GitHub, picks up one job, completes it, then
re-registers for the next job (ephemeral mode in a loop).

## How It Works

### Runner Lifecycle

1. `run.sh` loads `.env` configuration
2. Requests a **registration token** from GitHub API
3. Configures the runner in **ephemeral** mode
4. Runner agent waits for a job matching `[self-hosted, macos, ar-ci]`
5. Job executes natively on the Mac
6. Runner exits after the job completes
7. Script loops back to step 2

This is equivalent to the Docker Compose `restart: unless-stopped`
behavior used by the Linux fleet, but implemented as a shell loop
since Docker is not available.

### Signal Handling

Pressing Ctrl+C (SIGINT) or sending SIGTERM triggers a graceful
shutdown: the runner deregisters from GitHub before exiting.

### Environment

The runner script exports AR environment variables automatically:

```
AR_HARDWARE_LIBS=/tmp/ar_libs/
AR_HARDWARE_DRIVER=native
```

JDK and Maven must already be installed on the system. The
`actions/setup-java` step in the workflow ensures correct PATH
configuration.

## Running in the Background

To keep the runner running after closing the terminal:

```bash
# Using nohup
nohup ./run.sh > runner.log 2>&1 &

# Or using a tmux/screen session
tmux new-session -d -s ar-runner './run.sh'
```

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
        <string>REPLACE_WITH_FULL_PATH/tools/ci/macos/run.sh</string>
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
| `GITHUB_OWNER` | `almostrealism` | GitHub org or user |
| `GITHUB_REPO` | `common` | Repository name |
| `RUNNER_NAME` | `$(hostname)-macos` | Runner display name in GitHub |
| `RUNNER_GROUP` | `Default` | Runner group |
| `RUNNER_WORKDIR` | `~/actions-runner/_work` | Job working directory |

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
- If you see `NoClassDefFoundError: PackedCollection`, verify
  `AR_HARDWARE_DRIVER=native` is set.

### JDK not found after setup

- Verify `java` is on PATH: `which java && java -version`
- If using Homebrew: `brew info --cask temurin@17`
- The `actions/setup-java` workflow step will also configure the path

## Files

```
tools/ci/macos/
├── .env.example    # Template for environment configuration
├── setup.sh        # One-time setup (downloads runner agent)
├── run.sh          # Runner loop (register, run, re-register)
└── README.md       # This file
```
