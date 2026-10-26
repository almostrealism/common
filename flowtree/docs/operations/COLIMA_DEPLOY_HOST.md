# Moving the Controller Stack to a Service-Account Docker Daemon

Status: **planned, not yet carried out.** This is the operator checklist for the
migration; it is executed on the deploy host itself, not from a checkout elsewhere.
Placeholders: `<personal-user>` is the personal macOS account that currently owns
Docker Desktop and the stack; `<service-user>` is the dedicated service account
that will own the Colima daemon; `<deploy-host>` is the deploy machine.

## Why

The deploy job runs code delivered from GitHub. It must not execute as a
personal account. That rules out the simplest fix (run the runner as the user
who owns Docker Desktop) as anything more than a one-off test.

Docker Desktop for Mac cannot serve this: it is a per-user application whose
daemon lives in a VM tied to a logged-in GUI session. A service account with no
GUI session cannot run it, and sharing another user's socket keeps the stack
hostage to that person staying logged in.

Colima runs a Linux VM per user with no GUI dependency, so a service account
(`<service-user>`) can own a daemon outright.

## What actually moves

**No data moves.** The stack declares no named volumes — every piece of state is
a host bind mount:

```
/Users/Shared/flowtree/memory-data     ar-memory database
/Users/Shared/flowtree/controller      workstreams.yaml, notification tokens, logs
/Users/Shared/flowtree/secrets         shared-secret (HMAC signing)
/Users/Shared/flowtree/tracker         tracker database
/Users/Shared/flowtree/manager         manager-tokens.json
/Users/Shared/flowtree/manager-state   OAuth client registrations
/Users/Shared/flowtree/agent-transcripts
```

**No images move.** `rebuild.sh` builds every image from source on the host.

So the migration is not a data migration. It is: stop the containers on one
daemon, start them on another pointed at the same paths. That also means
**rollback is cheap** — Docker Desktop can bring the stack back from the same
directories.

## Host ports the stack claims

`7766`, `7780` (controller), `8010` (ar-manager), `8020` (ar-memory),
`8030` (ar-tracker). Two daemons cannot both bind these. This is the mechanism
behind the main risk below.

---

## Risks

**1. Split-brain — the one that actually bites.** If `<personal-user>`'s Docker Desktop
still has the stack when `<service-user>`'s Colima starts it, you get either port-bind
failures or, worse, two partial stacks sharing the same bind mounts. Two
ar-memory processes writing one SQLite database is corruption. The cutover must
fully stop and disable the old copy before starting the new one, and
`restart: unless-stopped` means a Docker Desktop launch at login will silently
try to bring the old one back. Verify with `docker ps` **as both users**.

**2. Colima mounts only the user's home by default.** `/Users/Shared` is
outside it, so unless mounts are configured explicitly, every bind mount above
resolves to an empty directory inside the VM. The failure mode is ugly: the
stack starts "successfully" with an empty database and no secret. Configure the
mount and verify a file written on the host is visible in a container **before**
cutting over.

**3. VM sizing.** Colima's defaults are far too small for building four images
and running the stack plus agents. Under-sizing appears as OOM-killed builds or
a wedged VM, not a clear error. Size CPU and memory generously, and size the
**disk** generously too — it is a fixed VM disk and growing it later is
disruptive.

**4. Permissions on the shared paths.** `<service-user>` needs read/write on everything
above. `secrets/shared-secret` is created `chmod 600` by `rebuild.sh` and is
likely owned by `<personal-user>` today; the controller and ar-manager both read it. Fix
ownership or group before cutover, not during.

**5. `host.docker.internal`.** Only relevant when running a local LLM
(`--with-llm`). Docker Desktop provides that name automatically; confirm
behaviour under Colima rather than assuming. `rebuild.sh` already exposes
`OLLAMA_HOST_OVERRIDE` as the escape hatch. The agent compose pins
`<deploy-host>` via `extra_hosts` to a Tailscale IP, which is a plain hosts entry
and is unaffected by the daemon change.

**6. Whatever fronts ar-manager publicly.** The reverse proxy for
`<deploy-host>`'s tailnet name targets host port 8010, which does not change —
but if that proxy is itself a container under `<personal-user>`'s daemon, it has to move
too. Check before cutover.

**7. First deploy is cold.** New daemon, no layer cache, and a cold `~/.m2` for
`<service-user>`. Expect it to be slow and do not mistake it for a hang.

---

## Sequence

Each phase ends in a state you can stop at.

### Phase 0 — prove the job works (as `<personal-user>`, temporary)

Run the deploy workflow end to end with the runner as `<personal-user>`. This separates
"the workflow is wrong" from "the new daemon is wrong", which is worth knowing
before changing the host. Do not leave the runner registered as `<personal-user>`.

### Phase 1 — prepare `<service-user>` without cutting over

Nothing here touches the running stack.

1. Install for `<service-user>`: Colima, the Docker CLI, and the Compose v2 plugin.
   Docker Desktop's compose plugin lives in `<personal-user>`'s `~/.docker/cli-plugins`
   and is **not** shared — `docker compose` will be missing for `<service-user>`
   otherwise. Also JDK 17 and Maven, because `rebuild.sh` builds before it
   composes.
2. Start Colima under `<service-user>` with explicit CPU, memory, disk, and a writable
   mount covering `/Users/Shared`.
3. Grant `<service-user>` read/write on `/Users/Shared/flowtree/**`.

Verify, as `<service-user>`, before going further:

```bash
docker info                      # reaches the service account's own daemon
docker compose version           # plugin present
docker run --rm -v /Users/Shared/flowtree:/m alpine ls /m   # mount is real
```

That last command is the one that matters — it is the check for risk 2.

### Phase 2 — cutover (one window, stack is down)

1. As `<personal-user>`: stop the stack and prevent it restarting at login
   (`docker compose ... down`, then quit Docker Desktop and disable its
   start-at-login).
2. Confirm nothing holds the ports: `lsof -nP -iTCP:7780 -iTCP:8010 -sTCP:LISTEN`.
3. As `<service-user>`, from a checkout `<service-user>` owns: `./flowtree/runtime/rebuild.sh`
   (no `--cache`; see the deploy workflow's comment on why).
4. Verify: `/api/health` on the controller, `/_health` on ar-manager, the
   documentation-corpus count inside the ar-manager container, and — the real
   test — one agent job end to end.

Rollback at any point: quit Colima, start Docker Desktop as `<personal-user>`, re-run
`rebuild.sh` there. Same bind mounts, same state.

### Phase 3 — make it survive a reboot

Colima under a service account needs a **LaunchDaemon** (runs at boot as the
named user), not a LaunchAgent (needs a GUI session — the very thing being
avoided). Confirm by rebooting the machine and checking the stack returns with
no one logged in.

### Phase 4 — hand deployment to the runner

Re-register the deploy runner as `<service-user>` with
`RUNNER_LABELS=self-hosted,macos,ar-deploy`, its own `RUNNER_NAME` and
`RUNNER_DIR`, then trigger the deploy workflow manually and watch it complete.
Only then remove `<personal-user>`'s runner registration.

---

## Open questions to settle on the machine

- Does anything else on `<deploy-host>` depend on `<personal-user>`'s Docker Desktop? If not,
  uninstalling it after Phase 3 removes the split-brain risk permanently.
- Should the CI test runners also move to `<service-user>`? Same argument applies to
  them — they run repository code too — but they do not need Docker, so it is a
  separate decision.
- Is a local LLM in use (`--with-llm`)? If not, risk 5 is moot.
