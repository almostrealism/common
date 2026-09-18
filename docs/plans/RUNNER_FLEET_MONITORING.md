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
- **Self-assigning names**: containers claim the lowest free `<prefix>-N` from
  the GitHub API (`tools/ci/rocm/README.md:236-242`,
  `tools/ci/docker/README.md:54-59`).
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
- The stack is reachable over **Tailscale**
  (`AR_MANAGER_URL=https://mac-studio.taild0f87.ts.net`,
  `docker-compose.yml:79`).
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
2. **Queue wait per runner label** = time a job spends between "eligible" and
   "picked up by a runner", bucketed by its `runs-on` label set. This is the
   headline number for the purchasing decision.
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
- Python 3.10+ is present on every host (the runners require it; the monitor's
  companion tooling assumes it).
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
match alone:

- `runner` = the subtree under each runner's `Runner.Listener` (its
  `Runner.Worker` and that worker's `mvn`/`java` children).
- `agent` = the FlowTree agent: on macOS the launchd service
  `com.almostrealism.flowtree-agent` running as `worker`
  (`tools/ci/macos/README.md:462`) and its `claude`/`node`/`java` children; in
  the Docker pool, the `agent-N` containers (clean cgroup boundary on Linux).
- `other` = total − runner − agent (interactive work, the shared services, OS).

To make this a pure post-processing step, the collector must additionally record
**`ppid` and the owning user** per process (the current monitor records neither).
On Linux, the **cgroup path** gives clean per-container attribution for free and
should be preferred there.

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
`created_at`, `started_at`, `completed_at`, `labels[]` (the `runs-on` set),
`runner_id`, `runner_name`, `runner_group_name`, and `steps[]`
(each with `started_at`/`completed_at`). From these:

- **Queue wait** ≈ `started_at − created_at`, bucketed by `labels[]`.
- **Utilization** from `[started_at, completed_at]` busy windows per
  `runner_name`, joined to host metrics via the host manifest.
- **CI run time breakdown** from `steps[]` (checkout / build / test).

**Honest caveat (validate before trusting the headline number):** for a job
gated behind `needs:`, `started_at − created_at` includes time waiting on
*upstream jobs*, not only time waiting for a *free runner*. To isolate
runner-availability wait, the poller should subtract the completion time of the
job's last dependency (derivable from the run's job graph) — or, more simply,
focus the label-level queue-wait metric on **entry-point** test jobs that have no
CI dependency. The exact `created_at` semantics of the workflow-job object should
be confirmed against live API responses on this repo before the panel is treated
as authoritative (§12). Rate limits are a non-issue at this scale (5000
req/hr authenticated; a few dozen requests per poll every 5–15 min).

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
choice is a §11 decision.)

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
- **Transport**: agents reach the Mac Studio over **Tailscale** (already in use).
  The ingest endpoint / DB port is bound to the tailnet, not public, and the
  ingest endpoint authenticates with a bearer token using the established
  `AR_*_AUTH_TOKEN` pattern (`docker-compose.yml:56-58`).
- **Least privilege for the poller token**: read-only Actions scope; prefer a
  fine-grained PAT or a GitHub App over a classic `admin:org` PAT. Note the
  standing warning that self-hosted runners on a **public** repo can run
  fork-PR code (`tools/ci/rocm/README.md:330-335`); the metrics agent must not
  widen that exposure (host-scoped, outbound-only, no inbound execution path).

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
   bearer auth; retry/backpressure; never log tokens. *Depends on 1.*
4. **Runner-state detection.** Cross-platform idle/busy + current-job via
   `Runner.Worker` presence and process tree; map `runner_name → host` via an
   operator manifest. *Depends on 2.*
5. **GitHub job poller.** Pull runs+jobs, compute queue wait (with the
   dependency-wait caveat of §5.4), per-label aggregates, and step breakdown;
   reuse the `qa-cadence.sh` curl/jq/date patterns; handle pagination + rate
   limits. Tests in `tools/tests/`. *Depends on 1.*
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
8. **CLI name and code location** under `tools/` (e.g. `tools/ci/fleet/`), and —
   consequentially — whether its Python tests live in `tools/tests/` (no CI
   change) or in a new directory that requires adding a discover step to the
   `python-tests` job (a workflow change, normally an agent-locked file except on
   a `ci/...` branch — §1.4).
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
- **Greenfield assumption.** `workstream_context` shows no prior work on this
  branch and there is no existing fleet-monitoring plan in `docs/plans/`, so I
  treat this as greenfield — but the `consult` documentation backend was
  unreachable during this session (degraded), so a design note I could not see is
  a small residual risk.

---

## Appendix A — GitHub workflow-job fields used

From `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs` (`.jobs[]`):
`id`, `run_id`, `name`, `status`, `conclusion`, `created_at`, `started_at`,
`completed_at`, `labels` (the `runs-on` set), `runner_id`, `runner_name`,
`runner_group_name`, `steps[]` (`name`, `status`, `conclusion`, `number`,
`started_at`, `completed_at`). Registered-runner state from
`GET /repos/{owner}/{repo}/actions/runners` (and the org variant):
`name`, `os`, `status` (`online`/`offline`), `busy`, `labels[]`.

## Appendix B — proposed tables (sketch, not final)

```
host_sample(ts, host, cpu_pct, mem_used_mb, mem_total_mb, disk_used_gb,
            disk_total_gb, load1, load5, load15, thermal_c, throttled)
class_sample(ts, host, class /*runner|agent|other*/, cpu_pct, rss_mb)
runner_state(ts, host, runner_name, labels, state /*idle|busy*/,
             repo, workflow, job_id, agent_version)
job_event(job_id, run_id, repo, name, labels, created_at, started_at,
          completed_at, status, conclusion, runner_name, runner_group)
job_step(job_id, number, name, started_at, completed_at, conclusion)
```

Rollups (`*_1m`, `*_1h`) are continuous aggregates over `host_sample` /
`class_sample`. No table holds a credential of any kind (§5.7).
