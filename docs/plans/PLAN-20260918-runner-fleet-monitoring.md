# Runner Fleet Monitoring & Capacity Planning

Status: **planning / design** — not yet approved for implementation.
Author context: refinement of the task brief for
`project/plan-20260918-runner-fleet-monitoring`. This document recommends a
design, states the trade-offs behind each choice, breaks the work into
agent-sized tasks, and ends with the decisions a human must make before any
code is written.

> This is a `docs/plans/` document: temporary, expected to be superseded or
> deleted once the work lands. Per `docs/plans/CLAUDE.md`, no retained code or
> durable doc may reference it.

---

## 1. Why this exists (the one question that matters first)

Automated PR volume has grown to the point where the self-hosted runners are
under heavy load and the operator is weighing buying more machines. There is
almost no fleet visibility today. **The first customer of this system is a
purchasing decision**, so its first job is to answer:

> *Are we short of capacity, or are we using badly the hardware we already have?*

That framing drives every recommendation below: read-only visibility that makes
utilization, queue wait, and headroom legible comes before any polished CLI or
control command. Phases 1 and 2 matter more than Phase 3.

---

## 2. What already exists in this repo (inventory)

Runner management today is **not** a single set of ad hoc "start some runners"
scripts — it is **three different substrates**, each with its own lifecycle
mechanism, plus a local host monitor. Cataloguing this first is essential: we
must not replace what we have not understood, and two of these already do part
of the job.

### 2.1 Runner-management substrates

| Fleet | Label (`runs-on`) | Mechanism | Control surface today |
|---|---|---|---|
| macOS GPU/Metal | `[self-hosted, macos, ar-ci]` | `tools/ci/macos/runner.sh` — **one ephemeral runner per invocation**, foreground loop; `launchd` for auto-start | register/deregister/labels via GitHub PAT (`repo`/`org` scope); CPU cap via `cpu-watcher.sh`. No multi-runner or list command. |
| Linux CPU | `[self-hosted, linux, ar-ci]` | `tools/ci/docker/` Docker Compose, `docker compose up -d --scale runner=N` | `RUNNER_PREFIX` per host; `entrypoint.sh:77` calls `GET /actions/runners` to clean up stale registrations. |
| Linux ROCm/OpenCL | `[self-hosted, linux, ar-ci-cl]` | `tools/ci/rocm/` — systemd **user** units + rootless podman, template unit `ar-ci-cl-runner@N` | **`fleet.sh` is already a fleet CLI**: `start [N]` / `stop [--if-idle]` / `status` / `restart` / `logs [-f]`; idle-vs-busy via the `Runner.Worker` process (`fleet.sh:107`); `MAX_INSTANCE=32`; single AMD "halo" host, ~125 GB *unified* GPU/CPU memory (`rocm/README.md`); runners self-name `amd-halo-N`. `entrypoint.sh:193` has `list_runners()`. |
| macOS deploy | `[self-hosted, macos, ar-deploy]` | `deploy.yaml` job | Docker controller-stack deploy; label deliberately distinct from `ar-ci` so a deploy never queues behind the test lane. |
| macOS native agent deploy | `[self-hosted, macos, ar-deploy-agent]` | `deploy.yaml:262` `deploy-macos-agent` | Installs the native launchd FlowTree agent as the `worker` account. |

Also `ubuntu-latest` (GitHub-hosted) is used for the light jobs (build,
`python-tests`, `docker-build`, `analysis`, `auto-resolve`). Those are **not**
self-hosted and are out of scope for host monitoring.

**Design consequences.**
- The GitHub-facing operations (list / register / deregister / set-labels /
  version / current-job) are **identical across substrates** — same Actions
  API, same on-disk runner layout (`.runner`, `config.sh`, `Runner.Listener` /
  `Runner.Worker` processes). Only the *lifecycle* (start/stop/restart) is
  substrate-specific (`launchctl` vs `docker compose` vs `systemctl --user` +
  `podman`).
- `rocm/fleet.sh` already demonstrates the exact idle/busy signal we need
  (`Runner.Worker` present ⇒ a job is running) and the `--if-idle` safety
  pattern. The new tool should **generalise `fleet.sh`, not ignore it.**

### 2.2 Existing host monitor (the closest thing to a metrics agent)

`tools/ci/monitor/` already samples host CPU/memory:

- `ar-host-monitor.sh` — every `MONITOR_INTERVAL` (default 10s) runs
  `ps -eo pid,pcpu,pmem,rss,comm` + `uptime`, writes one JSONL line per sample,
  rotates by day, prunes past `MONITOR_RETENTION_DAYS` (default 14).
- `ar-host-query.sh` — retroactive time-range/process/load queries over the
  JSONL, cross-platform (BSD vs GNU `date`).
- README ships `launchd` and `systemd --user` unit templates.

**Gaps that make it insufficient as a fleet metrics agent** (it is a good
foundation to supersede, keeping its query ergonomics):
- **Local only** — no central store; JSONL stays on each host.
- **Threshold-filtered** — records only processes above 5% CPU / 2% MEM, so it
  cannot compute *total* utilization or attribute the remainder.
- **No runner awareness** — it does not know which processes are runner jobs vs
  FlowTree agents vs everything else.
- **No disk, no thermal/throttling.**
- **No GitHub job join** — cannot answer queue wait.

### 2.3 The central host: `mac-studio`

`mac-studio` is already the shared hub (`http://mac-studio:7780`,
`verify-completion.yaml:90`). It hosts:

- the FlowTree **controller** (endpoints seen in-repo: `/api/health`,
  `/api/jobs`, `/api/stats`, `/api/workstreams`, `/api/github/proxy`,
  `/api/secrets`, and the planned `/api/agents` — see
  `docs/plans/PER_WORKSTREAM_RUNNERS.md`);
- the **memory** store — SQLite at `<data_dir>/memory.db`
  (`tools/mcp/memory/store.py:114`);
- FlowTree **job stats** — H2 (`JobStatsStore`: `job_timing`,
  `job_runner_cost`, phase columns);
- the **Docker agent pool** (`flowtree-agent-1`, `-2`) and the **native launchd
  macOS agent** (Metal, the `platform:macos` capability label);
- and, per the workflows, the **macOS `ar-ci` CI runners** themselves.

This co-tenancy **is** the problem the brief describes: on the Mac Studio, CI
test lanes, FlowTree agent jobs, the controller stack, and interactive work all
contend for the same CPU. A host at 90% tells you nothing until you know *whose*
90% it is.

Critically: **there is no controller endpoint that lists connected agents** —
`.github/CLAUDE.md` notes the deploy job verifies the native agent's connection
with `lsof` "because there is no controller endpoint listing connected agents."
So runner-vs-agent attribution cannot lean on a controller node API today; it
must be derived by process/cgroup/container bucketing (see §5.7).

### 2.4 Transport, credentials, GitHub API — all already patterned

- **Tailscale is already the transport.** `tools/mcp/manager/setup.sh` exposes
  ar-manager via **Tailscale Funnel**; hosts are on the tailnet
  (`*.ts.net`, `oauth.py:284`). Funnel is *public* TLS; the plain tailnet is
  private WireGuard.
- **GitHub API = curl + jq**, cross-platform, already used for the runner
  lifecycle (`qa-cadence.sh`, `rocm/entrypoint.sh:193`, `docker/entrypoint.sh:77`)
  and paged listing (`qa-cadence.sh:84`).
- **Credentials** never need to live on every host: the controller already
  proxies GitHub authenticated (`/api/github/proxy`) and serves secrets
  (`/api/secrets`); the `ar-secrets` MCP server renders secret files locally.
  `runner.sh` reads `GITHUB_PAT` from a gitignored `.env`.
  `docs/plans/GITHUB_APP_CREDENTIALS.md` exists and is relevant to rate limits
  and credential strategy — consult it when deciding the credential path.

### 2.5 Gates any new code must pass

- **Python files are capped at 1600 lines** — `scripts/check-python-file-lengths.sh`
  (the Java Checkstyle `FileLength` equivalent; the exempt list is empty and
  meant to stay empty). This forces the CLI/agent to be decomposed into focused
  modules from day one.
- **Python tests run via `python3 -m unittest discover`** over `tools/tests`,
  `tools/mcp/manager`, `tools/mcp/common` (`analysis.yaml` `python-tests` job,
  gated on `python_changed`). New tests belong in `tools/tests/` with
  `test_*.py` names. There is **no** flake8/ruff/pylint config in the repo, so
  "lint" for Python is effectively the file-length cap plus the build
  validator's checks; shell scripts are expected to be `bash -n`/shellcheck
  clean by convention.
- **Never create a Maven module** (project rule). The CLI/agent are Python under
  `tools/` — no module, no `pom.xml`, so this is satisfied by construction.

---

## 3. Answers to the brief's eight questions (summary)

| # | Question | Recommendation (rationale in §5) |
|---|---|---|
| 1 | What runner scripts exist, where, what do they do? | Catalogued in §2.1. The scripts are **in this repo**; the missing piece is the operator's list of *physical hosts and per-host runner counts* (§Decisions D3). |
| 2 | How many hosts/runners, what labels? | Labels derived precisely (§2.1). Counts are operational, **not in the repo** — must come from the operator; the agent will also self-discover them once deployed. |
| 3 | One cross-platform CLI or two? Language? | **One CLI, in Python** (`ar-fleet`), with a thin per-substrate lifecycle adapter. §5.1. |
| 4 | Which store? | **VictoriaMetrics single-node** for host/runner time-series + **SQLite** for GitHub job events, both on `mac-studio`, both read by Grafana. Lighter alternative (SQLite-only) documented. §5.2 — flagged as a human decision. |
| 5 | Push or pull? | **Push** over the tailnet to a single endpoint. §5.3. |
| 6 | Smallest first deliverable? | Read-only agent + one Grafana board showing **utilization & headroom per host and per runner** — before any control command and before the GitHub join. §5.4. |
| 7 | Retention / resolution / disk? | 15s raw for 14–30 days, 5-min rollup for ~13 months; order of a few GB/year on the Mac Studio. §5.5. |
| 8 | Rollout without disrupting CI? | Agent is read-only, installed as a *separate* launchd/systemd unit; one host first; control ops reuse `--if-idle`/drain. §5.6. |

---

## 4. Recommended architecture (at a glance)

```
        ┌──────────── each runner host (macOS / Linux CPU / Linux ROCm) ───────────┐
        │  ar-fleet-agent  (Python, one launchd/systemd unit, read-only)           │
        │    • host vitals via psutil: cpu, mem, disk, load, thermal*              │
        │    • per-runner state: idle/busy, current job, labels, version           │
        │    • process bucketing: runner-job / flowtree-agent / stack / other      │
        │    • sink interface: local JSONL (parity w/ monitor) + push sink         │
        └──────────────────────────────┬───────────────────────────────────────────┘
                                        │  push over tailnet (WireGuard), outbound only
                                        ▼
        ┌──────────────────────── mac-studio (existing hub) ───────────────────────┐
        │  VictoriaMetrics (single-node)   ← host/runner time-series               │
        │  SQLite job-events DB             ← GitHub Actions job records (Phase 2)  │
        │  GitHub job ingester              ← Actions API poll (or webhook)         │
        │  Grafana (one container)          ← PromQL + SQLite datasources          │
        │      bound to the tailnet, login required, NOT on Funnel                 │
        └───────────────────────────────────────────────────────────────────────────┘

        ar-fleet  (Python CLI, runs from any machine on the tailnet)
            read:    list / status / current-job / version   (GitHub API + host adapter)
            control: start / stop / restart / register / deregister / labels (Phase 3)
            report:  capacity (Phase 4)
    * thermal/throttling is best-effort; see §5.7 and Open Questions.
```

Proposed code location: a new Python package `tools/ci/fleet/` (CLI + agent +
shared library), tests in `tools/tests/`, deployment (`install.sh`, launchd
plist, systemd unit) alongside — mirroring `tools/ci/monitor/` and
`tools/ci/macos/`. No Maven module.

---

## 5. Design decisions and trade-offs

### 5.1 One CLI, in Python (Q3)

**Recommendation: a single cross-platform CLI, `ar-fleet`, written in Python 3**,
with a small `HostAdapter` abstraction for the substrate-specific lifecycle and
a substrate-agnostic core for everything GitHub-facing.

Why one CLI: the operator wants to manage the fleet "across machines rather than
only where you are sitting." Runner *identity and control via GitHub* is
uniform; the only thing that differs is how a host starts/stops a local runner
process. A single CLI with three lifecycle adapters gives one interface and one
mental model; two CLIs would duplicate the GitHub client, the runner-dir parser,
and the status formatting.

Why Python (not Bash, not Go):
- **Fits the repo's gates and tests for free.** `tools/` is already Python-first
  (MCP servers, `tools/tests/` unittest, the `python-tests` CI job, the
  file-length cap). A Python CLI + agent drop straight into that pipeline; a Go
  binary adds a build toolchain the repo doesn't have, and Bash cannot carry a
  metrics agent + GitHub joins without becoming unmaintainable.
- **Erases the macOS-vs-Linux shell tax.** The repo already pays it repeatedly
  (`date -j` vs `date -d` in `qa-cadence.sh:163`; the bash-3.2 parallel-array
  gymnastics in `cpu-watcher.sh`). One Python 3 codebase runs on both; `psutil`
  gives cross-platform cpu/mem/disk/load in-process (no per-sample `ps|awk|jq`
  fork storm).
- **Shares a library with the agent.** CLI and agent both need the GitHub client,
  the runner-dir/process parser, and the host sampler.

Trade-offs to accept and record:
- **New runtime dependency: `psutil`** (and the CLI itself needs `python3`).
  `python3` is present on all these hosts; `psutil` is one pip install, pinned in
  a `requirements.txt` next to the agent. If a zero-dependency agent is required
  on some host, the sampler can fall back to parsing `ps`/`/proc` as the existing
  monitor does — keep the sampler behind an interface so this stays possible.
- **The CLI should delegate, not reimplement, lifecycle.** For ROCm, shell out
  to the working `fleet.sh`; for Linux CPU, drive `docker compose`; for macOS,
  drive `launchctl` + `runner.sh`. This respects "don't replace what works" and
  keeps the risky control paths in the code the operator has already validated.

Rejected: **two platform-specific CLIs** (duplicates the 80% that is shared);
**Go** (no repo toolchain, doesn't hit the Python gates); **pure Bash** (macOS
bash-3.2 + metrics + JSON joins is a maintenance sink).

### 5.2 Store: VictoriaMetrics + SQLite, on the Mac Studio (Q4)

**Recommendation: VictoriaMetrics single-node** for host and per-runner
time-series, **plus a small SQLite table for GitHub job events**, both hosted on
`mac-studio`, both surfaced through Grafana.

Reasoning, weighted by "what already runs on the Mac Studio" and "Grafana query
fit," and by the fact that **the Mac Studio is the most contended machine we are
trying to measure** — the store must be cheap or it defeats itself:
- **VictoriaMetrics single-node is a single small binary/container** with a low
  idle RAM footprint, Prometheus-compatible (PromQL, so Grafana queries it
  natively), strong compression, and — decisively for §5.3 — it **accepts push**
  (Prometheus remote-write, Influx line, Graphite, JSON import). One more
  container next to the controller stack.
- **GitHub job records are events, not metrics.** They carry high-cardinality
  attributes (repo, workflow, job, runner name, labels, conclusion). Forcing them
  into a TSDB blows up series cardinality. A **relational table in SQLite** — the
  same engine already on the box for `memory.db` — stores raw job rows for
  drill-down; derived aggregates (queue-wait histogram per label) can be pushed
  to VictoriaMetrics for fast dashboard panels. Grafana reads SQLite via the
  SQLite/Infinity datasource.

Alternatives weighed:
- **Prometheus** — the "default," but pull-first (§5.3 argues against pull for a
  roaming tailnet fleet) and heavier on RAM (grows with active series) on an
  already-loaded host. Rejected as primary for those two reasons; VictoriaMetrics
  is Prometheus-compatible, so we lose nothing on the Grafana side.
- **TimescaleDB / Postgres** — excellent for the relational join, but it is a
  full RDBMS to operate and a large resident process; overkill for tens of hosts
  and tens of runners. Rejected on ops weight.
- **SQLite-only + a tiny exporter** — the *lightest* option and reuses exactly
  what's on the box. Adequate for this fleet's scale (tens of hosts, ~15–30s
  sampling) if we downsample. Its weakness is fine-resolution TSDB queries and
  automatic retention/rollups over long windows. **This is the recommended
  fallback** if the operator wants absolute-minimum ops and will accept coarser
  time-series queries. Flagged as decision **D1**.

Caveat worth stating: because the Mac Studio contention is the very thing we
measure, hosting the store *on a less-contended tailnet host* (a Linux CPU
runner box, or the ROCm box) is worth considering even though the brief says
"on the Mac Studio." Keep it on the Mac Studio if the operator prefers a single
hub; note the alternative.

### 5.3 Push, not pull (Q5)

**Recommendation: agents push to a single endpoint on `mac-studio` over the
tailnet.**

- The fleet is heterogeneous and some hosts roam or aren't always on (Macs,
  laptops). **Pull (Prometheus scraping) requires the central server to reach an
  open port on every host** — awkward across a tailnet with NAT/sleep/roaming.
- **Push matches how everything already talks to `mac-studio`** (controller at
  `:7780`, MCP over the tailnet) and means **no new inbound ports on runner
  hosts** — which is also the safer security posture (§5.7).
- VictoriaMetrics ingests push natively.

Trade-off: push forfeits the "target down = scrape failed" signal pull gives for
free. **Mitigation:** every agent emits a heartbeat/last-sample timestamp;
alert when a host's freshest sample is older than *N* intervals. Cheap and
explicit.

### 5.4 Smallest first deliverable (Q6)

**Read-only visibility that answers the utilization half of the capacity
question, before any control command and before the GitHub join:**

1. `ar-fleet-agent` pushing host vitals (bucketed) + per-runner idle/busy;
2. VictoriaMetrics + Grafana on `mac-studio`;
3. one Grafana board: **per-host utilization (busy vs wall clock, by bucket)**
   and **per-runner busy/idle timeline**, plus raw vitals;
4. deployed to `mac-studio` and one Linux host to prove overhead.

Rationale: "are we using what we have well?" (utilization and headroom) is
answerable from **runner process state sampled over time** — `Runner.Worker`
present ⇒ busy — with **no GitHub data at all**. Queue wait ("are we short?")
needs the GitHub join and is Phase 2. Shipping utilization first gives the
operator a real number within the first phase.

### 5.5 Retention, resolution, disk (Q7)

- **Resolution:** host vitals at **15s**; per-runner GitHub-derived state at
  **30–60s** (API-rate-limit bounded). The existing monitor proves 10s `ps`
  sampling is negligible (~1.7 MB/day/host of JSONL).
- **Retention:** raw at 15s for **14–30 days** (incident-grade detail), a
  **5-minute rollup for ~13 months** (trend/seasonality for the purchasing
  decision).
- **Disk (order-of-magnitude, pending real counts):** assume <10 hosts and <60
  runners ⇒ low thousands of active series. At 15s that's ~10⁷ samples/day; at
  VictoriaMetrics' ~1 byte/sample compressed, **~10–15 MB/day raw**, i.e.
  **~4–5 GB/year** before rollup, comfortably under 10 GB/year with rollups.
  Negligible on a Mac Studio's TB-class disk. GitHub job rows in SQLite are tiny
  (one row per job; even thousands of jobs/week is single-digit MB/year).
  **These numbers scale with the real host/runner counts (decision D3);** treat
  them as a ceiling sketch, not a measurement.

### 5.6 Rollout without disrupting in-flight CI (Q8)

- The **agent is strictly read-only** (samples and pushes; issues no control) and
  installs as a **separate** launchd/systemd unit — exactly the pattern the
  monitor README already documents (`com.almostrealism.host-monitor.plist`). It
  never touches the runner services, so installing it cannot interrupt a job.
- **One host first** (`mac-studio` or a Linux CPU box), watch overhead against
  the target (§5.7), then roll out host by host.
- The store + Grafana are new services co-located with the controller stack; they
  don't interact with job execution.
- **Control operations (Phase 3) must reuse the existing safety patterns:**
  `fleet.sh` already has `stop --if-idle` and the `Runner.Worker` busy check;
  `deploy.yaml` drains intake before restarting an agent. The CLI's stop/restart
  must default to "finish the current job first" and require an explicit
  `--force` to cancel a running job — runners are ephemeral, so waiting costs
  nothing (`fleet.sh:152`).

### 5.7 Cross-cutting: attribution, overhead, security, dashboard

**Attribution — runner vs agent vs everything else (the load that would
otherwise mislead).** On the Mac Studio the same CPU is shared by macOS `ar-ci`
runner job JVMs, the Docker agent pool (`flowtree-agent-*`), the native launchd
agent JVM (`platform:macos`), the controller stack, and interactive work. The
agent must classify each process's CPU/mem into buckets so utilization can
separate "runner busy" from "agent busy":
- **runner-job** — descendants of the runner's `Runner.Worker` / `run.sh` tree
  under the runner dir (the signal `fleet.sh:107` already uses);
- **flowtree-agent** — processes inside `flowtree-agent-*` containers
  (cgroup / container PID namespace / `docker stats`) plus the native launchd
  agent JVM;
- **controller-stack** — the `ar-manager` / `ar-memory` / `ar-tracker`
  containers;
- **other** — the remainder (interactive, system).
Corroborate with the controller's own job records (`JobStatsStore` / `/api/jobs`,
`/api/stats`) for *when* agent jobs ran and where (`platform:macos`). Without
this bucketing, a saturated Mac Studio cannot be read as "runners are the
bottleneck" — it might be agents, which changes the purchasing answer entirely.

**Overhead target (collection must be cheap).** Target **< 1% of one core
averaged, < ~50 MB RSS** per agent. Keep the agent single-threaded, in-process
(psutil, no per-sample fork storm), no heavy deps. GitHub API calls are the
expensive part (latency + rate limits), so runner→job mapping polls on the slow
cadence (30–60s) or is driven by webhooks (Phase 2), never per host-vitals
sample. State the target in the agent README and verify it on the first host
before rollout.

**Security.**
- **Never log or store registration tokens or PATs in the metrics DB.** The DB
  holds only non-secret state (host id, runner name, labels, version, idle/busy,
  job id/number/URL). Registration tokens are short-lived and never persisted.
- **Prefer routing token-bearing GitHub calls through the controller's
  `/api/github/proxy`** so the PAT stays on `mac-studio`; where a host genuinely
  needs its own PAT (self-registration), read it from the mechanism the runner
  scripts already use (gitignored `.env` `GITHUB_PAT`, or an `ar-secrets`-rendered
  file) and never echo it. See `docs/plans/GITHUB_APP_CREDENTIALS.md` for whether
  a GitHub App (higher rate limits, scoped installation token) should back this
  instead of a classic PAT.
- **Transport is the private tailnet** (WireGuard), outbound-only from hosts;
  no new inbound ports.
- **Dashboard must not be exposed without auth:** bind Grafana to the tailnet
  interface and require login. **Do not put Grafana on Tailscale Funnel** (Funnel
  is public); Funnel is appropriate for the MCP server, not for an internal ops
  dashboard.

**Dashboard — Grafana fits (Q4 dashboard).** Grafana is the low-effort, capable
choice: it queries VictoriaMetrics (PromQL) and SQLite (SQLite/Infinity
datasource), supports all four required capacity views, and dashboards can be
provisioned **as code** (JSON committed to the repo, so a dashboard is
reviewable and reproducible). The only cost is one more container, which the Mac
Studio already runs several of. Alternatives (VictoriaMetrics' `vmui`, a bespoke
panel) are weaker for multi-panel dashboards and correlation; rejected.

The four capacity views the dashboard must make readable at a glance:
1. **Utilization per host and per runner** — busy vs wall clock, split by bucket.
2. **Queue wait time per runner label** — from the GitHub join (Phase 2).
3. **Concurrency headroom per host** — how many more concurrent jobs before
   CPU / memory / disk saturate (especially the ROCm host's single unified
   ~125 GB pool, and the Mac Studio's shared CPU).
4. **Where the time in a CI run goes** — per-job / per-stage duration breakdown,
   joining GitHub job timing with the lane structure.

---

## 6. Phased breakdown into agent-sized tasks

The brief's four phases are the right shape; refinements: an installer is needed
*within* Phase 1 (to deploy the Phase-1 agent), and control operations stay
genuinely last (read-only first, as the brief prefers). Each task below is
scoped to a single agent job — a few files, a clear acceptance test, and a
Python file kept well under the 1600-line cap.

### Phase 0 — shared library skeleton (foldable into Phase 1)
- **T0.1 — `tools/ci/fleet/` package + GitHub client.** Actions API client
  (list/register/deregister/set-labels, paged like `qa-cadence.sh`), able to go
  direct-with-PAT or via `/api/github/proxy`. Unit tests in `tools/tests/`
  mocking HTTP. *Accept:* tests pass under `unittest discover`; file < 1600 lines.
- **T0.2 — runner-dir + process parser.** Given a runner dir, report name,
  labels, version (`.runner` / config), and idle/busy from the `Runner.Worker`
  process (generalise `fleet.sh:107`). Cross-substrate. Unit tests over fixture
  dirs / mocked process lists.
- **T0.3 — host vitals sampler.** psutil-based cpu/mem/disk/load (+ best-effort
  thermal, §Open Questions) with the **process-bucketing** classifier (§5.7)
  behind an interface; a `ps`/`/proc` fallback path. Unit tests over synthetic
  process tables.

### Phase 1 — read-only visibility, store, first dashboard
- **T1.1 — `ar-fleet status` / `list` (read-only).** Compose T0.1–T0.2 into a
  command that lists runners on a host (name, labels, version, idle/busy,
  current job). *Accept:* against a mocked API + fixture runner dir, output is
  correct; no token ever printed.
- **T1.2 — `ar-fleet-agent` sampling loop.** Emit bucketed host vitals +
  per-runner state through a **sink interface** with two sinks: local JSONL
  (parity with the existing monitor) and a push sink (line protocol /
  remote-write). Config: interval, endpoint, host label. *Accept:* JSONL sink
  round-trips a known sample; push sink posts the expected payload to a fake
  endpoint; measured overhead within target on one host.
- **T1.3 — store bring-up on `mac-studio`.** VictoriaMetrics single-node
  (compose entry next to the controller stack) + retention config; document the
  push endpoint. (Or the SQLite-only fallback if D1 chooses it.) *Accept:* a
  pushed sample is queryable via PromQL.
- **T1.4 — Grafana + first dashboard (as code).** One board: per-host
  utilization (bucketed), per-runner busy/idle timeline, raw vitals. Tailnet
  binding + login; not on Funnel. Dashboard JSON committed. *Accept:* board
  renders from live data on one host.
- **T1.5 — installer + service units.** `install.sh` + launchd plist + systemd
  `--user` unit for the agent, mirroring `monitor/README` and `macos/` patterns;
  a heartbeat/freshness alert (§5.3). Deploy to one host, record overhead.
  *Accept:* agent auto-starts, survives logout/reboot on the pilot host,
  freshness alert fires when stopped.

### Phase 2 — GitHub job-level join (queue wait & per-label utilization)
- **T2.1 — GitHub job ingester.** Pull `workflow_jobs`
  (queued/started/completed, labels, runner name, conclusion) via the Actions
  API (or a webhook receiver — decision D2) into the SQLite job-events table on
  `mac-studio`. Idempotent upsert by job id. *Accept:* replaying a fixed set of
  API pages yields a stable table; rate-limit backoff covered by a test.
- **T2.2 — derived metrics.** Compute queue wait (`started − queued`) per label,
  job duration, and runner attribution; push aggregates to the TSDB and/or
  expose SQLite to Grafana. *Accept:* queue-wait aggregation matches a hand-worked
  fixture.
- **T2.3 — capacity dashboard panels.** Queue wait per label, utilization per
  label, and the "where time goes in a CI run" stage breakdown, joined with the
  three-lane structure. *Accept:* panels render; numbers reconcile with T2.2.

### Phase 3 — control operations, installers, docs
- **T3.1 — `ar-fleet start/stop/restart`** delegating to the substrate
  (`launchctl` + `runner.sh` / `docker compose` / `fleet.sh`), defaulting to
  **`--if-idle`** and requiring `--force` to cancel a running job (§5.6).
- **T3.2 — `ar-fleet register/deregister/labels`** via the GitHub API (+
  controller proxy), never logging tokens; covers repo and org scope like
  `runner.sh`.
- **T3.3 — full installers + docs + rollout guide** for all three substrates;
  document deprecating/absorbing `tools/ci/monitor` (superset) while keeping its
  query ergonomics.

### Phase 4 — the capacity report (the purchasing input)
- **T4.1 — `ar-fleet capacity` report** (and/or a scheduled job) that, from
  observed data, states: utilization vs wall clock per host and per label; queue
  wait distribution per label; concurrency headroom per host before CPU/memory/
  disk saturate; and a concrete "another machine of type X would buy roughly Y"
  estimate. Output is a written report — the artifact the operator's purchasing
  decision actually consumes. *Accept:* run against Phase 1–2 data, the report
  reproduces the dashboard's headline numbers and states its assumptions.

---

## 7. Decisions a human must make before implementation

- **D1 — Store choice.** VictoriaMetrics + SQLite (recommended) vs SQLite-only
  (lightest ops, coarser TSDB queries) vs Prometheus vs Timescale. A long-lived
  ops commitment on a contended host — needs the operator's sign-off. Also: keep
  the store on the Mac Studio (per brief) or move it to a less-contended tailnet
  host (§5.2 caveat)?
- **D2 — GitHub job data source.** Poll the Actions API (simple, coarser,
  rate-limited) vs webhooks (finer, near-real-time, but needs an inbound
  endpoint the tailnet/Funnel setup must accommodate). Affects Phase 2 shape.
- **D3 — Actual inventory.** The operator must provide the list of physical
  hosts, how many runners each runs, and their labels — not derivable from the
  repo. The label taxonomy is in §2.1; counts are not. (The agent self-discovers
  once deployed, but Phase-1 sizing/retention needs a first estimate.)
- **D4 — Delegate vs reimplement lifecycle.** Recommendation is to delegate to
  the existing substrate scripts. Confirm — and decide whether to bring macOS
  runner supervision (today a hand-run `runner.sh`) under `launchd KeepAlive` so
  every substrate is supervised uniformly.
- **D5 — Credential path.** Controller `/api/github/proxy` + `/api/secrets` vs
  per-host PAT via `ar-secrets`, and classic PAT vs GitHub App
  (`docs/plans/GITHUB_APP_CREDENTIALS.md`) for rate limits and scoping.
  Security-sensitive.
- **D6 — Dashboard exposure.** Tailnet-only Grafana (recommended) vs
  Funnel-with-auth. Confirm no public exposure of the ops dashboard.
- **D7 — Fate of `tools/ci/monitor`.** Supersede/deprecate it (the fleet agent
  is a superset) or keep it running in parallel during migration?

---

## 8. What I am unsure about (stated plainly, not invented)

- **Host/runner counts and the physical machine list** are genuinely not in the
  repo. I derived the *label* taxonomy and the *substrate* mechanics; I did not
  invent counts. This is D3.
- **macOS thermal / throttling metrics are the weakest data point.** On Apple
  Silicon, per-core frequency/throttle detail typically needs `powermetrics`,
  which requires **root** — undesirable for a low-privilege sampling agent.
  `pmset -g therm` exposes a coarse thermal/CPU-limit state without root. I am
  not certain how much useful thermal signal we can get unprivileged on the Mac
  Studio; the plan treats thermal as **best-effort** and this needs a spike
  before promising it on the dashboard. Linux exposes `/sys/class/thermal` and
  `/sys/.../cpufreq` without root, so Linux thermal is more tractable.
- **Webhook feasibility (D2)** depends on whether an inbound endpoint to
  `mac-studio` is acceptable given the tailnet/Funnel posture. If not, polling is
  the safe default and the plan can proceed on polling alone.
- **Exact disk cost** is a sketch until D3 supplies counts.
- **Whether some macOS runners are started by hand today** (vs launchd) — the
  `runner.sh` design is a foreground loop, so uniform supervision (D4) may be a
  real change on those hosts rather than a no-op.
- I could not record progress memories or send status messages during the second
  half of this session: the `ar-manager` MCP server returned *"requires
  re-authorization (token expired)"* and this session is non-interactive, so the
  OAuth flow could not be run. The inventory and this plan are the durable record
  instead; the token should be refreshed so future sessions can store memories.
