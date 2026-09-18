# Runner Fleet Monitoring — read-only visibility pieces

Metrics collection, storage, and read-only visibility for the self-hosted
GitHub Actions runner fleet: what each host is spending CPU/memory on
(split by GitHub Actions runner, FlowTree coding agent, or everything else),
what state each runner is in, and how long a CI job actually waits before it
starts running.

| Module | What it does |
|---|---|
| `attribution.py` | Classifies host processes into `runner`/`agent`/`other` by process-tree ancestry. Every class is a direct sum of its own processes — never a `total - runner - agent` residual, which is ill-defined (differing `%cpu` accounting bases, RSS double-counted across shared pages). |
| `collector.py` | Generalises `tools/ci/monitor/ar-host-monitor.sh`: records `ppid` and the owning user per process (the existing monitor records neither) and computes the attribution split. Writes local JSONL, matching the existing monitor's fallback-first design. |
| `schema.py` | The store schema, with an explicit primary/unique key on every table — needed because both ingest paths (batched pushes, repeated GitHub polling) are at-least-once. |
| `store.py` | A `sqlite3`-backed implementation of that schema with upsert-on-natural-key semantics, plus the read queries the CLI uses. A Postgres/TimescaleDB deployment is expected to use the same schema and query shapes; only the connection and upsert syntax differ. |
| `github_poller.py` | Computes `pre_start_latency_seconds` (the raw `started_at - created_at` interval) and, only when the caller supplies the job's dependency graph, a real `queue_wait_seconds` distinct from it — a job gated on a workflow `needs:` does not have its queue wait measured by the raw interval alone, since that also includes time blocked on upstream jobs. `poll_and_store` is the poll-cycle entry point: it fetches runs/jobs, computes metrics, and upserts `job_event`/`job_step` into a `FleetStore`, retrying rate-limited (403/429) responses with backoff. |
| `cli.py` | The two read verbs, `list` and `status`, against `store.py`. |

## What is intentionally not here

- **Push transport** to a remote ingest endpoint, and **dashboards**: both
  need a running store/ingest instance to deploy against, and an actual
  fleet inventory to point at.
- **Docker-API-based attribution** for containers whose process tree is not
  visible to a native host `ps` (e.g. containers running inside a
  virtualized container runtime on macOS): needs a real host of that kind to
  validate against.
- **Control verbs** (`start`/`stop`/`restart`/`register`/`label`) and their
  platform adapters: these need an operator-supplied fleet inventory and
  should follow read-only visibility, not precede it.

Tests live in `tools/tests/test_fleet_*.py` (not beside these modules) so the
existing `python-tests` CI step, which already discovers
`tools/tests/test_*.py`, picks them up with no workflow change.
