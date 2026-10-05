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

On a new Mac, as an **administrator** (an account with sudo — not the runner
account, and not root), from a checkout that account owns:

```bash
# 1. Configure: fill in GITHUB_PAT, GITHUB_OWNER, RUNNER_SCOPE/GITHUB_REPO
cp tools/ci/macos/.env.example tools/ci/macos/.env
chmod 600 tools/ci/macos/.env   # it holds GITHUB_PAT; install refuses a readable one
$EDITOR tools/ci/macos/.env

# 2. Install the runner as a LaunchDaemon, and the fleet monitor
tools/bin/fleet macos install --store-from michael@mac-studio
```

That is the whole setup. `install` checks everything before it changes
anything — the env file, the runner account, the tools the runner needs on
the PATH the daemon will have, directory ownership, the monitor's credential —
and reports every problem at once. It then stages `runner.sh` and the env
file into the runner account's home, registers
`com.almostrealism.ci-runner` in launchd's system domain (so it starts at
boot with nobody logged in, and restarts if it dies), waits until GitHub
lists the runner **online**, and installs the fleet metrics collector
(`tools/fleet/launchd/install.sh`) as the administrator account.

`install` (and `status`) read the env file with the administrator's
privileges, so it is refused unless the file and every directory above it
belong to you or root, are not group- or world-writable, carry no
write-granting ACL entry (macOS ACLs can grant write access with the mode
bits clear), and are not symlinks. Everything under the stage directory is
written as the runner account, never as root. The same standard keeps the
monitor's `FLEET_HOME` (default `~/fleet`), which holds its database
credential, out of the runner account's reach, and keeps an existing runner
directory free of files others can write.

The runner runs as `worker` unless you pass `--user NAME`. Every job step runs
as that account too.

`--store-from` names a host that already has the monitor's store credential,
copied once with `scp`; leave it off on a host where `~/fleet/store-url`
already exists, or pass `--no-monitor` to install the runner alone.

Day to day:

```bash
tools/bin/fleet macos status          # launchd state, idle/busy, GitHub registration, monitor
tools/bin/fleet macos logs [-f]       # the runner's log
tools/bin/fleet macos stop [--if-idle] # deregister and stop; stays stopped across reboots
tools/bin/fleet macos start
tools/bin/fleet macos restart
tools/bin/fleet macos uninstall       # remove the LaunchDaemon (keeps the runner directory)
```

**Changing the configuration** — after editing `.env`, or after a `git pull`
that changes `runner.sh` — means running `install` again. The service runs
the staged copies under `~worker/ci-runner/`, not your checkout, so neither an
edit nor a pull reaches it until then; re-installing replaces the running
service (the old one is stopped and deregistered first).

To run `runner.sh` in the foreground instead, for debugging:
`./runner.sh [env-file] [runner-dir]`. Do not run it beside the installed
service with the same runner directory — `install` refuses to, and two
wrappers would fight over one registration.

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

Pressing Ctrl+C (SIGINT) or sending SIGTERM — which is what
`launchctl bootout`, and so `fleet macos stop`, does — triggers a graceful
shutdown: the runner agent is stopped and the runner deregisters from GitHub
before the script exits.

For that to happen promptly, the agent runs in the background while the
loop `wait`s on it (bash runs a trap only once the foreground command
returns, so a foreground agent would hold the signal until the job ended),
in a process group of its own (a background command in a non-interactive
shell otherwise starts with SIGINT ignored, and the listener stops on
SIGINT), with `RUNNER_MANUALLY_TRAP_SIG=1` so the agent's own `run.sh`
turns the SIGTERM it is sent into that SIGINT.

### Environment

The runner script exports AR environment variables automatically:

```
# AR_HARDWARE_LIBS is auto-detected — do not set manually
```

`AR_HARDWARE_DRIVER` is intentionally left unset to auto-detect the best available backend.

JDK and Maven must already be installed on the system. The
`actions/setup-java` step in the workflow ensures correct PATH
configuration.

## Stopping and Restarting

`tools/bin/fleet macos stop` boots the service out of launchd. launchd sends
`runner.sh` SIGTERM; its trap stops the runner agent (which cancels a job in
progress) and deregisters the runner from GitHub before exiting. Use
`--if-idle` to stop only when no job is running — runners are ephemeral, so
waiting for the current job costs nothing. A stopped runner is also disabled
in launchd, so it stays stopped across reboots until `start`.

Avoid `pkill` against an installed runner: `KeepAlive` relaunches it at once.
Avoid `kill -9` in any case — it bypasses the deregister trap and leaves a
stale offline runner in GitHub. That is not fatal (`runner.sh` clears local
state before re-registering, and ephemeral runners are cleaned up
server-side), but a graceful stop is tidier.

> When switching a runner from repo to org scope (`RUNNER_SCOPE=org`),
> re-running `install` deregisters it from its old repo-level registration
> (the old process still holds the old env) and registers it at the org
> level. Make sure the runner group grants the relevant repositories access
> first, or jobs will queue.

## Configuration

All configuration is via the `.env` file (see `.env.example`).

| Variable | Default | Description |
|---|---|---|
| `GITHUB_PAT` | *(required)* | GitHub personal access token. The `.env` file holds it, so `fleet macos install` refuses one any account but its owner and root can read (`chmod 600 .env`, and `chmod -N` to drop any ACL) — a readable one would hand the token to every account on the host, the runner account that runs CI jobs among them |
| `RUNNER_SCOPE` | `repo` | `repo` (single repository) or `org` (shared across the org) |
| `GITHUB_OWNER` | `almostrealism` | GitHub org or user |
| `GITHUB_REPO` | `common` | Repository name (required for `repo` scope, ignored for `org`) |
| `RUNNER_NAME` | `$(hostname)-macos` | Runner display name in GitHub |
| `RUNNER_GROUP` | `Default` | Runner group |
| `RUNNER_WORKDIR` | `${RUNNER_DIR}/_work` | Job working directory. Must be absolute; `fleet macos install` refuses one under a directory any account but root and the runner's could change, one that already exists as a non-directory, or one the runner account cannot create (`runner.sh` makes it with `mkdir -p`). The `~/actions-runner/_work` in `.env.example` is this default for the default `RUNNER_DIR` |
| `RUNNER_LABELS` | `self-hosted,macos,ar-ci` | Labels advertised to GitHub — decides which jobs this runner may take |
| `RUNNER_CPU_LIMIT` | *(unset — no limit)* | Max CPUs for jobs (requires `cpulimit`) |
| `RUNNER_DIR` | `~<user>/actions-runner` | Where the runner agent is installed; must be absolute. `fleet macos install` resolves `~` to the runner account's home |
| `RUNNER_PATH` | Homebrew, `openjdk@17`, `~/.local/bin`, system dirs | PATH the LaunchDaemon gives `runner.sh` and its jobs; set it if the runner account's JDK or Maven lives elsewhere. Every entry must be absolute and writable — by mode bits or by an ACL — only by root, the runner account and the `admin` group (install refuses e.g. `/tmp/bin`, and an empty or trailing `:` entry). The same holds for each required program found on it, and for every symlink on the way to it. Set this, never `PATH` itself: `runner.sh` sources the env file after launchd sets the screened PATH, so install refuses an env file that assigns `PATH` |

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

`--instance` installs a second, independent service for exactly this — its
own launchd label (`com.almostrealism.ci-runner-NAME`), env file
(`tools/ci/macos/NAME.env`), stage directory and runner directory:

```bash
# Test runner — the default instance, from .env.
tools/bin/fleet macos install

# Deploy runner — separate env file, separate name, runs as the Docker account.
cat > tools/ci/macos/deploy.env <<'ENV'
GITHUB_PAT=ghp_your_token_here
GITHUB_OWNER=almostrealism
GITHUB_REPO=common
RUNNER_NAME=mac-studio-deploy
RUNNER_LABELS=self-hosted,macos,ar-deploy
ENV
tools/bin/fleet macos install --instance deploy --user <docker account> --no-monitor
```

Every other command takes the same `--instance deploy`. (`--no-monitor`
because the monitor is per host, not per runner, and is already installed by
the first.) `tools/ci/.gitignore` ignores every `*.env` there, so the PAT never
reaches git.

Confirm the two registrations carry different labels before relying on it:

```bash
gh api repos/almostrealism/common/actions/runners \
    --jq '.runners[] | {name, status, labels: [.labels[].name]}'
```

The deploy runner additionally needs **Docker** available to the runner user
(`docker compose` v2), plus JDK 17 and Maven, because `rebuild.sh` builds the
JARs and then composes the images on that host. Re-run `install` after
changing `RUNNER_LABELS` — the service runs a staged copy of the env file.

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

Step 1 is done **as `worker`** — a `sudo su - worker` shell is fine, nothing
here bootstraps into worker's own launchd domain. Steps 2 and 3 are done by
an administrator.

```bash
# as worker
mkdir -p ~/flowtree-agent

# 1. The env file the agent service will load. It lives OUTSIDE any checkout
#    because it holds the Claude Code credential.
cp /path/to/common/flowtree/runtime/agent/macos/agent.env.example ~/flowtree-agent/agent.env
$EDITOR ~/flowtree-agent/agent.env    # CLAUDE_CODE_OAUTH_TOKEN, FLOWTREE_ROOT_HOST, FLOWTREE_NODE_ID
```

```bash
# 2. As the administrator: the runner env file, in your checkout. The labels
#    are what route the job here.
cat > /path/to/common/tools/ci/macos/deploy-agent.env <<'ENV'
GITHUB_PAT=ghp_your_token_here
GITHUB_OWNER=almostrealism
GITHUB_REPO=common
RUNNER_NAME=mac-studio-deploy-agent
RUNNER_LABELS=self-hosted,macos,ar-deploy-agent
ENV
```

```bash
# 3. Install it as a LaunchDaemon running as worker.
tools/bin/fleet macos install --instance deploy-agent --no-monitor
```

The preflight prints the labels and the runner directory
(`/Users/worker/actions-runner-deploy-agent` by default) before it changes
anything; `install` finishes only once GitHub lists the runner online.

This runner and the `ar-deploy` one can run on the same host at once: each
instance keeps all of its state under its own `RUNNER_DIR`, and the two carry
different labels, so they neither collide nor take each other's jobs.

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
under `com.almostrealism.`, so no system service can be named at all. The
plist speaks for its owner and for nobody else, so it must be writable by
root and the owner alone: the file and every directory above it get the
same ownership, mode and ACL checks as the script's own path, with worker
in your place. **A group-writable home directory fails this** — every
member of the group could otherwise hand root a definition to run as worker
— and the fix is `sudo chmod g-w /Users/worker`. It then
copies the plist into a fresh directory only root can enter (under
`/private/var/root`, which must be root-owned and writable by root alone; the
new directory is checked to be root's alone by ownership, mode and ACL, with
any inherited ACL stripped — not the inherited
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

`fleet macos install` already registers the runner as a LaunchDaemon in the
system domain, which is what a runner for an account with no login session
needs — the same reason the agent is one. Nothing more to do.

A host set up before `fleet` existed may have a hand-written
`com.almostrealism.deploy-agent-runner` daemon serving the same runner
directory. `install` refuses to start beside it (two wrappers would fight over
one registration); retire it first:

```bash
sudo launchctl bootout system/com.almostrealism.deploy-agent-runner
sudo rm /Library/LaunchDaemons/com.almostrealism.deploy-agent-runner.plist
tools/bin/fleet macos install --instance deploy-agent --no-monitor
```

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
actually on the network, not merely started. A redeploy identifies the
process it stopped by pid *and* start time, and accepts the service's
process as new only when that identity differs — a JVM that ignores the
restart cannot pass as the new one, and a replacement that happens to be
handed the same pid number is still recognised as new. The same identity
gates every signal the restart sends, so a reused pid is never signalled.

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
- `tools/bin/fleet macos status` shows launchd's view and GitHub's;
  `tools/bin/fleet macos logs` shows registration errors
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
tools/bin/fleet             # Entry point: fleet <macos|rocm> <command>

tools/ci/macos/
├── .env.example            # Template for environment configuration
├── fleet.sh                # install/start/stop/status/logs for the LaunchDaemon
├── com.almostrealism.ci-runner.plist  # LaunchDaemon template fleet.sh renders
├── runner.sh               # Setup + run with auto-recovery
├── cpu-watcher.sh          # Enforces RUNNER_CPU_LIMIT during a job
└── README.md               # This file

tools/ci/
└── sync-music-samples.sh   # Seed the curated sample library onto any runner
```
