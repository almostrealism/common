# AR CI macOS Runner

Self-hosted GitHub Actions runner for macOS. Runs natively (no Docker)
as a simple shell script loop, picking up jobs whose labels it covers —
`[self-hosted, macos, ar-ci]` by default, configurable via `RUNNER_LABELS`
(see "Dedicating a Runner to One Job").

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
- **Full Xcode** (NOT just the Command Line Tools) — required for jobs that build
  the macOS app via `xcodebuild`. Install from the App Store or with
  `brew install xcodes && xcodes install --latest`. Note the install path: the
  App Store gives `/Applications/Xcode.app`, while `xcodes` gives a versioned
  `/Applications/Xcode-<version>.app`. Select the actual path:
  `sudo xcode-select -s /Applications/Xcode-<version>.app/Contents/Developer`
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

This is a LaunchAgent, so it loads when the account logs in and only then. For
a runner that has to run as an account nobody logs in as — the `worker` runner
for the native agent — that never happens; use a LaunchDaemon instead, as in
"Keeping the runner up across reboots" under "Deploying the native macOS
agent" below.

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
| `RUNNER_LABELS` | `self-hosted,macos,ar-ci` | Labels advertised to GitHub — decides which jobs this runner may take |
| `RUNNER_CPU_LIMIT` | *(unset — no limit)* | Max CPUs for jobs (requires `cpulimit`) |

### "chmod: Unable to change file mode on .../svc.sh: Operation not permitted"

Registration gets as far as `√ Settings Saved.` and then fails on a `chmod`,
and the wrapper retries every 30s without ever succeeding.

This is **ownership**, not permissions. `chmod` returns EPERM — "Operation not
permitted" — for any file the caller does not own, whatever its mode bits say;
only the owner or root may change a file's mode. Setting the directory 777
therefore does not help, which is why it looks like the permissions were
already correct.

It is also unrecoverable by retrying: `config.sh` has already written `.runner`
before it reaches the `chmod`, so each attempt clears that, re-registers, and
fails at the same place.

**The runner directory must be owned by whoever runs `runner.sh`.** There is no
way around this and no reason to want one: the account running the agent is
also the account that executes every job step, so "run as A against a directory
owned by B" has no coherent meaning — B's ownership would be the only thing B
contributed.

So do not mix them. Run the script as the account the jobs should run as, and
give that account the directory:

```bash
# as the service account
sudo -iu worker /path/to/tools/ci/macos/runner.sh ~/.runner-deploy.env
```

Testing the deploy job from a personal account does **not** require the service
account's directory. What routes the deploy job to a runner is its **labels**,
not where it lives — so a throwaway runner in your own home with
`RUNNER_LABELS=self-hosted,macos,ar-deploy` serves the same purpose:

```bash
RUNNER_DIR=/Users/<you>/actions-runner-deploytest
RUNNER_LABELS=self-hosted,macos,ar-deploy
```

`runner.sh` checks ownership before registering and stops with this guidance
rather than looping. If the other account still has a runner registered from
that directory, remove it in GitHub first.

### The runner installed into the wrong directory

`RUNNER_DIR=~/actions-runner` in `.env` does **not** mean a fixed directory. A
bare `~` is expanded by the shell to the home of whoever runs the script, so
the same `.env` resolves differently per user — running it as one account to
service another lands the runner in the wrong home, with no error. Use an
absolute path.

The startup summary now reports the resolved value and where it came from:

```
  Runner dir:   /Users/michael/actions-runner   [from /path/to/.env]
```

`[from ...env]` with an unexpected path means the value was set but expanded
elsewhere — almost always a `~`. `[from built-in default]` means the variable
was never set; check that the line is not still commented out.

The same applies to `RUNNER_LABELS`: if the summary shows
`[from built-in default]`, the runner is about to advertise the test-lane
labels regardless of what you intended.

### "Cannot configure the runner because it is already configured"

The runner directory holds a `.runner` file naming a registration that GitHub
no longer has. This is a normal end state, not corruption: the runner is
registered `--ephemeral`, so it deregisters itself after every job, and a
wrapper stopped mid-cycle leaves the local file behind. Deregistration then
fails ("Not Found") and `config.sh` refuses to configure over the leftover.

`runner.sh` now recovers from this by itself — it reports why the graceful
removal failed and clears the local state before registering. If you hit it
with an older copy of the script, or want to clear it by hand:

```bash
rm -f ~/actions-runner/.runner ~/actions-runner/.credentials*
```

Note that `config.sh` lives in the **runner directory** (`~/actions-runner` by
default), not in `tools/ci/macos`. The tool's own advice to run `./config.sh
remove` is relative to that directory, which is why it looks missing.

Deleting those files is safe: they are local state, and registration passes
`--replace`, so a registration that does still exist is taken over rather than
duplicated.

## Dedicating a Runner to One Job

GitHub schedules a job on any runner whose labels are a **superset** of the
job's `runs-on` list. Nothing else constrains it: a runner does not opt in to
particular workflows, and there is no exclusion list. So what a runner refuses
is decided entirely by the labels it **leaves out**.

That makes the isolation rule simple: to serve one job and nothing else, drop
the labels every other job asks for.

The deploy runner is the worked example. `.github/workflows/deploy.yaml` asks
for `[self-hosted, macos, ar-deploy]`, while every other macOS job in this repo
asks for `[self-hosted, macos, ar-ci]`. Configure it with:

```bash
RUNNER_LABELS=self-hosted,macos,ar-deploy
```

It then covers the deploy job's three labels, and cannot cover any `ar-ci` job
because it does not carry `ar-ci`. Deploys never queue behind a multi-hour test
lane, and the test lane never picks up a deploy.

**Do not add `ar-deploy` to an existing `ar-ci` runner instead.** Its labels
would cover both lists and it would take test jobs as well — the opposite of
what you want.

### Running both roles on one machine

The Mac that hosts the FlowTree controller stack may need to be both a test
runner and the deploy runner. That is two wrapper processes, and each needs its
own identity and its own directory — a second wrapper inheriting the default
`RUNNER_NAME` would re-register over the first (`config.sh --replace`), leaving
one runner where you wanted two.

`runner.sh` takes an env file and a runner directory as arguments for exactly
this:

```bash
# Test runner — the existing setup, unchanged.
./runner.sh

# Deploy runner — separate env file, separate directory, separate name.
cat > ~/.runner-deploy.env <<'ENV'
GITHUB_PAT=ghp_your_token_here
GITHUB_OWNER=almostrealism
GITHUB_REPO=common
RUNNER_NAME=mac-studio-deploy
RUNNER_LABELS=self-hosted,macos,ar-deploy
ENV
./runner.sh ~/.runner-deploy.env ~/actions-runner-deploy
```

Confirm the two registrations carry different labels before relying on it:

```bash
gh api repos/almostrealism/common/actions/runners \
    --jq '.runners[] | {name, status, labels: [.labels[].name]}'
```

The deploy runner additionally needs **Docker** available to the runner user
(`docker compose` v2), plus JDK 17 and Maven, because `rebuild.sh` builds the
JARs and then composes the images on that host. `.env` is read once at wrapper
start, so restart the wrapper after changing `RUNNER_LABELS` — re-registration
between jobs does not re-read it.

## Deploying the native macOS agent

`Deploy Controller Stack` has a second job, `deploy-macos-agent`, that keeps a
**native** FlowTree agent — a JVM under launchd, with Metal — on the same JARs
as the Docker pool. It runs whenever the pool is rebuilt (`redeploy_agents`, or
the `FLOWTREE_DEPLOY_AGENTS` variable) and asks for
`[self-hosted, macos, ar-deploy-agent]`.

That is a **third label and a third runner**, and the reason is the account.
The agent is a launchd service that runs as the `worker` service account, and
a redeploy swaps the JARs under `~worker/flowtree-agent` and restarts the
agent's process. Both of those need the job to *be* `worker`: only the owner
of the install directory can write it, and only the owner of a process can
signal it. The job never elevates or switches user, so:

- The runner must be started as `worker`. The Docker deploy runner
  (`ar-deploy`) is a different account — the one that administers Docker —
  and cannot do this job. Do not add `ar-deploy-agent` to it: the job would
  fail the account check below rather than install anything for it.
- The job's first step prints `Installing the native agent as <account> on
  <host>` and then fails the job if `<account>` does not match the expected
  service account (`worker` by default, overridable with the repository
  variable `FLOWTREE_MACOS_AGENT_ACCOUNT`), so a mis-registered runner is
  caught rather than silently installing in the wrong place.

What the job does **not** need is any launchd privilege, and that is
deliberate. The agent is a **LaunchDaemon** in the system domain
(`/Library/LaunchDaemons/com.almostrealism.flowtree-agent.plist`, with
`UserName` set to `worker`), registered once by an administrator. It is not a
LaunchAgent in worker's own `gui/<uid>` or `user/<uid>` domain, and the
difference matters on exactly the kind of host this runs on:

- A per-user domain exists only while the account has a login session.
  `worker` is a service account that nobody logs in as, so after a reboot
  there is no domain to load anything into until someone does.
- On a host where `worker` is only ever reached through `su - worker` from
  another user's terminal, the per-user domain that `su` creates refuses
  every bootstrap: `launchctl bootstrap user/<uid> …` answers
  `Bootstrap failed: 5: Input/output error` to worker, to root, and to root
  via `launchctl asuser`. Nothing short of a real login session (or a reboot)
  changes that, and the first version of this job — which bootstrapped a
  LaunchAgent from inside the CI job — failed on it every time.

The system domain has neither problem: root may bootstrap into it from any
session, it is present from boot with nobody logged in, and a service running
there as `worker` can be inspected (`launchctl print system/<label>`) and
signalled by `worker` without root. `KeepAlive` does the restart, so a redeploy
is "swap the JARs, `kill` the JVM, wait for the new one to connect".

### Setting up the `worker` runner

Everything below is done **as `worker`** unless it says `sudo`. A
`sudo su - worker` shell is fine for all of it — nothing here bootstraps into
worker's own launchd domain.

```bash
# as worker
mkdir -p ~/flowtree-agent

# 1. The env file the agent service will load. It lives OUTSIDE any checkout
#    because it holds the Claude Code credential.
cp /path/to/common/flowtree/runtime/agent/macos/agent.env.example ~/flowtree-agent/agent.env
$EDITOR ~/flowtree-agent/agent.env    # CLAUDE_CODE_OAUTH_TOKEN, FLOWTREE_ROOT_HOST, FLOWTREE_NODE_ID

# 2. The runner env file. The labels are what route the job here, and the
#    absolute RUNNER_DIR keeps the runner in worker's home whoever launches it.
#    Keeping it beside .env in the checkout is fine: tools/ci/.gitignore
#    ignores every *.env there.
cat > /path/to/common/tools/ci/macos/deploy-agent.env <<'ENV'
GITHUB_PAT=ghp_your_token_here
GITHUB_OWNER=almostrealism
GITHUB_REPO=common
RUNNER_NAME=mac-studio-deploy-agent
RUNNER_LABELS=self-hosted,macos,ar-deploy-agent
RUNNER_DIR=/Users/worker/actions-runner-deploy-agent
RUNNER_WORKDIR=/Users/worker/actions-runner-deploy-agent/_work
ENV

# 3. Start the runner — as worker, with that env file and directory.
/path/to/common/tools/ci/macos/runner.sh /path/to/common/tools/ci/macos/deploy-agent.env /Users/worker/actions-runner-deploy-agent
```

The startup banner should show `Labels: self-hosted,macos,ar-deploy-agent
[from .../deploy-agent.env]` and a runner directory under `/Users/worker`. If
either says `[from built-in default]`, the runner is about to advertise the
test-lane labels or install into the wrong home; fix the env file before it
registers.

The same checkout can run this runner and the `ar-deploy` one (as the Docker
account, from `.env`) at the same time: `runner.sh` keeps all of a runner's
state under its `RUNNER_DIR`, and the two carry different labels, so they
neither collide nor take each other's jobs.

The runner's own account needs, on PATH for a non-interactive shell: JDK 17,
Maven and `lsof` (ships with macOS). The **agent** it installs additionally
needs the Claude Code CLI, Node.js, git and python3 — launchd starts services
with almost no PATH, so `bin/run.sh` puts Homebrew and `~/.npm-global/bin`
ahead of it by default; set `FLOWTREE_AGENT_PATH` in `agent.env` if worker's
tools live elsewhere (a `claude` under `~/.local/bin`, for instance).

### Registering the agent daemon (once, as an administrator)

The first deploy — or `install.sh` run by hand — stages the JARs, `run.sh`
and the rendered service definition under `~worker/flowtree-agent`, then stops
with the command for this step, because worker cannot perform it:

```bash
# from a checkout of this repository that YOU own — not worker's
sudo /path/to/your/common/flowtree/runtime/agent/macos/register-daemon.sh \
    com.almostrealism.flowtree-agent \
    /Users/worker/flowtree-agent/conf/com.almostrealism.flowtree-agent.plist
```

Run it from any administrator shell; the session it is in does not matter.
Where the script comes from does: `worker` executes code it did not write
(that is what a coding-agent job is), so root must not run anything worker
can edit. `register-daemon.sh` is therefore never staged under
`~worker/flowtree-agent`, the checkout the deploy job runs from is not
trusted either, and the script refuses to run unless the file it was invoked
from and every directory on the path to it are owned by root or by the
administrator behind `sudo`, are not group- or world-writable, and are not
symlinks — a file you own inside a directory worker can write to could be
swapped before `sudo` opens it. A checkout under your own home passes; one
under `/tmp` does not. The script needs nothing beyond what ships with macOS
(`plutil`, `PlistBuddy`, `launchctl`) and sets its own PATH to the system
directories, so it works under `sudo`'s sanitised PATH and consults nothing
another account could place on yours.

"Owned by you" is checked by ownership, mode bits *and* ACLs on every path
component — macOS ACLs can grant write access with the mode bits clear, so
an `allow … write` (or delete, append, add_file, …) entry for anyone
disqualifies the path just as a group write bit does.

The plist is worker's, and it is treated that way. `register-daemon.sh`
(`flowtree/runtime/agent/macos/register-daemon.sh`) takes the service label
as its first argument — you say which service you are registering, and the
plist must carry exactly that `Label`; it cannot name some other daemon on
the host and have you boot that out and overwrite it, and the label must be
under `com.almostrealism.`, so no system service can be named at all. It
copies the plist into a fresh directory only root can enter (under
`/private/var/root` — checked to be root's alone by ownership, mode and ACL,
with any inherited ACL stripped from the new directory — not the inherited
`TMPDIR`, which sudo may have taken from your environment), lints that copy,
and checks it before root acts on it: the service must run
as the account that owns the plist (`UserName` is required and must name the
owner, `GroupName` if present must be the owner's primary group, and the
owner must not be root), and only the keys a plain service needs are
accepted. A plist can register a service that runs as whoever wrote it, and
nothing else — no more than that account could already do with a
LaunchAgent, minus the login-session requirement. It then installs the copy
under `/Library/LaunchDaemons` as root:wheel 644, bootstraps it into the
system domain, and prints the service's state and pid. When a service with
that label is already registered it boots that out first and **waits for
launchd to stop listing it** before loading the new definition — `launchctl
bootout` returns before the process is gone, and a bootstrap that landed in
that window would run two agents with the same node identity — failing
instead if the old one will not stop.

The agent starts immediately (`RunAtLoad`) on the staged JARs, and every
deploy from then on is unprivileged. The rendered plist is regenerated on each
install and compared with the registered copy; if the install directory, env
file path or account ever changes, `install.sh` fails with the same command,
rather than reporting success against a definition launchd is no longer
running.

Hosts that ran the earlier LaunchAgent version are migrated by the first
daemon-era install: it boots out `com.almostrealism.flowtree-agent` from
`gui/<uid>` and `user/<uid>` if either has it, waits for launchd to stop
listing it, and deletes
`~/Library/LaunchAgents/com.almostrealism.flowtree-agent.plist` so a later GUI
login cannot load it. If the service is still listed after the bootout —
launchd can refuse that on the same hosts whose domains refuse a bootstrap —
the install stops there rather than start the daemon beside it, and prints the
`launchctl bootout` to run by hand (with `sudo` if worker's own is refused);
two processes with the same node identity would both connect to the
controller.

### Keeping the runner up across reboots

The runner has the same problem the agent had — worker has no login session
in which a LaunchAgent could load — and the same answer. Render a LaunchDaemon
for it and register it once as an administrator. Unlike the agent's, this
definition is not generated by any script; keep it where the runner lives.
It names no `GroupName`: launchd then uses worker's primary group, which is
also the only value `register-daemon.sh` would accept, so there is nothing
to get wrong by hand:

```bash
# as worker
cat > /Users/worker/actions-runner-deploy-agent/com.almostrealism.deploy-agent-runner.plist <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.almostrealism.deploy-agent-runner</string>
    <key>UserName</key>
    <string>worker</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>/path/to/common/tools/ci/macos/runner.sh</string>
        <string>/path/to/common/tools/ci/macos/deploy-agent.env</string>
        <string>/Users/worker/actions-runner-deploy-agent</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <!-- launchd gives a daemon almost no PATH and no HOME. runner.sh
             needs java, mvn, curl and jq; the job needs git, mvn and lsof. -->
        <key>PATH</key>
        <string>/Users/worker/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
        <key>HOME</key>
        <string>/Users/worker</string>
    </dict>
    <key>WorkingDirectory</key>
    <string>/path/to/common</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>ThrottleInterval</key>
    <integer>30</integer>
    <key>StandardOutPath</key>
    <string>/Users/worker/actions-runner-deploy-agent/runner.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/worker/actions-runner-deploy-agent/runner.log</string>
</dict>
</plist>
PLIST
```

```bash
# as an administrator, from a checkout YOU own — the same script, and the
# same checks, as for the agent: the plist must run the service as worker
sudo /path/to/your/common/flowtree/runtime/agent/macos/register-daemon.sh \
    com.almostrealism.deploy-agent-runner \
    /Users/worker/actions-runner-deploy-agent/com.almostrealism.deploy-agent-runner.plist
tail -f /Users/worker/actions-runner-deploy-agent/runner.log
```

Stop it with `sudo launchctl bootout system/com.almostrealism.deploy-agent-runner`
(not `pkill`, or `KeepAlive` relaunches it), which also lets the wrapper's
cleanup trap deregister the runner from GitHub. A runner started by hand
(step 3 above) and one started this way must not run at once: they share a
`RUNNER_NAME`, and the second registration replaces the first.

### Where the agent lives, and how to check on it

`install.sh` (`flowtree/runtime/agent/macos/install.sh`) installs into
`~/flowtree-agent` by default — `lib/` (the JARs), `conf/` (`agent.properties`
and the rendered plist), `bin/run.sh`, `logs/agent.log`, and `workspace/` for
checkouts. Override the location with the repository variable
`FLOWTREE_MACOS_AGENT_HOME`, and the env file path with
`FLOWTREE_MACOS_AGENT_ENV`; both are read by the workflow and passed through.
Changing either means re-registering the daemon (`install.sh` says so).

The service label is `com.almostrealism.flowtree-agent`, in the system domain.
As worker, or anyone else:

```bash
launchctl print system/com.almostrealism.flowtree-agent | grep -E 'state|pid|last exit'
tail -f ~worker/flowtree-agent/logs/agent.log
```

The job fails unless the new process is running **and** holds a connection to
the controller port within three minutes, so a green run means the agent is
actually on the network, not merely started. A redeploy identifies the new
process by pid — it must differ from the one that was signalled — so a JVM
that ignores the restart cannot pass as the new one.

You can run `install.sh` by hand as worker from a checkout to do the same
thing outside CI — it is the whole deployment, not a helper the workflow wraps.

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

### `xcodebuild requires Xcode` / wrong developer directory

Jobs that build the macOS app invoke `xcodebuild`, which needs the **full Xcode**
app — the Command Line Tools alone are not enough. The error
`tool 'xcodebuild' requires Xcode, but active developer directory
'/Library/Developer/CommandLineTools' is a command line tools instance` means
either Xcode is not installed or `xcode-select` points at the CLT.

1. Check what is active: `xcode-select -p`
2. Confirm Xcode is installed and note its exact path: `ls -d /Applications/Xcode*.app`
   (if absent, install via the App Store, `xcodes install --latest`, or a manual
   download from developer.apple.com). `xcodes` installs a versioned
   `Xcode-<version>.app`, not a plain `Xcode.app`.
3. Point the toolchain at that exact path (one-time, system-wide):
   `sudo xcode-select -s /Applications/Xcode-<version>.app/Contents/Developer`
4. Accept the license and finish first-launch setup:
   `sudo xcodebuild -license accept && sudo xcodebuild -runFirstLaunch`
5. Verify: `xcodebuild -version`

The runner picks up the system-wide selection automatically — no restart needed.
Without sudo, set `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` in
the runner's environment instead; `xcodebuild` honors it over `xcode-select`.

### JDK not found after setup

- Verify `java` is on PATH: `which java && java -version`
- If using Homebrew: `brew info --cask temurin@17`
- The `actions/setup-java` workflow step will also configure the path

## Test Data (Sample Library)

Some benchmark tests (`AudioSceneSingleVsMultiChannelTest`,
`PdslHotPathBreakdownTest`, and other sample-dependent suites) read real audio
from `/Users/Shared/Music/Samples`. A runner **without** that directory silently
falls back to synthetic samples and reports misleading timings, so every macOS
runner must have the library present before it picks up jobs.

[`../sync-music-samples.sh`](../sync-music-samples.sh) seeds a runner from a
machine that already has the library. It lives one level up because it is not
macOS-specific — the ROCm fleet uses it too, with a different `--dest` and
`--group`. Run it from the source machine as a user whose SSH key authenticates
as the remote user:

```bash
cd tools/ci

# Preview first (transfers nothing, changes no permissions)
./sync-music-samples.sh --dry-run --host michaels-mac-mini-2 --user michael

# Then perform the real sync
./sync-music-samples.sh --host michaels-mac-mini-2 --user michael
```

It uses `rsync` over SSH (incremental, resumable, idempotent — safe to re-run to
pick up library updates), excludes macOS `.DS_Store` cruft, and then makes the
remote tree **group-readable** (`chgrp -R staff` + `chmod -R g+rX`) so the
runner's user account can read it during jobs. No `sudo` is needed:
`/Users/Shared` is world-writable (`1777`) on macOS, and every macOS user shares
the `staff` group. See `./sync-music-samples.sh --help` for all options
(`--group`, `--src`, `--dest`, `--key`, `--no-delete`).

## Files

```
tools/ci/macos/
├── .env.example            # Template for environment configuration
├── runner.sh               # Setup + run with auto-recovery
├── cpu-watcher.sh          # Enforces RUNNER_CPU_LIMIT during a job
└── README.md               # This file

tools/ci/
└── sync-music-samples.sh   # Seed the curated sample library onto any runner
```
