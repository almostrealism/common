# Runner Fleet Monitoring — read-only visibility pieces

Metrics collection, storage, and read-only visibility for the self-hosted
GitHub Actions runner fleet: what each host is spending CPU/memory on
(split by GitHub Actions runner, FlowTree coding agent, or everything else),
what state each runner is in, and how long a CI job actually waits before it
starts running.

| Module | What it does |
|---|---|
| `attribution.py` | Classifies host processes into `runner`/`agent`/`other` by process-tree ancestry. Every class is a direct sum of its own processes — never a `total - runner - agent` residual, which is ill-defined (differing `%cpu` accounting bases, RSS double-counted across shared pages). |
| `collector.py` | Generalises `tools/ci/monitor/ar-host-monitor.sh`: records `ppid` and the owning user per process (the existing monitor records neither) and computes the attribution split. Writes local JSONL (with `filter_procs` keeping every `runner`/`agent` process and only the `other` processes above a CPU/RSS threshold — unfiltered, one sample is ~100 KB on a busy host) and, given `--store-url`/`--store-url-file`, the `host_sample`/`class_sample` rows to the store (`store_record`). `run_sampling_loop`/`main` is the scheduled entry point — `python -m tools.fleet.collector --log-dir <dir> …`, the body of the launchd service in `launchd/` — opening the store lazily and reopening it after any failed write, so a database restart costs a few rows, never the collector. |
| `schema.py` | The store schema, with an explicit primary/unique key on every table — needed because both ingest paths (direct writes from collectors, repeated GitHub polling) are at-least-once. Portable DDL; the timestamp columns are `TEXT` on sqlite and `TIMESTAMPTZ` on Postgres. |
| `store.py` | `FleetStore`: the schema on either sqlite3 (tests, a single host) or Postgres (the central store), through one implementation — `INSERT … ON CONFLICT DO UPDATE` upserts, `?`→`%s` placeholder rewriting, explicit transactions. `FleetStore.from_url` picks the backend from `sqlite:///path` or `postgresql://…`; Postgres needs `psycopg`, imported only when a Postgres store is opened. |
| `credentials.py` | `read_secret_file`: the one way a credential (store URL, GitHub token) enters a service — from a file that must be mode 600, never a command line. |
| `github_poller.py` | Computes `pre_start_latency_seconds` (the raw `started_at - created_at` interval) and, given the job's dependency graph, a real `queue_wait_seconds` distinct from it — a job gated on a workflow `needs:` does not have its queue wait measured by the raw interval alone, since that also includes time blocked on upstream jobs. `poll_and_store` is the poll-cycle entry point: it fetches runs/jobs, computes metrics, and upserts `job_event`/`job_step` into a `FleetStore`, retrying rate-limited (403/429) responses with backoff; each job also carries `lane` (its `ar-*` label — what kind of work the runner is for) and `platform`, from `classify_labels`. `run_poll_loop`/`main` is the scheduled entry point — `python -m tools.fleet.github_poller --repo owner/name --token-file … --store-url-file … [--runners-org owner]` — with the collector's reconnect behaviour; it resolves dependencies through `workflow_graph.py` and, every cycle, records each registered self-hosted runner (busy / idle / offline, labels, lane) into `runner_state` from the runners API. |
| `workflow_graph.py` | `WorkflowGraph`: a workflow file's jobs, their `needs:` and the mapping from the display name the jobs API reports back to the YAML key that produced it (key, literal `name:`, matrix suffix, reusable-workflow caller, `${{ }}` names as patterns — unknown or ambiguous is `None`, never a guess). `WorkflowGraphResolver` fetches each run's workflow file at the run's commit (once per file and commit) and supplies `poll_and_store`'s two callbacks: the job's `needs`, and when its last dependency finished, so `queue_wait_seconds` is measured for dependent jobs too. Needs `PyYAML`; without it the poller says so and leaves the dependency columns `NULL`. |
| `cli.py` | The two read verbs, `list` and `status`, against any store (`--db fleet.db`, or `--db-url-file` for the central Postgres store — `--db` itself rejects a `postgresql://…` URL, since the credential it carries would otherwise appear on this process's command line). |
| `launchd/` | LaunchDaemon templates for the collector and the poller; `render.sh`, which fills them in for a host and creates the private interpreter (a venv with `psycopg` and `PyYAML`) they run with; and `install.sh`, the one-command install for a macOS host (render, credential, a proven first sample, registration). |

## Deploying it

The central store and dashboard are two services in the controller compose
stack, `fleet-db` (Postgres) and `fleet-grafana`
(`flowtree/runtime/controller/docker-compose.yml`); `rebuild.sh` generates
their passwords into `/Users/Shared/flowtree/secrets/` on first run and binds
their ports to the host's tailnet address only (it detects the address, or
takes `FLEET_BIND_ADDR`, and writes the result to the compose project's
`.env` — `flowtree/runtime/controller/.env`, gitignored — so every later
`docker compose` command from that project, `logs` and `exec` included,
resolves the address too; the compose file refuses to interpolate without
one). Grafana's datasource and the capacity dashboard are provisioned from
`flowtree/runtime/controller/grafana/`, so a dashboard change ships with the
next deploy.

Collectors write to the store directly over the tailnet, and so does the
poller — there is no ingest service in between. The cost is a database
credential on each host, which is why the collector runs as an account that
executes neither CI jobs nor coding-agent jobs, with the credential in a
mode-600 file only that account can read (`read_secret_file` refuses
anything more permissive).

On a macOS host, as the account the collector will run as (never the account
the runners run as — the installer refuses that), one command does the whole
install:

```bash
tools/fleet/launchd/install.sh --store-from michael@mac-studio
```

It creates the private interpreter and renders the plists (`render.sh`),
copies the store credential from a host that already has it with `scp`
(expect that host's password prompt unless you have a key there; or use an
existing `~/fleet/store-url`), takes **one sample into the central store and
reads it back** before anything is daemonised — a credential or database
problem fails there, in the foreground — and then registers the collector
daemon through the native agent's `register-daemon.sh` (the one `sudo`
step). Re-running it updates an existing install. On the store host add
`--with-poller`, after putting the poller's read-only GitHub token at
`/Users/Shared/flowtree/secrets/fleet-github-token`, mode 600, owned by the
same account; the poller runs once per fleet, not per host. The token is
fine-grained with Actions: read (runs, jobs), Contents: read (each run's
workflow file, for job dependencies) and Administration: read on the
repository plus Self-hosted runners: read on the organization (the runner
inventory — `FLEET_RUNNERS_ORG`, default the repository's owner, because
runners registered org-wide are not in the repository's list). A token
without the last two still records jobs; the poller warns each cycle that the
inventory is unavailable and the runner-count panels stay empty.

The pieces, for a host where you want to do them by hand (`--no-register`
stops before the sudo step and prints the commands):

```bash
tools/fleet/launchd/render.sh          # venv with psycopg + PyYAML, rendered plists under ~/fleet
printf 'postgresql://fleet:%s@<tailnet address of the store host>:5432/fleet\n' \
    "$(cat /Users/Shared/flowtree/secrets/fleet-db-password)" > ~/fleet/store-url
chmod 600 ~/fleet/store-url
sudo <your checkout>/flowtree/runtime/agent/macos/register-daemon.sh \
    com.almostrealism.fleet-collector ~/fleet/launchd/com.almostrealism.fleet-collector.plist
```

`register-daemon.sh` validates that the plist runs the service as the
account that wrote it and nothing else, and must itself be run from a
checkout you own.

`launchctl print system/com.almostrealism.fleet-collector | grep -E 'state|pid'`
and `tail -f ~/fleet/logs/collector.log` show whether it is up; the host
appears in Grafana (`http://<tailnet address>:3000`, user `admin`, password
in `/Users/Shared/flowtree/secrets/grafana-admin-password`) within a minute.
The collector's `--host` label is the host's `LocalHostName`, lower-cased;
`render.sh` sets it because `platform.node()` on a Tailscale host can return
the FQDN with extra tokens appended. A runner whose work volume is not `/`
needs `FLEET_DISK_PATH=<mount>` in the environment of the install.

### Linux runner hosts

The runners on the Linux hosts are containers — Docker Compose on the CPU
fleet (`tools/ci/docker`), rootless podman on the ROCm fleet
(`tools/ci/rocm`) — but on native Linux a container's processes are ordinary
host processes, so the collector runs on the host, not in a container, and
its process-tree attribution works unchanged (`Runner.Listener` fits Linux
`ps`'s 15-character `comm`). The differences are packaging, and
`tools/fleet/systemd/install.sh` handles them. With sudo, from a checkout:

```bash
sudo tools/fleet/systemd/install.sh --store-from michael@mac-studio --disk-path /var/lib/docker
```

It creates a dedicated system account (`fleet`, no login shell — the
runners' account must not be able to read the store credential, and on the
ROCm host that account runs fork-PR code), snapshots `tools/fleet` into
`/var/lib/fleet/app` as a root-owned copy the service imports and creates
the venv with `psycopg` beside it, also root-owned (so neither the service
account nor the runner account can change what the service executes — only
the logs and the credential are the service account's; re-run to update),
copies the
credential (`--store-from` runs `scp` as the user behind `sudo`, whose keys
reach the other host; `--store-url-file` takes a local file), takes one
sample into the central store **as the service account** and reads it back,
then renders `tools/fleet/systemd/fleet-collector.service` into
`/etc/systemd/system/` and enables it. `--disk-path` should be the runners'
work volume — the Docker data root, or `/var/lib/containers` / the service
account's podman storage on the ROCm host — since that is the disk the
capacity question is about. `journalctl -u fleet-collector -f` shows the
service; the JSONL fallback is under `/var/lib/fleet/logs`.

## What is intentionally not here

- **An ingest service** in front of the database. The design describes one
  (bearer auth, tailnet-bound); the direct connection above is the short
  path that needs nothing built server-side, and it is enough while the
  fleet is a handful of hosts the operator controls. Reconsider if a host
  that runs fork-PR code cannot be given a separate account for the
  collector.
- **Docker-API-based attribution** for containers whose process tree is not
  visible to a native host `ps` (e.g. containers running inside a
  virtualized container runtime on macOS): needs a real host of that kind to
  validate against.
- **Control verbs** (`start`/`stop`/`restart`/`register`/`label`) and their
  platform adapters: these need an operator-supplied fleet inventory and
  should follow read-only visibility, not precede it.

## What the dashboard reads, and its limits

The capacity dashboard (`flowtree/runtime/controller/grafana/dashboards/fleet-capacity.json`)
answers "short of runners, or using them badly?" per **lane** — the fleet's
`ar-*` label (`ar-ci`, `ar-ci-cl`, `ar-deploy`, …), which names the kind of
work a runner is for. Every job panel is restricted to jobs with a lane, i.e.
to self-hosted runners; GitHub-hosted jobs say nothing about this hardware.
The headline pair is **wait for a runner** (`queue_wait_seconds`, per lane)
against **runners busy / idle / offline** (`runner_state`, per lane): a lane
whose wait grows while none of its runners is idle is short; one whose wait
grows with idle runners has a labelling problem. Below that: fleet CPU by
attribution class, per-host CPU / memory / disk as a share of the host's
total, per-runner utilization over the range, and hours per workflow step.

- **`queue_wait_seconds` needs the job's dependency graph**, which the poller
  reads from the run's workflow file (`workflow_graph.py`). A job whose
  display name cannot be matched back to its YAML key — an ambiguous
  `name:` expression, a file the API will not serve at that commit — is
  left `NULL` rather than guessed, and the dashboard's "Wait measured" tile
  says what share of jobs the wait figures cover.
- **`job_event.labels`, and so `lane`/`platform`, are the executing
  runner's actual label set, not the job's requested `runs-on:` set.** A
  runner can carry extra/custom labels beyond what a job asked for, so a
  lane measures jobs that ran on a runner carrying that `ar-*` label. The
  two coincide as long as every runner carries exactly one `ar-*` label —
  the fleet's convention. The set is JSON-encoded
  (`json.dumps(sorted(labels))`), not comma-joined — a comma-joined encoding
  cannot distinguish `["a,b", "c"]` from `["a", "b,c"]`; a caller filtering
  `pre_start_latency_by_label(labels=...)` on an exact set must encode it
  the same way.
- **`runner_state.host` is `''` from the poller**: the runners API does not
  say which machine a runner is on, and there is no host manifest yet
  (design §2.3). Runner counts per lane do not need it; joining a runner to
  its host's CPU samples does.

Tests live in `tools/tests/test_fleet_*.py` (not beside these modules) so the
existing `python-tests` CI step, which already discovers
`tools/tests/test_*.py`, picks them up with no workflow change.
