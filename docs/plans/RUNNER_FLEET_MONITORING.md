---
status: in-progress
created: 2026-09-18
closed:
tracker: []
---

# Runner Fleet Monitoring: remaining work

## Purpose

The self-hosted GitHub Actions runner fleet is under heavy load and the operator is
deciding whether to buy more machines. This system exists to answer one question first:

> Are we short of capacity, or are we using badly what we already have?

"Runner" here means a GitHub Actions runner. It is not the FlowTree `AgentRunner`.

## What is built (phase A, read-only visibility)

The read-only visibility layer is in place and documented in `tools/fleet/README.md`. That
README is the durable record of the design: process-tree attribution into
runner/agent/other, the store schema and its idempotency keys, queue wait versus pre-start
latency, lanes, and the dashboard's limits. Built so far:

- the collector, with launchd and systemd installers;
- the Postgres or sqlite store, with `fleet-db` and `fleet-grafana` in the controller
  compose stack;
- the GitHub poller, which computes dependency-adjusted queue wait from each run's workflow
  file and records `runner_state` from the runners API;
- the capacity dashboard, which shows wait per lane against runners
  busy/idle/offline;
- the `list` and `status` CLI verbs.

## Remaining work

### Finish phase A

1. **Host manifest.** `runner_state.host` is always `''`: the runners API does not say
   which machine a runner is on. An operator-maintained manifest from runner name to host
   is needed before a runner can be joined to its host's CPU and memory samples, which the
   per-host concurrency-headroom panel depends on. Blocked on decision 1 below.
2. **Runner-state detection on the host.** Detect idle/busy and the current job locally,
   from `Runner.Worker` presence and the process tree, the same test the ROCm fleet
   scripts use. This complements the runners-API view, and the job detail is joined from
   the poller by runner name.
3. **Docker-API attribution.** On macOS, the Docker-pool `agent-N` containers run inside
   Docker Desktop's VM and are invisible to a native host `ps`. Their CPU and RSS must
   come from `docker stats` or the Docker API, a separate collection path for the same
   `agent` class. Without it, agent load on such a host is silently omitted. Validate on a
   real host of that kind.

### Phase B: the capacity report

4. **`capacity-report` command.** From observed data, quantify where the fleet is
   actually short, and estimate what another machine would buy (for example, the
   projected reduction in queue wait at the observed arrival rate, per lane). It should
   separate the two answers the dashboard's two-by-two distinguishes:
   - "short": wait grows while no runner in the lane is idle;
   - "badly used": wait grows while runners in the lane are idle, i.e. a labelling or
     distribution problem.
   It should also report how much CI time goes to `mvn install` versus tests, because
   caching helps there where more runners would not.

   The workflow caps a single pipeline's concurrency (`max-parallel` on the matrix lanes),
   so one pipeline's demand per lane is bounded. The report should show how often
   concurrent pipelines push demand past that, and how long jobs wait when they do.

### Phase C: control and packaging

5. **Fleet-wide control verbs.** `start`/`stop`/`restart`/`register`/`deregister`/`label`,
   all `--if-idle`-safe, driven across hosts from an inventory. They sit behind platform
   adapters that wrap the existing per-host controls: `tools/bin/fleet macos ...`
   (launchd), `docker compose`, and `tools/bin/fleet rocm ...` (systemd/podman).
   Per-host control already exists; cross-host control needs the inventory from item 1.
6. **Remaining rollout.** Install the collector host by host (Mac Studio first, then the
   Linux and ROCm hosts), following the `tools/ci/macos` and `tools/ci/rocm`
   conventions, without restarting runners.

## Decisions that need a human

1. **Host inventory and accounts.** The authoritative host list, each host's fleet type
   and runner count, and the account each runner and each FlowTree agent runs as. This
   blocks items 1 and 5.
2. **GitHub data source.** Repo-scoped or org-scoped polling (the poller supports both),
   a fine-grained PAT or a GitHub App, and whether to add webhooks later.
3. **Thermal data on macOS.** May the collector run `powermetrics` as root? If not,
   thermal and throttling data is unavailable on macOS; the other host metrics are
   unaffected.
4. **Out-of-repo scripts.** Are there ad hoc "start some runners" scripts the control
   verbs should absorb, and where do their credentials live?
5. **Grafana exposure.** Tailnet-only (the current setup), or Tailscale Funnel with
   OAuth.
6. **Retention and resolution.** Confirm the tiers (15 s raw kept for 14 days, 1-minute
   rollups for 90 days, 1-hour rollups for a year) against how far back the purchasing
   analysis must look.
7. **CLI name and home.** Whether the code stays in `tools/fleet/`. It was chosen over
   `tools/ci/fleet/` because `tools/ci/` changes only on `ci/` branches.

## Open uncertainties

- **`created_at` semantics.** Confirm the workflow-job `created_at` against live API
  responses for this repository before treating queue-wait panels as authoritative.
- **Per-runner attribution on macOS.** Several `ar-ci` runners can share one account and
  `$HOME`, so the process tree from each `Runner.Listener` is the only signal that
  separates them. Reparented children are the edge case to validate on a real host.
- **Requested versus executing labels.** Lanes are derived from the *executing* runner's
  labels, not the job's requested `runs-on:` set. The two coincide only while every runner
  carries exactly one `ar-*` label. If that convention breaks, persist the requested set
  separately, derived from the workflow YAML.
