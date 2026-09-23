# Runner Fleet Monitoring — Design

## Purpose and first customer

Automated PR volume has grown to the point where the self-hosted GitHub Actions
runners are under heavy load and the operator is considering buying more
machines. There is almost no visibility into the fleet today. **The first
customer of this system is the operator's purchasing decision**, so the design
prioritises answering one question above everything else:

> Are we short of capacity, or are we using badly what we already have?

Everything here is ordered so that read-only *visibility* — and specifically the
**queue-wait and utilization** numbers — lands before any control CLI or polish.
A polished CLI that cannot tell you whether to buy a machine is the wrong thing
to build first.

This is a **design document**. It recommends a shape, states the trade-offs
behind each choice, breaks the work into agent-sized tasks, and lists the
decisions that need a human call before implementation. Where the repository
already answers a question, the file is cited.

---

## Terminology: two things are called "runner" in this repo

This is a real source of confusion and the design must not add to it.

- **GitHub Actions runner** — a process (native or containerised) registered
  with GitHub that picks up CI jobs by label. **This document is about the fleet
  of these.**
- **`AgentRunner`** — the FlowTree abstraction that selects which coding-agent
  CLI runs a job (`claude` vs `opencode`). See
  `docs/plans/PER_WORKSTREAM_RUNNERS.md` and `flowtree/agents/`. **Not this
  document.**

Throughout, "runner" means a GitHub Actions runner and "agent" means a FlowTree
coding-agent job, because on the Mac Studio the two compete for the same CPU and
telling them apart is a core requirement (see §7.3).

---

## TL;DR — recommendation at a glance

| Question | Recommendation | Confidence |
|---|---|---|
| One CLI or two? | **One** Python CLI, platform-specific *control* behind adapters | High |
| Language | **Python 3.10+** (already the tooling language; `python-tests` gate exists) | High |
| Collection model | **Push** host/runner metrics; **pull** GitHub job data | High |
| Store | **Postgres (TimescaleDB extension) in the existing compose stack**; Grafana on top | Medium — confirm vs VictoriaMetrics |
| Dashboard | **Grafana**, tailnet-only, never exposed without auth | High |
| Smallest useful deliverable | Read-only: extend the existing host monitor → central store → one dashboard with utilization + queue wait | High |
| Sampling interval | **15 s** host/runner metrics (a touch coarser than the existing 10 s) | Medium |
| Retention | 15 s raw / 14 d, 1-min rollup / 90 d, 1-hour rollup / 1 y | Medium — cheap either way |
| Collection overhead target | **< 0.5 % of one core, < 30 MB RSS** per host | Medium |
| Rollout | Additive, host-scoped, no runner restart; Mac Studio (store) first, then host-by-host | High |

Reasoning for each is in §7. Items marked "confirm" reappear in §11 (decisions
needing a human).

---

## 1. What exists today (inventory) — answering Q1

Everything below is in this repository. What is *not* in the repository (the
operator's actual host list and any ad hoc "start some runners" scripts) is
called out in §1.5.

### 1.1 The three runner fleets

There is no single "runner manager". There are three per-fleet toolkits, each
matched to a platform and a backend, plus a shared sample-library sync:

| Fleet | Path | Platform / backend | Label served | Lifecycle mechanism |
|---|---|---|---|---|
| Linux CPU | `tools/ci/docker/` | Docker Compose | `ar-ci` (linux) | `docker compose up --scale runner=N`, `restart: unless-stopped` |
| macOS GPU | `tools/ci/macos/` | Native, shell loop | `ar-ci` / `ar-deploy` / `ar-deploy-agent` (macos) | `runner.sh` ephemeral loop, optional launchd |
| Linux OpenCL | `tools/ci/rocm/` | Rootless podman + systemd Quadlet | `ar-ci-cl` (linux) | `ar-ci-cl-runner@.container` template unit; `fleet.sh` / `install-runner.sh` |

Common design across all three (verified in each script):

- **Ephemeral registration**: a runner registers, runs exactly one job,
  deregisters, and is restarted to register again (`--ephemeral`; see
  `tools/ci/macos/runner.sh:350-361`, `tools/ci/rocm/README.md:300-319`,
  `tools/ci/docker/README.md:54-63`).
- **Self-assigning names — container fleets only.** The Docker and ROCm fleets
  claim the lowest free `<prefix>-N` from the GitHub API
  (`tools/ci/rocm/README.md:236-242`, `tools/ci/docker/README.md:54-59`). The
  macOS fleet does **not** do this: `tools/ci/macos/runner.sh` defaults the
  runner name to `$(hostname)-macos` and passes it through unchanged — it never
  queries GitHub for a free suffix. The host manifest and any historical join
  on `runner_name` must treat macOS names as explicit/hostname-derived, not as
  instances of the same self-assigning scheme the container fleets use.
- **Label-based routing**: GitHub schedules a job on any runner whose labels are
  a *superset* of the job's `runs-on`; a single-purpose runner *omits* the
  labels of jobs it should ignore (`tools/ci/macos/README.md:317-342`,
  `tools/ci/macos/runner.sh:127-133`).
- **Credentials in `.env`, never in the checkout**: `.env` is gitignored;
  `install-runner.sh` copies it to `~/.config/.../runner.env` at mode 600
  (`tools/ci/rocm/README.md:266-276`).

The ROCm fleet already has the closest thing to the CLI this project wants:
**`tools/ci/rocm/fleet.sh`** exposes `start` / `stop` / `stop --if-idle` /
`status` / `logs` / `restart`, re-execs itself as the service account, and
already solves **busy detection** — a runner is "running a job" iff a
`Runner.Worker` process exists (`tools/ci/rocm/fleet.sh:104-117`). That trick is
cross-platform and is the foundation for per-runner state in §7.3.

### 1.2 There is already a host-metrics prototype

**`tools/ci/monitor/`** is a working, cross-platform host monitor and is the
single most important existing asset for this project. The design **generalises
it rather than starting over.**

What it does today (`tools/ci/monitor/ar-host-monitor.sh`,
`tools/ci/monitor/README.md`):

- Samples `ps -eo pid,pcpu,pmem,rss,comm` every `MONITOR_INTERVAL` (default
  10 s), filters to processes above a CPU/MEM threshold, adds load averages from
  `uptime`, and writes **one JSONL line per sample** to a date-stamped file
  (`ar-host-monitor.sh:105-150`).
- Cross-platform (macOS + Linux), documented launchd and systemd services
  (`README.md:136-189`).
- 14-day local retention; ~1.7 MB/day/host (`README.md:44-48`).
- Ships a query tool `ar-host-query.sh` with time/host/proc/load filters.

What it is missing for this project — the whole delta:

- **Local only.** Logs sit in `./logs` on each host; there is no central store.
- **No per-runner state** (idle/busy, current job, repo, workflow, labels).
- **No attribution** of load to runners vs agents vs everything else.
- **No GitHub job-level data**, so it cannot show queue wait.
- **No dashboard.**

Note a security-relevant property to **preserve**: it records the command
*basename* (`comm`), never the full argv (`ar-host-monitor.sh:128-134`). Full
argv can contain a registration token; keep basenames (see §7.8).

### 1.3 What runs on the Mac Studio

From `flowtree/runtime/controller/docker-compose.yml`, the Mac Studio already
runs a **Docker Compose stack** of small services:

| Service | Port | Data volume | Notes |
|---|---|---|---|
| `ar-memory` | 8020 | `/Users/Shared/flowtree/memory-data` | the "memory DB" |
| `flowtree-controller` | 7766 / 7780 | `/Users/Shared/flowtree/controller` | job control + submit API |
| `ar-tracker` | 8030 | `/Users/Shared/flowtree/tracker` | task/project tracker (Python HTTP) |
| `ar-manager` | 8010 | `/Users/Shared/flowtree/manager` | MCP bridge; bakes docs corpus |

Facts that shape the store and security decisions:

- The host already runs Docker Compose with **per-service data dirs under
  `/Users/Shared/flowtree/`** and `restart: unless-stopped`. Adding a metrics
  store + Grafana as two more compose services is the path of least resistance.
- Services speak **plain HTTP internally; TLS is a reverse proxy's job**
  (`docker-compose.yml:16-21`). Bearer-token auth via
  `AR_*_AUTH_TOKEN`/secret files is the established pattern
  (`docker-compose.yml:56-58, 100-102`).
- The stack is reachable over **Tailscale**, and ar-manager is also fronted
  by a public Cloudflare tunnel, which is the address agents are given
  (`AR_MANAGER_URL`, `docker-compose.yml`) since neither the Docker pool nor
  the native agent can see the tailnet.
- **Restarting `ar-manager` drops every in-flight agent's MCP connection**, so
  the deploy workflow drains first (`.github/CLAUDE.md:465-471`). A metrics
  store must be a *separate* compose service so its restarts never touch the
  controller or manager.

### 1.4 CI and Python conventions the new code must satisfy

- **Python lives under `tools/`** and is exercised by the `python-tests` job,
  gated on `python_changed` (any `*.py` change) —
  `.github/workflows/analysis.yaml:1338-1392`. That job runs Python **3.10**
  and discovers tests with `unittest` in specific directories:
  `tools/mcp/manager`, `tools/mcp/common`, `tools/tests`, `.claude/hooks/lib`,
  `tools/ci/agent-protection` (`analysis.yaml:1365-1384`). It installs
  `tools/mcp/requirements.txt`.
  - **Consequence for placement**: unit tests for new collector/CLI code are
    auto-discovered only if they live in one of those directories (or a new
    discover step is added, which is a workflow change — see §11). Putting the
    Python tests in **`tools/tests/`** (importing the implementation from
    wherever it lives under `tools/`) needs no CI change. There is precedent for
    a dedicated pytest step too: `agent-volume-isolation` runs
    `pytest test_validate_agent_volume_isolation.py` from `tools/ci`
    (`analysis.yaml:1425-1427`).
- **GitHub API over curl** is an established pattern:
  `tools/ci/qa-cadence.sh:87-112` pages `api.github.com` with
  `Authorization: Bearer $GITHUB_TOKEN`, `Accept: application/vnd.github+json`,
  `jq` parsing, `per_page=100`, and a BSD-vs-GNU `date` probe
  (`qa-cadence.sh:162-167`). This is the reference for the GitHub job poller.
- **No new Maven module may be created** (root `CLAUDE.md`). None of this needs
  one — it is Python, shell, compose, and Grafana config under `tools/` and the
  controller stack.
- File-length and lint gates: Checkstyle applies to **Java only**; there is no
  Java here. Keep Python files modest (prior work treats ~1500 lines as the
  soft ceiling and runs `pyflakes` by hand after refactors).

### 1.5 What is NOT in the repository

The task notes "an assortment of ad hoc 'start some runners' scripts." The three
fleet toolkits above are what is *checked in*. The design needs the operator to
supply, because it cannot be derived from the repo:

- The **actual host inventory**: hostnames, which fleet each host runs, how many
  runners per host, and the **account** each runner and each FlowTree agent runs
  as (needed for attribution — see §7.3).
- Where **credentials currently live** on each host (the `.env` locations), and
  which GitHub token(s) the poller may use.
- Any **out-of-repo scripts** the operator wants folded into the unified CLI.

---

## 2. The fleet: hosts, runners, labels — answering Q2

### 2.1 Labels, derived from the workflows

Every `runs-on` in `.github/workflows/*.yaml`:

| Label set | Where it is used | Fleet |
|---|---|---|
| `[self-hosted, linux, ar-ci]` | `test`, `test-media` (`analysis.yaml:1588, 1777`) | Docker (Linux CPU) |
| `[self-hosted, macos, ar-ci]` | `test-mac`, `test-media-mac` (`analysis.yaml:1940, 2177`); `verify-completion.yaml`; all `master-agent-dispatch.yaml` QA jobs | macOS native |
| `[self-hosted, linux, ar-ci-cl]` | `test-cl`, `test-media-cl` (`analysis.yaml:2064, 2305`) | ROCm |
| `[self-hosted, macos, ar-deploy]` | `deploy.yaml:69` controller-stack deploy | macOS native (Docker-owner account) |
| `[self-hosted, macos, ar-deploy-agent]` | `deploy.yaml:267` native agent install | macOS native (`worker` account) |
| `ubuntu-latest` | most `analysis.yaml` orchestration jobs | GitHub-hosted (not our fleet) |

The `ar-ci-cl` and `ar-deploy*` labels are deliberately distinct from `ar-ci`
so those runners do not pick up the general test lane
(`.github/CLAUDE.md:345-347, 459-463`; `tools/ci/macos/README.md:317-342`).

### 2.2 Concurrency the fleet must satisfy (a capacity anchor)

The workflow caps concurrency independently of how many runners exist, so a
*single* pipeline's demand is bounded and known:

- `test-mac`: 8 groups at `max-parallel: 3` (`analysis.yaml:1945-1947`).
- `test-media-mac`: 4 groups at `max-parallel: 2` (`.github/CLAUDE.md:392-396`).
- `test-cl`: `max-parallel: 3`; `test-media-cl`: `max-parallel: 2`
  (`tools/ci/rocm/README.md:245-251`).

So one macOS pipeline wants up to 3 concurrent `ar-ci` runners; concurrent
pipelines multiply that. **This is exactly the kind of number the dashboard must
turn from a guess into an observation** — how often demand exceeds the cap, and
how long jobs wait when it does.

### 2.3 Host inventory — partial, from incidental references

Hostnames that appear in docs/config (not authoritative, not a count):
`mac-studio` (controller host + a runner), `michaels-mac-mini-2`
(`tools/ci/macos/README.md:621`), `amd-halo` (ROCm host,
`tools/ci/rocm/README.md:241`), and unnamed Linux Docker host(s). **The real
host list and per-host runner counts must come from the operator and from the
GitHub API**, e.g.:

```bash
# Repo-scoped
gh api repos/almostrealism/common/actions/runners \
  --jq '.runners[] | {name, status, busy, os, labels: [.labels[].name]}'
# Org-scoped (rocm registers org-wide by default)
gh api orgs/almostrealism/actions/runners \
  --jq '.runners[] | {name, status, busy, labels: [.labels[].name]}'
```

The design should **populate the host table from this API + a small operator-
maintained host manifest** (which host runs which fleet, and the runner/agent
accounts), rather than hard-coding it.

---

## 3. The capacity question, made concrete

"Are we short of capacity or using it badly?" decomposes into five observable
quantities. Each becomes a dashboard panel and drives a schema requirement:

1. **Utilization per host and per runner** = busy wall-clock ÷ total wall-clock.
   Low utilization + high queue wait ⇒ a *distribution/label* problem, not a
   hardware problem. High utilization + high queue wait ⇒ genuinely short.
2. **Queue wait per lane** = time a job spends between "eligible" and
   "picked up by a runner", bucketed by lane — the `ar-*` label of the runner
   that executed it (§2.1), which is the fleet's name for the kind of work a
   runner is for. This is the headline number for the purchasing decision.
   (The implementation buckets by the executing runner's label, not the job's
   requested `runs-on:` set — see the §5.4 correction; the two coincide while
   every runner carries exactly one `ar-*` label.)
3. **Concurrency headroom per host** = how many more concurrent jobs a host
   could take before CPU / memory / disk saturates. Needs host metrics attributed
   by class (§7.3): a host that looks "busy" because of a FlowTree agent has
   different headroom than one busy with CI.
4. **Where the time in a CI run goes** = per-job step breakdown (checkout vs
   `mvn install` vs the test phase). If most wall-clock is `mvn install`, more
   runners help less than caching would.
5. **Runner vs agent vs other**, per host (the attribution requirement) — without
   it, utilization on the Mac Studio is meaningless because agent jobs and CI
   test lanes share its CPU (`flowtree/runtime/docs/agent-pool.md:1-12`).

---

## 4. Recommended architecture

```
   Each runner host (macOS / Linux / ROCm)          Mac Studio (existing compose stack)
 ┌───────────────────────────────────────┐        ┌─────────────────────────────────────┐
 │  fleet metrics agent (Python, launchd/ │        │  ingest (bearer auth, tailnet-only) │
 │  systemd) — generalises tools/ci/monitor│  push  │            │                         │
 │   • host: cpu/mem/disk/load/thermal    │───────▶│         Postgres + TimescaleDB      │
 │   • per-runner: state, current job     │ (HTTPS │            ▲        ▲                 │
 │   • attribution: runner/agent/other    │  over  │            │        │                 │
 │   • local JSONL fallback               │ tailnet)│    GitHub  │        │  Grafana        │
 └───────────────────────────────────────┘        │    poller ─┘        │  (tailnet-only) │
                                                   │  (pull api.github.com)                │
   Operator workstation                            └─────────────────────────────────────┘
 ┌───────────────────────────────────────┐
 │  one CLI (Python): list / status /     │  reads store (status/report) and drives
 │  start / stop / register / label /     │  control over SSH/Tailscale via platform
 │  capacity-report                       │  adapters (launchd / systemd / docker / podman)
 └───────────────────────────────────────┘
```

Three moving parts: a **push** metrics agent per host (an evolution of
`tools/ci/monitor`), a **pull** GitHub poller on the Mac Studio, and a
**store + Grafana** in the existing compose stack. The **CLI** is a thin client
over the store (read) and the host adapters (control).

---

## 5. Design decisions and trade-offs

### 5.1 One CLI or two, and in what language — Q3

**Recommendation: one CLI, written in Python, with platform-specific *control*
behind adapter modules.**

The read paths (`list`, `status`, `capacity-report`) are platform-independent —
they query the central store. Only the *control* verbs are platform-specific,
and irreducibly so: starting/stopping a runner is `launchctl` on macOS,
`docker compose` on the Linux CPU fleet, and `systemctl --user` + podman on the
ROCm fleet (§1.1). "One CLI vs two" is therefore a false choice: it is **one CLI
surface with three control adapters** behind a common interface. The adapters
wrap logic that already exists (`runner.sh`, `docker compose`, `fleet.sh`).

Why Python, not Go or more bash:

- It is **already the project's tooling language** (`tools/mcp`, `tools/ci`
  Python), and the `python-tests` CI gate exists, so the CLI passes gates with
  no new infrastructure (§1.4).
- **Correction — Python is NOT already guaranteed on every runner host.**
  `tools/ci/macos/runner.sh` lists Java, Maven, curl, and jq as prerequisites
  and says nothing about Python; the Docker and ROCm runner images
  (`tools/ci/docker/`, `tools/ci/rocm/`) do not install a Python interpreter
  either. A Python collector or CLI can therefore fail on a valid, currently
  working host. This design makes Python provisioning an **explicit rollout
  step**, not an assumption: each platform's installer (§7, §8 task 10) must
  either (a) install/verify `python3` as a stated prerequisite alongside the
  existing Java/Maven/curl/jq checks (macOS via Homebrew, Linux via the
  distro's package manager or the container image), or (b) package the
  collector as a self-contained runtime (e.g. a `zipapp`/PyInstaller build)
  that does not depend on a host interpreter at all. Option (a) is simpler and
  is the default; option (b) is the fallback if a host's platform team refuses
  to add a runtime dependency. Either way, the rollout script must fail loudly
  (not silently skip sampling) when the prerequisite is missing.
- The GitHub-API + join + report logic is real program logic that outgrows
  shell; bash remains fine for the thin per-host *collector loop* but not for the
  analysis.

Trade-offs, stated honestly:
- **Go** would give a single dependency-free static binary — attractive for
  pushing an agent onto a bare host. But it adds a toolchain the repo does not
  use and a second CI lane. Reach for it only if "zero runtime dependencies on
  the host" becomes a hard requirement; the hosts already have Python.
- **Keep it all in bash** (extend `fleet.sh` per fleet) — lowest friction, but
  no shared store client, no testable join logic, and it entrenches three
  divergent CLIs, which is the problem we are trying to end.

Naming (`arfleet`, `fleetctl`, …) is a bikeshed deferred to §11.

### 5.2 What the metrics agent collects, and attribution — Q (constraint)

Collected per sample (extends `tools/ci/monitor`):

- **Host**: CPU %, memory used/free, disk used/free on the runner work volume,
  load averages, and **thermal/throttling where available**. Thermal is the
  awkward one: macOS exposes it via `powermetrics`, which **needs root**; Linux
  via `/sys/class/thermal` and, on the ROCm host, `amd-smi`. Recommendation:
  sample thermal on a *coarser* interval and treat it as best-effort/optional
  where root is unavailable (see §11 for the sudo decision).
- **Per runner on the host**: state (idle/busy), and when busy the current job's
  repo / workflow / labels. State comes from the `Runner.Worker` presence test
  the ROCm fleet already uses (`fleet.sh:104-109`); the job detail comes from the
  runner's `_diag`/`_work` directory or, more reliably, is **joined in from the
  GitHub poller** by `runner_name` (the API reports the job a runner is running).
- **Attribution class**: `runner` / `agent` / `other`, so utilization means
  something on shared hosts.

**Attribution mechanism** (the core constraint): on each host, classify every
process's CPU/RSS into one of three buckets by **process tree**, not by a name
match alone, and **measure `other` directly rather than as a residual**:

- `runner` = the subtree under each runner's `Runner.Listener` (its
  `Runner.Worker` and that worker's `mvn`/`java` children).
- `agent` = the FlowTree agent: on macOS the launchd service
  `com.almostrealism.flowtree-agent` running as `worker`
  (`tools/ci/macos/README.md:462`) and its `claude`/`node`/`java` children; in
  the Docker pool, the `agent-N` containers.
- `other` = every remaining process on the host, tagged by the same walk, not
  computed by subtracting two aggregates from a host total.

**Why not `other = total − runner − agent` (corrected from an earlier draft):**
host CPU% (from `uptime`/`vm_stat`-style host counters) and summed per-process
`ps %cpu` are not guaranteed to share a denominator or sampling window — a
residual computed by subtracting one from the other can come out negative or
otherwise misleading. Summing per-process RSS is a second, independent problem:
shared library and shared-memory pages are double-counted across processes, so
"runner RSS + agent RSS + other RSS" does not reconstruct host memory used
either. The fix is to make every class a **direct measurement**, not an
arithmetic difference: walk the full process list once, tag every PID
`runner`/`agent`/`other`, and sum each class's own processes independently —
`other` is then just "everything not tagged `runner` or `agent`," with the same
accounting basis (and the same double-counting caveat) as the other two
classes, not a derived correction term. Where the host total and the sum of
classified processes disagree (kernel threads, zombie processes, `ps` sampling
races), report the discrepancy as its own diagnostic field rather than folding
it silently into `other`.

To make this a pure post-processing step, the collector must additionally record
**`ppid` and the owning user** per process (the current monitor records neither).
On Linux, the **cgroup path** gives clean per-container attribution for free and
should be preferred there.

**macOS caveat: the Docker pool's `agent-N` containers are invisible to a
native host collector.** On the Mac Studio, `agent-N` runs inside Docker
Desktop's Linux VM; a native macOS process lists its own host's processes and
cannot see the VM's cgroups or the `claude`/`node`/`java` children running
inside it (`ps` on the host shows only the VM's own hypervisor process, not
what runs inside it). Only the launchd-based FlowTree agent
(`com.almostrealism.flowtree-agent`) is directly visible via host `ps`. The
collector must therefore attribute the containerised `agent-N` class through
the **Docker API/`docker stats`** (reachable from the macOS host through the
Docker Desktop CLI socket, which already reports clean per-container CPU/RSS)
rather than through the host process tree — this is a *different* collection
path for the same `agent` class, not an extension of the `ps`-based walk used
for `runner` and the native agent. A design that assumes one `ps`-based walk
covers all three classes on every host will silently omit Docker-pool agent
load on macOS and misstate capacity there; §12 tracks validating this against
a real Mac Studio.

macOS caveat worth a spike: multiple `ar-ci` runners can share one `$HOME` and
run as the **same account** (`analysis.yaml:1961-1971` isolates only the Maven
repo, not the account), so account-based attribution cannot separate runner from
runner — the process tree from each `Runner.Listener` is the authoritative
signal, and reparented children are the edge case to validate on a real host
(§12).

### 5.3 Push or pull — Q5

**Recommendation: push for host/runner metrics; pull for GitHub job data.**

- **Host/runner metrics → push.** The hosts under load are the ones behind
  Tailscale, and they already *initiate* outbound connections (runners register
  outbound to GitHub; FlowTree agents connect outbound to the controller). A push
  agent is a two-line evolution of the existing monitor (write JSONL → also POST
  a batch). Pull (Prometheus-style scraping) would require every host to expose a
  port and the centre to hold the authoritative host list and reach *into* each
  host — inverting the trust direction the rest of the system uses. Push also
  degrades gracefully: keep the local JSONL as a fallback buffer when the centre
  is unreachable.
- **GitHub job data → pull.** The data lives in the GitHub API; a periodic poller
  on the Mac Studio (the `qa-cadence.sh` curl/jq pattern) is simplest and needs
  no ingress. Webhooks would give lower latency but require a public,
  authenticated endpoint and secret management — unjustified for a purchasing
  decision that reads hours/days of history. Note webhooks as a later latency
  optimisation, not part of the MVP.

### 5.4 GitHub job-level data and queue wait

The poller calls (repo- or org-scoped — a §11 decision):

```
GET /repos/{owner}/{repo}/actions/runs?per_page=100        # recent runs
GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs        # per-run jobs
```

Per-job fields to persist: `id`, `run_id`, `name`, `status`, `conclusion`,
`created_at`, `started_at`, `completed_at`, `labels[]` (see the correction
below — this is the *executing runner's* label set, not the workflow's
requested `runs-on` set), `runner_id`, `runner_name`, `runner_group_name`,
and `steps[]` (each with `started_at`/`completed_at`). From these:

- **`pre_start_latency` ≈ `started_at − created_at`**, bucketed by `labels[]`
  (the executing runner's actual labels, not the requested `runs-on` set —
  see the label-semantics correction below). This is **not** the same thing
  as runner-availability queue wait, and the design must not conflate the
  two — see the queue-wait correction below.
- **Utilization** from `[started_at, completed_at]` busy windows per
  `runner_name`, joined to host metrics via the host manifest.
- **CI run time breakdown** from `steps[]` (checkout / build / test).

**Correction: `started_at − created_at` is not a queue-wait measurement for a
dependent job, and Phase A must not present it as the headline number
unqualified.** For a job gated behind `needs:`, that interval includes time
blocked on upstream jobs, not only time waiting for a free runner — so the raw
value is **pre-start latency**, not queue wait, and the two are named
differently in the schema and every panel that shows them. The Phase A poller
and dashboard MUST do one of the following before the queue-wait panel is
treated as authoritative for the purchasing decision, not merely note it as a
caveat to revisit later:
  1. **Restrict the label-level queue-wait metric to entry-point jobs** — jobs
     whose workflow-run job graph shows no `needs` dependency — where
     `pre_start_latency` and runner-availability wait coincide; or
  2. **Subtract the completion time of the job's last dependency** (derivable
     from the run's job graph, persisted alongside `job_event`) to compute a
     dependency-adjusted `queue_wait` field distinct from `pre_start_latency`.
Option 1 is the simpler Phase A implementation and is what the poller and
schema in this document assume by default (`job_event` carries both
`pre_start_latency` and an `is_entry_point` flag); option 2 is the more precise
follow-up. The exact `created_at` semantics of the workflow-job object should
still be confirmed against live API responses on this repo before either panel
is treated as authoritative (§12). Rate limits are a non-issue at this scale
(5000 req/hr authenticated; a few dozen requests per poll every 5–15 min).

**Correction — `labels[]` as persisted is the executing runner's actual
label set, not the workflow's requested `runs-on:` set, and this section
must not be read as saying otherwise.** The Phase A implementation
(`tools/fleet/github_poller.py`, documented in `tools/fleet/README.md`)
deliberately persists `job.get("labels")` from the workflow-jobs API
response — the labels of the runner that actually executed the job — because
computing the *requested* set would require parsing the run's workflow YAML
and matching each job's API `name` (a `name:` override or matrix-expanded
label, not a YAML key) back to its `runs-on:` declaration, which the poller
does not do. A runner can carry extra/custom labels beyond what a job asked
for, so grouping `pre_start_latency`/`queue_wait` by this column measures
**actual-runner-label demand** ("jobs that happened to land on a runner
carrying this exact label set"), not **per-`runs-on` demand** ("jobs that
requested this label set"). The two coincide only when every runner in the
fleet carries exactly the labels its `runs-on:` lanes expect and nothing
else — not guaranteed, and not validated here. A consumer of the
label-level latency/queue-wait panels (§3 item 2, the Phase B
`capacity-report`) must treat the `labels` column under this semantics
until one of the following lands: (a) a workflow-YAML-derived mapping from
job to its declared `runs-on:` set, persisted as a separate column so the
two demand signals are never conflated, or (b) the panels and this document
are relabelled throughout to say "executing-runner-label demand" rather
than "requested-label demand."

### 5.5 The store — Q4

**Recommendation: a single Postgres instance with the TimescaleDB extension,
added to `flowtree/runtime/controller/docker-compose.yml` (or a sibling compose
file), with Grafana reading it directly.** Mark the final choice as a human
decision (§11); the runner-up is VictoriaMetrics + Grafana.

The data has two shapes: (a) numeric **time-series** (host/runner samples) and
(b) **event/interval records** (GitHub jobs with queued/started/completed and
label sets). The capacity answer is fundamentally a **join** of (b) against (a),
per label and per host.

Why Postgres/Timescale first:
- **The join is SQL.** Queue wait per label joined to host utilization is a
  couple of queries in one store. A pure TSDB (Prometheus/VictoriaMetrics)
  models (a) beautifully and (b) awkwardly, so you would end up bolting a
  relational store beside it anyway.
- **It fits the host.** The Mac Studio already runs Docker Compose services with
  data dirs under `/Users/Shared/flowtree/` (§1.3); this is one more service
  beside `ar-memory`/`ar-tracker`, restart-isolated from the controller.
- **Grafana's Postgres datasource is first-class**, and TimescaleDB adds
  hypertables, native compression, and **continuous aggregates** that implement
  the rollups in §5.8 without a cron job.
- **The fleet is small.** A handful of hosts and tens of runners does not need a
  cluster-grade TSDB; one Postgres is less to operate.

Trade-offs:
- **VictoriaMetrics + Grafana** — excellent for (a): cheap disk, push via Influx
  line protocol, single binary, long cheap retention. If the operator already
  favours the Prometheus/VM ecosystem, this is a fine substitute for the metrics
  half; you would keep job events in a small relational table alongside. Its
  weakness is exactly (b), the join we care about most.
- **Plain Postgres without Timescale** — fully adequate at this scale; you write
  the rollup queries/materialised views yourself. Timescale is a convenience, not
  a requirement.
- **SQLite + an exporter** — tempting for its zero-ops feel and matching
  `ar-tracker`'s style, but concurrent push writers + Grafana + rollups push past
  where SQLite is comfortable. Not recommended as the central store.

### 5.6 The dashboard — Q4 / Goal 4

**Recommendation: Grafana**, run as a compose service on the Mac Studio, reading
the store directly. It fits: it is built for time-series panels, has native
Postgres/Timescale (and VictoriaMetrics) datasources, and does alerting if we
later want "queue wait > X". The four capacity panels of §3 map directly to
Grafana panels. The alternative — a hand-built static dashboard — costs more to
build and maintain and buys nothing here.

**Auth is mandatory** (a task constraint): Grafana must not be exposed without
auth. Bind it to the **tailnet only** (mirroring how the controller stack is
reached), or, if remote access is needed, put it behind Tailscale Funnel +
Grafana's own auth/OAuth. Never publish it on a public port. (Reverse-proxy
choice is a §11 decision.) **Correction — "bind to the tailnet" must be an
explicit bind, not an aspiration**: see §5.7 below; the existing compose
services' `ports:` mappings (e.g. `ar-memory`'s `"${AR_MEMORY_PORT:-8020}:8020"`)
publish on every interface by default, which is exactly what the new metrics
services must NOT copy unmodified.

### 5.7 Security — Q (constraint)

- **Registration tokens and GitHub credentials never touch the metrics DB or the
  logs.** The DB stores only non-secret operational data: counts, timings,
  states, label strings, job ids, host names. Credentials stay where they already
  live — per-host runner `.env` at mode 600 outside the checkout (§1.1) — and the
  poller's single GitHub token lives in the Mac Studio secrets dir
  (`/Users/Shared/flowtree/secrets`, mounted read-only, as the stack already does
  — `docker-compose.yml:75, 130`), read at runtime.
- **Preserve `comm` basenames; never record full argv** in samples (§1.2): argv
  can carry a token (a runner registration command line), so recording it would
  leak secrets into the store. This is a design invariant, not a nicety.
- **Transport, corrected — an explicit bind and fail-closed auth, not the
  existing services' pattern.** The existing compose services publish their
  ports with no bind address (`"${AR_MEMORY_PORT:-8020}:8020"` maps to every
  interface on the host, and `AR_*_AUTH_TOKEN` defaults to the **empty
  string** — `AR_MEMORY_AUTH_TOKEN=${AR_MEMORY_AUTH_TOKEN:-}` — so an operator
  who forgets to set it gets an unauthenticated, non-tailnet-scoped service
  that happens to work because the host's own firewall/Tailscale ACLs are
  doing the real enforcement out of band). The new ingest endpoint, DB port,
  and Grafana port must not rely on that same implicit safety net:
  - Bind the published port to the **tailnet interface's address** explicitly
    in the compose port mapping (e.g. `"${TAILSCALE_IP}:8443:8443"`, not a
    bare `"8443:8443"`), so the service is unreachable from any other
    interface even if the host firewall is misconfigured.
  - The ingest process MUST **refuse to start** — exit non-zero at startup,
    not silently accept unauthenticated requests — when its bearer-token
    environment variable is unset or empty. This inverts the existing
    `AR_*_AUTH_TOKEN=${VAR:-}` default-to-empty-and-run pattern deliberately;
    do not copy that pattern for the new services.
- **Least privilege for the poller token**: read-only Actions scope; prefer a
  fine-grained PAT or a GitHub App over a classic `admin:org` PAT. Note the
  standing warning that self-hosted runners on a **public** repo can run
  fork-PR code (`tools/ci/rocm/README.md:330-335`).
- **Correction: "outbound-only, no inbound execution path" does not, by
  itself, protect the collector's push token from fork-PR code running on the
  same host/account.** A malicious job checked out from a fork PR executes
  with the same OS-level permissions as the runner's own account; if the
  collector's bearer token is readable by that account (an environment
  variable the shell inherits, or a token file the job's user can open), the
  fork-PR job can read or exfiltrate it and forge metrics, regardless of which
  direction the *collector's own* connections point. The mitigation is an
  **isolation boundary between the token and the job's execution context**,
  not the outbound-only property:
  - Run the collector under a **separate OS identity** from the runner/job
    process where the platform allows it (a dedicated service account on
    Linux/systemd; a separate launchd service identity on macOS), with the
    token file mode `600` and owned by that identity — mirroring how runner
    `.env` files are already kept outside the checkout (§1.1).
  - Where a separate identity is not available today (the container fleets
    run the collector, the runner, and the job under converging container
    boundaries), scope the token to the **narrowest possible capability**
    (this ingest endpoint only, revocable, short-lived if the transport
    supports rotation) so a leak's blast radius is bounded to forged metrics,
    never to GitHub or infrastructure credentials.
  - This is tracked as an explicit residual risk, not a solved problem, until
    validated per host in §12; the design must not claim the exposure is
    unwidened without stating the mitigation above.

### 5.8 Retention, resolution, and disk on the Mac Studio — Q7

Proposed tiers (all tunable — see §11):

| Tier | Resolution | Retention | Purpose |
|---|---|---|---|
| Raw host/runner | 15 s | 14 days | incident correlation, spot checks |
| Rollup | 1 minute | 90 days | utilization/headroom trends |
| Rollup | 1 hour | 1 year | long-horizon capacity trend |
| GitHub job events | per job | 1–2 years | queue wait / run-time history |

Disk estimate (arithmetic shown so it can be checked; **all inputs are
estimates pending §1.5 data**). Assume ~6 hosts, ~8 stored rows per 15 s sample
(one host row + per-runner rows + three class rows):

- 15 s ⇒ 5,760 samples/day/host × 8 rows ≈ 46 k rows/day/host.
- At ~100 B/row incl. indexes ≈ ~5 MB/day/host raw ⇒ ~30 MB/day fleet-wide ⇒
  **~0.4 GB per 14-day raw window**. Timescale compression cuts this several-fold.
- Rollups are far smaller; the 1-min/90-day and 1-hour/1-year tiers together are
  a few hundred MB.
- Job events: even 500 jobs/day × ~500 B ≈ 0.25 MB/day ⇒ **< 200 MB over 2
  years**.

**Total is a few GB on the Mac Studio** — negligible against its disk. Disk is
not the constraint; resolution/retention can be tuned freely for signal quality.

### 5.9 Collection overhead — Q (constraint)

An agent that eats CPU on a loaded runner host defeats the purpose. **Target:
< 0.5 % of one core average and < 30 MB RSS per host**, one short sample per
interval. The existing monitor (a `ps` + `jq` loop at 10 s) is already well under
this. To stay there: take one `ps` snapshot per interval, read `/proc` or the
handful of host counters cheaply, sample **thermal on a coarser sub-interval**
(`powermetrics` in particular is not free), batch pushes (e.g. every 60 s, not
every sample), and never shell out per process. Default sample interval **15 s**
(a touch coarser than the current 10 s to leave headroom); expose it as a knob.

---

## 6. The smallest deliverable that answers the capacity question — Q6

Read-only visibility before any control command. The MVP is the shortest path
from "no data" to "the operator can see queue wait and utilization":

1. **Store + Grafana** stood up in the compose stack (schema for host samples,
   runner state, and job events).
2. **Metrics agent** = `tools/ci/monitor` generalised to emit host + per-runner +
   per-class samples and **push** them (local JSONL retained as fallback).
   Deployed to the Mac Studio first.
3. **GitHub poller** on the Mac Studio filling the job-events table.
4. **One dashboard** with the two headline panels: **utilization per host/runner**
   and **queue wait per label**, plus concurrency headroom and CI run-time
   breakdown as they come online.
5. **CLI read verbs only**: `list` (runners on a host: name, labels, state,
   current job, version) and `status` (host + runner utilization), reading the
   store.

No start/stop/register/label yet. This is enough to answer "short, or badly
used?" Everything after it is convenience and control.

---

## 7. Rollout without disrupting in-flight CI — Q8

The metrics agent is **additive, read-only, and host-scoped** — it runs *beside*
runners, never inside them, and never touches the runner lifecycle — so deploying
it cannot disrupt a running job. Sequence:

1. **Mac Studio first**: it is the store host and already runs compose; stand up
   Postgres + Grafana + the poller there, and run the first metrics agent there
   (it is also the hardest attribution case, so it validates §5.2 early).
2. **Host-by-host** for the rest, via the existing service conventions: launchd
   on macOS (the monitor already documents the plist —
   `tools/ci/monitor/README.md:136-169`), systemd on Linux, and a systemd user
   service / podman sidecar on the ROCm host. No runner restart is required at
   any point.
3. **Control verbs last.** When start/stop/restart land, they must be
   **`--if-idle`-aware** — the ROCm `fleet.sh stop --if-idle` already models the
   correct behaviour (`fleet.sh:134-155`): never cancel an in-flight job unless
   explicitly told to. Because runners are ephemeral, "wait for idle, then act"
   costs only a short delay.

---

## 8. Task breakdown (agent-sized, roughly in dependency order)

Each is intended to be independently reviewable and mostly independently
mergeable. They map onto the suggested ordering in the brief, refined by what the
repo already provides.

**Implementation status.** The pieces of tasks 1, 2, 5, and 6 that do not
depend on the §9 human decisions (host inventory, store-engine confirmation,
GitHub scope, Grafana exposure) have a first implementation under
`tools/fleet/` (see that directory's `README.md`), tested in
`tools/tests/test_fleet_*.py`: process-tree attribution with the corrected
non-residual `other` class, a `sqlite3`-backed version of the Appendix B
schema with the idempotency keys this review added, a GitHub poller that
computes the corrected `pre_start_latency`/`queue_wait` distinction and a
poll-cycle entry point (`poll_and_store`) that persists `job_event`/
`job_step` rows into the store, and the two read-only CLI verbs. The poller
does not yet parse `needs:` from the workflow YAML, so `queue_wait_seconds`
is populated only for the entry-point case (§5.4 option 1) — the
dependency-adjusted calculation (option 2) needs that parsing as a follow-up.

A second round added what the first deferred to a live store: `store.py` runs
on Postgres as well as sqlite (one implementation, `FleetStore.from_url`);
the collector writes each sample to the store directly over the tailnet
(`--store-url-file`), reconnecting after a database restart, and filters the
JSONL fallback to the attributed processes plus busy `other` ones (unfiltered,
a sample on the Mac Studio was ~100 KB — hundreds of MB a day); the poller has
a scheduled entry point (`python -m tools.fleet.github_poller`); `fleet-db`
and `fleet-grafana` are services in the controller compose stack, bound to
the tailnet address with file-based credentials `rebuild.sh` generates; the
capacity dashboard is provisioned from
`flowtree/runtime/controller/grafana/`; `tools/fleet/launchd/` holds the
LaunchDaemon templates, `render.sh` and a one-command `install.sh` for macOS
hosts; and `tools/fleet/systemd/` holds the unit template and `install.sh`
for the Linux runner hosts, where the collector runs on the host as a
dedicated `fleet` system account (task 10's rollout, for the collector).
The direct database connection is
the "short way" for task 3 — no ingest service exists, and the credential
isolation it requires is documented in `tools/fleet/README.md`.

A third round made the dashboard answer §3's question the way it was posed.
The poller now resolves each job's `needs:` from the run's workflow file
(`tools/fleet/workflow_graph.py`), so `is_entry_point` is set and
`queue_wait_seconds` is measured for dependent jobs as well — §5.4's option 2,
eligibility being the last dependency's completion — with an honest `NULL`
whenever a job's display name cannot be matched to its YAML key. It also
writes `runner_state` every cycle from the runners API (repository- and
org-level registrations; busy / idle / offline), which is the denominator the
capacity question needs; `host` is `''` there until a host manifest exists.
Both `job_event` and `runner_state` carry `lane` (the `ar-*` label — the
kind of work a runner is for) and `platform`, so the dashboard groups by the
fleet's own lanes rather than by raw label sets, and excludes GitHub-hosted
jobs throughout. The dashboard itself now leads with wait-for-a-runner against
runners busy / idle / offline per lane (the §3 two-by-two), shows fleet CPU by
class as one stacked sum, host memory and disk as a share of the host's total,
and utilization and step time as bars rather than tables. Not yet
implemented: the host manifest (`runner_state.host`, and joining a runner to
its host's samples), process-tree runner-state detection on the host itself
(task 4's other half), Docker-API attribution for a virtualized container
runtime's processes, and every control verb.

**Phase A — read-only visibility (the MVP, §6):**

1. **Store service + schema.** Add Postgres(+TimescaleDB) and Grafana to the
   controller compose stack (or a sibling compose file), tailnet-only, bearer
   auth, data dir under `/Users/Shared/flowtree/`. Define tables: `host_sample`,
   `runner_state`, `job_event`, `job_step`, plus rollup continuous aggregates.
   Docs. *No dependency.*
2. **Metrics agent (host + attribution).** Generalise `tools/ci/monitor` into a
   collector that records host metrics **plus `ppid` + owning user** per process
   and computes the runner/agent/other split (§5.2); keep local JSONL fallback;
   keep `comm` basenames. Unit tests for the parser + attribution in
   `tools/tests/`. *No dependency (can emit JSONL before the store exists).*
3. **Push transport.** Collector batches and POSTs to the ingest endpoint with
   bearer auth; retry/backpressure; never log tokens. **Retries make this
   transport at-least-once, so every batched row carries the natural key
   declared in Appendix B and the ingest endpoint upserts (`INSERT ...
   ON CONFLICT DO NOTHING`/`DO UPDATE`) rather than plain-inserts — a retried
   batch after an ingest-side timeout must not double-count CPU/RSS in the
   rollups.** *Depends on 1.*
4. **Runner-state detection.** Cross-platform idle/busy + current-job via
   `Runner.Worker` presence and process tree; map `runner_name → host` via an
   operator manifest. *Depends on 2.*
5. **GitHub job poller.** Pull runs+jobs, compute `pre_start_latency` and
   `is_entry_point` per §5.4's corrected definition, per-label aggregates, and
   step breakdown; reuse the `qa-cadence.sh` curl/jq/date patterns; handle
   pagination + rate limits. **The poller re-reads recent runs/jobs every
   cycle, so `job_event`/`job_step` upserts on the primary/unique keys in
   Appendix B — a naive insert would duplicate events and inflate queue and
   utilization aggregates.** Tests in `tools/tests/`. *Depends on 1.*
6. **CLI read verbs.** `list` and `status` reading the store (Python). *Depends
   on 1; useful once 3/5 populate data.*
7. **Grafana dashboards.** Utilization per host/runner, queue wait per label,
   concurrency headroom per host, CI run-time breakdown. *Depends on 3, 5.*

**Phase B — the capacity report:**

8. **`capacity-report` command.** Given observed data, quantify where the fleet
   is actually short and estimate what another machine would buy (e.g. projected
   queue-wait reduction at the observed arrival rate). *Depends on 5, 7.*

**Phase C — control and packaging:**

9. **Control verbs** — `start` / `stop` / `restart` / `register` / `deregister`
   / `label` behind platform adapters wrapping `runner.sh` (macOS),
   `docker compose` (Docker), and `fleet.sh`/podman (ROCm); all `--if-idle`
   safe. *Depends on 4.*
10. **Installers + docs + host-by-host rollout** following the
    `tools/ci/macos` and `tools/ci/rocm` conventions (launchd/systemd/Quadlet).
    *Depends on 2, 9.*

---

## 9. Decisions that need a human before implementation

1. **Host inventory & accounts (blocking §5.2 attribution and §2.3).** The
   authoritative host list, per-host fleet type and runner count, and the
   account each runner and each FlowTree agent runs as. Only the operator has
   this.
2. **Store engine**: Postgres/TimescaleDB (recommended) vs VictoriaMetrics + a
   small relational table. Confirm.
3. **GitHub data source**: repo-scoped vs org-scoped polling; a single
   read-only fine-grained PAT vs a GitHub App; and whether to add webhooks later.
4. **macOS thermal via sudo**: may the metrics agent run `powermetrics` with
   root for thermal/throttling data? If not, thermal is degraded on macOS
   (host CPU/mem/disk/load are unaffected).
5. **Out-of-repo scripts**: are there ad hoc "start some runners" scripts (§1.5)
   the CLI should absorb, and where do their credentials live?
6. **Grafana exposure**: tailnet-only vs Tailscale Funnel + OAuth.
7. **Retention/resolution** (§5.8): confirm the tiers and the default 15 s
   interval; disk is not the constraint, signal quality and how far back the
   purchasing analysis must look are.
8. **CLI name**, and whether the code should eventually move out of
   `tools/fleet/` (its first-implementation home — chosen over `tools/ci/fleet/`
   because `tools/ci/` is agent-locked outside a `ci/...` branch, §1.4). Its
   Python tests live in `tools/tests/`, so no `python-tests` job change was
   needed.
9. **Confirm no new Maven module is implied** (root `CLAUDE.md` forbids agents
   creating one). The design intends none; flag immediately if any task appears
   to need one.

---

## 10. What I am unsure about (stated plainly)

- **GitHub queue-wait semantics.** Whether `started_at − created_at` cleanly
  isolates runner-availability wait from `needs:` dependency wait needs
  validation against live API responses for this repo before the headline panel
  is trusted (§5.4). It may require deriving each job's eligibility time from its
  dependencies, or restricting the label-level metric to entry-point jobs.
- **macOS per-runner attribution.** Whether the process tree from each
  `Runner.Listener` fully separates runner-from-runner and runner-from-agent in
  all cases (reparented children, shared `$HOME`/account) needs a spike on a real
  Mac Studio (§5.2).
- **Fleet size and job volume.** Every sizing number (disk, overhead headroom,
  concurrency) is an estimate pending the operator's inventory (§1.5, §9.1). They
  are all comfortably within a Mac Studio's resources under any plausible fleet
  size, so the *conclusions* are robust even though the *numbers* are estimates.
- **Thermal availability** differs per host and per permission model (§5.9); it
  is best-effort, not guaranteed, on macOS without sudo.
- **Greenfield assumption.** `grep -ril 'runner fleet' docs/plans/` finds only
  this file, and earlier commits touching a `*RUNNER*`-named plan document
  (e.g. the AMD/ROCm runner setup plan) are about installing an individual
  fleet's runner, not about fleet-wide monitoring. Both checks are repeatable
  by any maintainer against the checked-in history, independent of any
  particular tool session.

---

## Appendix A — GitHub workflow-job fields used

From `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs` (`.jobs[]`):
`id`, `run_id`, `name`, `status`, `conclusion`, `created_at`, `started_at`,
`completed_at`, `labels` (the *executing runner's* actual label set, not the
workflow's requested `runs-on:` set — see §5.4's label-semantics
correction), `runner_id`, `runner_name`, `runner_group_name`, `steps[]`
(`name`, `status`, `conclusion`, `number`, `started_at`, `completed_at`).
Registered-runner state from
`GET /repos/{owner}/{repo}/actions/runners` (and the org variant):
`name`, `os`, `status` (`online`/`offline`), `busy`, `labels[]`.

## Appendix B — proposed tables (sketch, not final)

**Every table below declares its natural key explicitly, because the push
transport (§8 task 3) is at-least-once and the GitHub poller (§8 task 5)
re-reads the same runs/jobs every cycle — without a declared key, a retried
push or a repeated poll duplicates rows and inflates every rollup the
purchasing decision depends on.**

```
host_sample(ts, host, cpu_pct, mem_used_mb, mem_total_mb, disk_used_gb,
            disk_total_gb, load1, load5, load15, thermal_c, throttled,
            PRIMARY KEY (ts, host))
class_sample(ts, host, class /*runner|agent|other*/, cpu_pct, rss_mb,
             PRIMARY KEY (ts, host, class))
runner_state(ts, host, runner_name, labels,
             lane, platform /* two projections of labels, matching
             job_event's own lane/platform below */,
             state /*idle|busy*/,
             repo /* part of the key alongside host/runner_name: a
             runner's name is unique only within its GitHub registration
             scope, not across scopes */, workflow, job_id, agent_version,
             PRIMARY KEY (ts, host, runner_name, repo))
job_event(job_id PRIMARY KEY, run_id, repo, name, labels,
          lane, platform /* two projections of labels, so a dashboard
          groups by a short, stable key instead of parsing the JSON label
          set in every panel */, created_at,
          started_at, completed_at, status, conclusion, runner_name,
          runner_group, runner_id /* the API's own stable identity for the
          executing runner, unique across registration scopes unlike
          runner_name alone — disambiguates per-runner utilization when a
          repo-scoped and an org-scoped runner share a name */,
          pre_start_latency_seconds, is_entry_point,
          queue_wait_seconds /* nullable; populated only when is_entry_point
          or a dependency-adjusted value has been derived, per §5.4 */)
job_step(job_id, number, name, started_at, completed_at, conclusion,
         UNIQUE (job_id, number))
```

Every ingest path upserts on the declared key
(`INSERT ... ON CONFLICT (...) DO UPDATE`, or the SQLite/Postgres equivalent)
rather than plain-inserting, per the corrections in §8 tasks 3 and 5.

Rollups (`*_1m`, `*_1h`) are continuous aggregates over `host_sample` /
`class_sample`. No table holds a credential of any kind (§5.7).
