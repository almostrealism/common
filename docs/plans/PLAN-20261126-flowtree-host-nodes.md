---
status: proposed
tracker_task: c2e54a19-0204-46b6-9500-dd8fdf8826bd
title: Run FlowTree on the host and make containers a kind of Node, not a place to put a Server
created: 2026-11-26
---

# FlowTree on the Host: Containers as a Node Kind

## Summary

FlowTree was designed as **one Server per machine, many Nodes per Server**. Today
we invert that: isolation between agents is obtained by running a whole FlowTree
`Server` inside each Docker container, so one machine ends up with several Servers
that know nothing about each other or about the machine.

This plan makes the container a **kind of `Node`** inside a single host Server. The
native host Node is today's `Node` (runs the job in-process). New Node subtypes —
Docker first, then Colima — hand the job to a container that does the work in
isolation and report the result back. FlowTree becomes the light scheduler that
owns the whole machine; the container runtime becomes an implementation detail of
one Node kind.

The work is staged so each stage is useful alone and reviewable on its own, and
**Stage 1 leaves existing deployments behaving identically**. No new Maven module
is required (see [§6](#6-no-new-maven-module)).

---

## 1. Decisions

The task lists nine decisions. Decisions 1–4 are settled concretely below;
5–9 give a recommendation and the open questions that remain for the stage that
implements them.

### Decision 1 — What crosses the container boundary — **the serialized job**

**Recommendation: (a) the serialized job, decoded and run by a thin worker process
in the container that speaks the existing wire format.**

`flowtree/CLAUDE.md` opens with "the job is the source of truth for the workload":
every property that describes what a job does already lives on `CodingAgentJob`,
travels over the wire, and is visible in the job record. The job *already*
serializes — `Node.addFailedJob(j.toWire())` (`Node.java:430`) encodes a job to a
String and `Server.instantiateJobClass(String)` (`Node.java:1063`) decodes it. A
container Node therefore needs no second description of the work: it encodes the
`Job` it dequeued with the same codec path (`CodingAgentJobCodec`,
`GitManagedJobCodec`) and hands the bytes to a worker in the container.

The container runs a **new thin main — `io.flowtree.jobs.CodingAgentJobWorker`**,
NOT a `Server`. It has no `NodeGroup`, no peer connections, no relay. It reads one
encoded job (from a file or stdin), decodes it via `Server.instantiateJobClass`,
wires it to an `OutputServer`/output consumer exactly as `Node.prepareJob`
(`Node.java:1077`) does, calls `job.run()`, streams events to stdout, and exits
with a status that encodes success/failure. This reuses the **entire** existing
execution path — `GitManagedJob.run()` (`GitManagedJob.java:443`), the
`AgentRunner` (`ClaudeCodeRunner`), `RestartGovernor`, the inactivity watchdog —
unchanged, inside the container.

Option (b) (a command line built by the Node, results read from files/exit status)
is rejected: it re-describes the job in a second form, cannot run an arbitrary job
type, and duplicates logic the codec already owns. It is exactly the shortcut
`flowtree/CLAUDE.md` warns against.

**Consequence:** a Docker Node can run *any* `Job` whose class is on the worker
image's classpath, because the worker decodes and runs it the same way a native
Node's worker thread does.

### Decision 2 — Container lifetime — **one container per job (ephemeral)**

**Recommendation: one fresh container per job.**

The current isolation guarantees are all per-container-start: each agent clones the
repo onto its own overlay filesystem (`entrypoint.sh:98-100`,
`docker-compose.yml:47-49`), gets an isolated Maven install space
(`install.sh:157-167`, `MavenInstallSpace`), and its own transcript sink. A
long-lived container that runs jobs in sequence would have to *reset* all of that
between jobs, and a missed reset is a silent cross-job data leak — precisely the
April 2026 collision class documented in the `entrypoint.sh` guard
(`entrypoint.sh:22-45`). Ephemeral containers get the same guarantees FlowTree has
today, by construction, with no reset logic to get wrong.

**How per-job state is provided** (this is what Decision 2 settles):

- **Workspace checkout** — the container clones into its container-private overlay
  FS at `/workspace/project`, exactly as today. Never a shared writable mount.
- **Maven install space** — the per-job split local repository
  (`MavenInstallSpace`) is created inside the container, as today.
- **Transcript volume** — the single sanctioned writable mount at
  `/agent-transcripts` is bound to a **per-job** host subdirectory (the host Server
  allocates a distinct path per launch), preserving the per-agent isolation that
  `tools/ci/validate_agent_volume_isolation.py` enforces today.

The slow cold start (image pull is cached; the cost is `git clone` + a cold Maven
resolve) is the price of isolation and is already paid on every container start
today. A **warm container pool** is a possible later optimization and is called out
as an explicit non-goal of this plan (see [§5 Risks](#5-risks) and Stage 4/5);
introducing it would require the reset discipline above and must not be smuggled in
under the isolation-critical stages.

### Decision 3 — Where the launch seam goes — **a method Node subtypes override**

**Recommendation: a `launch`/`execute` seam on `Node`, overridden by subtypes; and
`NodeGroup` constructs Nodes from typed configuration.**

The project's design rules ("A class IS what its name says"; honor the interface,
not the implementation) favour the subtype: a Docker Node *is* a `Node`. Introduce
a single protected seam the worker thread calls instead of `j.run()`:

```
// Node.java worker thread, today (line 419):
j.setExecutorService(pool);
j.run();

// after:
j.setExecutorService(pool);
launch(j);          // protected; native Node runs it in-process
```

`Node.launch(Job)` has the native body (`j.run()`); `DockerNode extends Node`
overrides it to encode the job, start the container worker, stream output, and map
the exit status back onto the job's completion. Everything else on `Node` — the
queue, `satisfies`, relay, peer connections, status — is inherited unchanged, so a
container Node takes part in routing and relay exactly as a native Node does
(task intent #4).

`NodeGroup` stops calling `new Node(this, i, ...)` directly (`NodeGroup.java:242`)
and instead asks a small factory to build the Node for each typed configuration
entry (`kind -> Node`). The relay Node (`NodeGroup.java:249`) stays a plain `Node`
with `role=relay`.

A launcher *object* a Node is configured with is rejected: it would make a Docker
Node a `Node` that merely *has* a launcher, inviting `instanceof`/cast checks and a
split of behaviour away from the type that owns it.

### Decision 4 — Configuration format — **a list of typed Node entries; per-Node labels**

**Recommendation: replace the scalar node count with an indexed list of typed Node
entries in the existing properties file, each carrying its own kind, labels, and
resource overrides.**

Today `NodeGroup` reads a single `nodes.initial` count (`NodeGroup.java:229`) and
builds homogeneous Nodes, and `NodeGroup.setLabel` broadcasts one label set to
*every* child Node (`NodeGroup.java:684-693`). That broadcast is the specific thing
that makes a mixed-kind Server impossible: a Server holding a macOS-native Node and
Linux Docker Nodes cannot give them all `platform=linux`.

New format (additive; parsed by `NodeGroupNodeConfig`, which already owns the
`nodes.*` dispatch table, `NodeGroupNodeConfig.java:101`):

```
# Typed node list. Index is the node id.
nodes.0.kind=native
nodes.0.labels.platform=macos          # or omitted: AutomaticLabel detects it
nodes.1.kind=docker
nodes.1.labels.platform=linux
nodes.1.image=flowtree/agent:latest
nodes.1.cpus=4
nodes.1.memory=4g
nodes.2.kind=docker
nodes.2.labels.platform=linux
```

- **Back-compat:** when no `nodes.<i>.kind` entries are present, fall back to the
  current scalar path — `nodes.initial` native Nodes with group-level labels —
  so existing `agent.properties` files are unchanged (Stage 1 requirement).
- **Per-Node labels:** labels move from group-broadcast to per-Node. `AutomaticLabel`
  (`platform`, `hostname`) still auto-detects values for the **native** Node (it
  describes the host the Server runs on); container Nodes take `platform` from their
  typed config, because the container's platform is a property of the image, not of
  the host. The native Node's `hostname` label continues to come from
  `AutomaticLabel.HOSTNAME`.
- **Routing is already per-Node:** `findNodeForJob` iterates child Nodes and checks
  each one's own `satisfies(requirements)` (`NodeGroupMetrics.java:249-269`), and the
  worker relays a mismatched job to the parent for re-routing
  (`Node.java:396-404`). So routing by `required_labels` keeps working per Node with
  **no change to the routing code** — the only change is that each Node now carries
  distinct labels instead of inheriting the group's. This is the single most
  important reason the redesign is tractable.

`NodeGroup.setLabel`'s broadcast is retained only for the legacy/back-compat path
(a homogeneous group); the typed path sets labels on each Node directly and does
not broadcast.

### Decision 5 — How the Server reaches the runtime — recommend CLI, host install default

**Recommendation: drive Docker/Colima through their **CLIs** (`docker run`,
`docker kill`, `docker rm`; Colima via its Docker context), not the socket API, at
least through Stage 4.** The host install is the **default** deployment.

CLI is simpler, adds no client-library dependency (honoring "do not modify
pom.xml"), is identical to what the compose/fleet scripts already shell out to, and
keeps the JVM from holding raw root-equivalent socket access. A Server that itself
runs in a container would need the host's Docker socket bind-mounted — root-
equivalent access to the host — which is exactly why the host install is the
default and the container-hosted Server is discouraged.

**Open questions:** whether a later stage moves to the Docker Engine API over the
socket for finer lifecycle control and native stdout attach (vs. parsing CLI
output); how Colima's `docker` context is selected per Node on a host that also has
Docker Desktop.

### Decision 6 — Progress, cancellation, silence — recommend stream + container kill

**Recommendation:** the boundary carries the same NDJSON event stream, and
cancellation becomes a container kill.

- **Progress / activity stream:** the container worker writes the same
  `stream-json` event stream to its stdout that `ClaudeCodeRunner` produces today
  (`flowtree/CLAUDE.md` §"silence budget"). The Docker Node reads the container
  process's stdout and forwards it to the job's output consumer, so the activity
  stream and transcript capture are unchanged from a consumer's point of view.
- **Inactivity:** the existing `AgentInactivityMonitor` /
  `AgentRunner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS` (35 min stdout silence,
  `AgentRunner.java:49`) runs **inside** the container (it is part of the job's own
  `run()` path), so it already governs the agent subprocess. The Docker Node adds a
  coarser outer guard: if the **container process** emits no output for a longer
  bound, the Node `docker kill`s it — this catches a container that wedges below the
  worker (e.g. a hung clone before the agent starts).
- **Cancellation:** today `Node.sendKill(task, relay)` (`Node.java:1116`) removes
  *queued* jobs with that task id and broadcasts `Message.Kill`, but a job already
  running keeps running — the controller only marks the record `CANCELLED`
  (`JobStatsStore.java:570-580`: "a cancelled job's agent keeps running"). For a
  Docker Node, `sendKill` must additionally `docker kill`/`docker rm` the container
  for the running task id, so cancellation actually reaches a containerized agent —
  a genuine *improvement* over today's native behaviour. The seam for this is a
  `Node` method the Docker subtype overrides (parallel to the launch seam).
- **A container that dies or never starts:** the Node treats a non-zero container
  exit as a job failure (`addFailedJob`, as today), logs it, and does **not** retry
  the container silently; a container that never starts (image/pull/daemon error)
  is reported, not gated into a skip (the "missing CI resource is reported" rule).

**Open questions:** exact outer-guard timeout relative to the inner 35-minute
window; whether a failed container start requeues to a peer or fails fast; how
mid-stream kill interacts with the in-container `RestartGovernor` relaunch loop.

### Decision 7 — Secrets and credentials — recommend per-Node injection at launch

**Recommendation:** the host Server holds the credential superset; each container
Node is injected only the credentials its job needs, at launch, via the container's
environment and read-only mounts — never a shared writable mount.

Today each container gets tokens/keys from compose env and `:ro` binds
(`docker-compose.yml:76-111`, `*vol-ssh`). In the new model the Server owns those
values and passes per-container `-e`/`--mount ...:ro` flags when it `docker run`s a
job, so a job in one container cannot read another's: distinct env, distinct
`/agent-transcripts` subdir, no shared writable volume (the `entrypoint.sh` guard
and `validate_agent_volume_isolation.py` continue to hold for the launched
containers).

**Open questions:** where the Server reads the credential superset from (an
operator env file like `macos/agent.env`, or the `ar-secrets` path); whether
per-workstream env (`CodingAgentJob` per-workstream env vars, `CodingAgentJob.java:127`)
is injected by the Server or remains inside the job record.

### Decision 8 — Resource limits — recommend runtime-enforced per-Node limits

**Recommendation:** the Server enforces per-Node CPU/memory through the runtime,
from the typed config, mirroring today's compose limits.

`docker-compose.yml:71-75` sets `deploy.resources.limits` (`AGENT_CPUS`,
`AGENT_MEMORY`). Those move to `nodes.<i>.cpus` / `nodes.<i>.memory` and become
`docker run --cpus/--memory` flags. GPU maps to Node kind: the native macOS Node has
Metal (the whole reason `macos/install.sh` exists — containers have no Metal,
`install.sh:6-8`); Linux Docker Nodes get CUDA/OpenCL via `--gpus` when configured.

**Open questions:** whether the native Node needs any Server-side cap at all (it
shares the host JVM); how GPU contention between several Docker Nodes on one host is
bounded.

### Decision 9 — Migration — recommend no wire change, parallel operation, retire last

**Recommendation:** the wire protocol between Servers does **not** change; the
Server-in-a-container layout keeps working throughout; the old layout is retired
only in the final stage.

Jobs still travel as encoded `Job`s and Nodes still relay exactly as now, so a
machine running the old compose and a machine running the new host Server
interoperate on the same network — a fleet migrates machine by machine, never all
at once. Stage 1 is behaviour-identical (native-only). Each later stage is opt-in by
configuration. The agent container stops starting a Server only in Stage 5, after
the host-install and Docker-Node paths are proven.

---

## 2. How the change maps onto the code

All paths verified against current `master`.

| Concern | File / symbol | Current behaviour | Change |
|---|---|---|---|
| In-process execution | `flowtree/runtime/.../node/Node.java:419` (worker thread, `j.run()`) | Worker dequeues, checks `satisfies`, runs `j.run()` in-process | Replace `j.run()` with `launch(j)` seam; native body unchanged |
| Node construction | `Node.java:368` (`Node(NodeGroup,int,int,int)`) | Single concrete class | Add `DockerNode`/`ColimaNode extends Node` overriding `launch` |
| Homogeneous build | `NodeGroup.java:229,242-251` (`nodes.initial`, `new Node(...)` loop, relay Node) | Builds N identical Nodes from a count | Build from typed `nodes.<i>.*` entries via a kind→Node factory; keep count path for back-compat |
| Label broadcast | `NodeGroup.java:684-693` (`setLabel` → all children) | One label set applied to every child | Per-Node labels from typed config; broadcast only on the legacy path |
| Config dispatch | `NodeGroupNodeConfig.java:101` (`setParam`), `:256` (`applyNodeLabels`) | `nodes.*` keys and `nodes.labels.*` | Parse `nodes.<i>.kind/image/cpus/memory/labels.*` |
| Routing | `NodeGroupMetrics.java:249-269` (`findNodeForJob`), `NodeGroup.java:1278-1282` (`routeJob`) | Least-active child whose own labels `satisfy` the job | **No change** — already per-Node; benefits directly from per-Node labels |
| Relay on mismatch | `Node.java:396-404` | Worker relays a mismatched job to parent for re-routing | **No change** — correctly refuses to run a wrong-kind job |
| Label matching | `NodeLabelMatcher.java:46`, `Node.satisfies` (`Node.java:355`) | `role=relay` never runs; empty reqs match any non-relay | **No change** |
| Auto labels | `AutomaticLabel.java:50` (`PLATFORM`), `:71` (`HOSTNAME`) | Detects host platform/hostname, applied to group | Apply to the **native** Node only; container Nodes take platform from config |
| Job execution path | `GitManagedJob.java:443` (`run()`, final) → `doWork()` → `AgentRunner` | Clone, lock, prepare, `doWork`, commit; agent via runner | **No change** — runs unchanged inside the container worker |
| Serialization | `Node.java:430` (`toWire`), `:1063` (`instantiateJobClass`), `CodingAgentJobCodec`, `GitManagedJobCodec` | Encode/decode jobs over the wire and for failed-job retry | Reused to carry the job into the container |
| Required labels | `CodingAgentJobFactory.java:1434` (`getRequiredLabels().forEach(job::setRequiredLabel)`) | Factory stamps required labels onto the job | **No change** — routing works per Node from these |
| Inactivity | `AgentRunner.java:49` (`DEFAULT_INACTIVITY_TIMEOUT_MILLIS`), `AgentInactivityMonitor` | 35-min stdout-silence kill of the agent process tree | Runs inside the container worker; Node adds an outer container guard |
| Cancellation | `Node.java:1116` (`sendKill`), `JobStatsStore.java:570-580` | Removes queued jobs, broadcasts `Message.Kill`; running agent keeps going | Docker Node also `docker kill`s the running task's container |
| Container entry | `flowtree/runtime/agent/entrypoint.sh:160-163` (`exec java io.flowtree.Server`) | Container starts a full Server | New `CodingAgentJobWorker` main; no Server in the container (Stage 2/5) |
| Pool definition | `flowtree/runtime/agent/docker-compose.yml:126-146` | Explicit `agent-N` services, each a Server | Retired; the host Server launches containers per job (Stage 5) |
| Host install model | `flowtree/runtime/agent/macos/install.sh`, `run.sh`, `*.plist` | Native host Server as a launchd daemon | The template for the Linux host install (Stage 3) |
| Server bootstrap | `flowtree/runtime/.../Server.java:395-397` (`new NodeGroup(p, …)`) | Builds the group from properties | **No change** to call site; the group reads typed config |

Key structural facts that make this safe:

1. **Routing is already per-Node and label-driven.** The hard part — selecting the
   right Node by capability — exists (`findNodeForJob`), and a mismatched job is
   relayed, not run (`Node.java:396-404`). The redesign mostly feeds it *distinct*
   per-Node labels instead of uniform ones.
2. **The job already serializes.** `toWire` / `instantiateJobClass` /
   `CodingAgentJobCodec` are exercised today by the failed-job retry path, so the
   container boundary needs no new serialization format.
3. **The host-install path exists.** `macos/install.sh` already installs and
   supervises a host Server as a daemon, builds/stages JARs atomically, verifies the
   controller connection, and provisions an isolated Maven space — the Linux host
   install is a port of it, not a new design.

---

## 3. Stages

Each stage is independently useful and reviewable. The task's suggested shape maps
one-to-one onto these five stages.

### Stage 1 — Launch seam + typed Node config (native only) — *behaviour-identical*

**Changes**
- Add the `launch(Job)` seam to `Node`; the native body is today's `j.run()`
  (`Node.java:419`). The worker thread calls `launch(j)`.
- Add typed-config parsing to `NodeGroupNodeConfig` (`nodes.<i>.kind=native`,
  `nodes.<i>.labels.*`) and a kind→Node factory used by `NodeGroup` instead of the
  bare `new Node(...)` loop. Only `kind=native` is implemented.
- Move per-Node labels off the `setLabel` broadcast for the typed path; keep the
  broadcast for the legacy `nodes.initial` path.
- No `agent.properties` change ships; the absence of `nodes.<i>.kind` selects the
  legacy path.

**Tests that prove it**
- `NodeTest`, `NodeGroupTest`, `NodeGroupInitTest` — unchanged behaviour for the
  legacy count path.
- `NodeLabelRoutingTest` — routing still selects by per-Node labels; add a case
  with two typed native Nodes carrying *different* labels and assert each job lands
  on the matching Node (this is the new capability the broadcast previously made
  impossible).
- A new unit test that a `native`-kind typed config produces a Node whose `launch`
  runs the job in-process (observe the job ran on the same thread, no container).

**Rollback / migration**
- Pure addition behind config. Reverting the typed-config parse leaves the legacy
  path. No deployment changes: existing agents read unchanged `agent.properties`
  and behave exactly as before.

### Stage 2 — Docker Node + container worker

**Changes**
- `DockerNode extends Node` overriding `launch`: encode the dequeued job, allocate a
  per-job transcript subdir, `docker run` the worker image with per-Node
  `--cpus/--memory`, the job bytes, and per-job credentials/`:ro` mounts; stream
  stdout to the job's output consumer; map exit status to completion/failure.
- New `io.flowtree.jobs.CodingAgentJobWorker` main: decode one job, wire output,
  `job.run()`, exit with status. No `Server`, no `NodeGroup`.
- `DockerNode.sendKill` override: `docker kill`/`rm` the running task's container.
- A worker image (reusing the existing `Dockerfile`) whose entrypoint runs the
  worker main instead of the Server. The existing writable-mount guard
  (`entrypoint.sh:46-110`) is retained for the worker.

**Tests that prove it**
- Round-trip: `GitManagedJobSerializationTest.roundTripFactory` already proves a
  `CodingAgentJob` survives encode/decode — extend it to assert the worker-side
  decode reconstructs the same job properties (the boundary carries the full job).
- A `DockerNode` unit test with a **stub launcher** (no real Docker) asserting:
  job encoded once; mismatched-label job is never launched (relayed instead); a
  non-zero container exit becomes `addFailedJob`; `sendKill` invokes the container
  kill for a running task id.
- Isolation assertion reusing `validate_agent_volume_isolation.py`'s invariant: the
  per-job launch binds a distinct `/agent-transcripts` subdir and no other writable
  mount.

**Rollback / migration**
- Docker Nodes exist only when configured (`nodes.<i>.kind=docker`). A host with no
  such entries is pure Stage 1. Reverting disables the kind; native Nodes and the
  old compose layout are untouched.

### Stage 3 — Linux host install path (modelled on macOS)

**Changes**
- A `flowtree/runtime/agent/linux/` install path mirroring `macos/`: build/stage/
  swap JARs atomically (`install.sh:204-262`), a systemd unit in place of the
  launchd plist, the same split-Maven-repo precondition (`install.sh:157-167`), and
  the same controller-connection verification (`install.sh:442-453`).
- The host Server runs native + Docker Nodes from one typed `agent.properties`.

**Tests that prove it**
- Script-level: a dry-run/CI lint of the install script (shellcheck + the existing
  `--no-build` path) and a unit/integration check that the rendered unit matches the
  registered one, mirroring the macOS `cmp -s` guard (`install.sh:355-361`).
- An end-to-end smoke (ops-gated, not in the unit suite): one host Server with one
  native and one Docker Node takes one job of each kind through the controller.

**Rollback / migration**
- Additive; the macOS and container deployments are unaffected. A Linux host can run
  the old compose until its host Server is installed and drained onto.

### Stage 4 — Colima (and further runtimes) as Node kinds

**Changes**
- `ColimaNode` — a `DockerNode` specialization selecting Colima's `docker` context
  (or a thin subtype if the CLI differs). This is where **task 609f5405 (the Colima
  migration) plugs in**: Colima is not a parallel migration but *one more Node kind*.
  A machine using Colima configures `nodes.<i>.kind=colima`; the launch seam and the
  worker are shared with the Docker Node, so Colima inherits streaming, cancellation,
  limits and isolation for free.
- Any other runtime (e.g. a remote builder) is a further `Node` subtype behind the
  same seam.

**Tests that prove it**
- The `DockerNode` stub-launcher tests parameterized over the Colima context.
- An ops-gated smoke on a Colima host.

**Rollback / migration**
- A new kind behind config; reverting removes the kind. Colima and Docker hosts
  coexist on the same FlowTree network because the wire protocol is unchanged.

### Stage 5 — Operations and retirement of the Server-in-a-container layout

**Changes**
- Port deploy / drain / rebuild / fleet scripts from "an agent is a container
  running a Server" to "a host Server with configured Nodes": draining a machine
  drains its host Server; rebuild restarts one host Server, not N containers.
- The agent image's entrypoint stops starting a `Server`
  (`entrypoint.sh:160-163`) — the image is now only the per-job worker.
- Retire `docker-compose.yml`'s explicit `agent-N` Server services
  (`docker-compose.yml:126-146`).
- Update architecture/operations docs (`flowtree/runtime/README.md`,
  `flowtree/runtime/docs/node-relay.md`, operations docs) to the new model.

**Tests that prove it**
- CI lint of the changed scripts; `validate_agent_volume_isolation.py` updated to
  assert the per-job launch isolation instead of per-service compose isolation.
- Documentation review; the "done when" checklist in the tracker task.

**Rollback / migration**
- This is the only stage that removes the old path, and it runs last — after the new
  path is proven on at least one host. Until then, old and new layouts interoperate,
  so retirement is a fleet-wide cutover done machine by machine, reversible by
  re-enabling a machine's compose stack.

---

## 4. The controller's own deployment is not disturbed

The controller Server is constructed the same way (`Server.java:395`); none of the
stages change controller code. The controller keeps routing by `required_labels`
and relaying to peer Servers exactly as today, because the wire protocol and
`routeJob`/`findNodeForJob` are unchanged. A host Server is just another peer that
happens to own container Nodes.

---

## 5. Risks

| Risk | Why it matters | Mitigation this plan relies on |
|---|---|---|
| **Wider blast radius** | One Server per machine means one failure stops every agent on it, where today a container fails alone. | A container Node's failures are contained in its container; the Node maps a container crash to a job failure (`addFailedJob`) and never propagates it into the Server process. A Node kind must not run host code that can wedge the shared JVM: the Docker Node only shells out (CLI) and reads a stream — it never runs the agent in-process. Stage 1 is behaviour-identical, so the shared-process risk is introduced only with Docker Nodes, incrementally. |
| **Host access** | A Server that can start containers can do anything the runtime can; on shared machines that is real privilege and changes what the isolation/exfil controls protect. | Host install is the **default** (Decision 5), so the Server runs as an ordinary account (as `macos/install.sh` already does — never root, `install.sh:20-29`), not as a container with the host Docker socket. The exfil/mount guards (`entrypoint.sh`, `validate_agent_volume_isolation.py`) continue to apply to each launched container. |
| **Isolation regressions** | Today isolation of filesystem, Maven repo, transcripts, credentials is by construction (separate containers); each becomes something the Docker Node must set up, and a mistake is silent. | One container per job (Decision 2) keeps the by-construction guarantees. The per-job launch reuses the existing in-container guard (`entrypoint.sh`) and the CI isolation validator, extended in Stage 5 to assert the per-job launch (distinct transcript subdir, no shared writable mount). No warm-pool reset logic is introduced in the isolation-critical stages. |
| **Relay with mixed Node kinds** | Relay/peer selection treats Nodes in a group as interchangeable; mixed kinds make that false, and a job relayed to the wrong kind must be refused, not run in the wrong environment. | Already enforced: `findNodeForJob` only returns a Node whose own labels `satisfy` the job (`NodeGroupMetrics.java:256`), and a worker that still receives a mismatch relays it rather than running it (`Node.java:396-404`). Per-Node labels (Decision 4) make "wrong kind" visible to this existing machinery. Residual gap: a job with **no** required labels can run on any kind — mitigated by having `CodingAgentJobFactory` stamp a `platform` requirement (it already stamps required labels, `:1434`) so platform-specific work is always routed. |
| **Operational change** | Deploy/rebuild/drain/fleet scripts are written around "an agent is a container running a Server"; each needs an equivalent, and the controller's deployment must not be disturbed. | Stage 5 ports each script with the old layout still working until then; the host-install scripts are a port of the proven `macos/` scripts; the controller is untouched ([§4](#4-the-controllers-own-deployment-is-not-disturbed)). |

---

## 6. No new Maven module

**No new Maven module is required.** The new code lands in existing modules:

- `Node`, `DockerNode`, `ColimaNode`, the kind→Node factory, and typed-config
  parsing → `io.flowtree.node` in **`flowtree/runtime`** (where `Node`/`NodeGroup`
  already live).
- `CodingAgentJobWorker` → `io.flowtree.jobs` in **`flowtree/runtime`** (where
  `CodingAgentJob` and its codec already live); it reuses `GitManagedJob.run()` and
  the `AgentRunner` contract in **`flowtree/agents`** unchanged.
- The container worker is a build artifact (a Docker image built from the existing
  `Dockerfile`), not a Maven module.
- The Linux host install is shell scripts under `flowtree/runtime/agent/linux/`,
  not a module.

If, during implementation, a stage appears to need a new module, the implementer
must **stop and report it** rather than create one (repository rule: agents never
create Maven modules). Nothing in this plan foresees that need.

---

## 7. Open questions carried forward

Settled: Decisions 1–4. Carried into their implementing stages: the runtime-access
transport (Decision 5, Stage 2+), outer-guard timeout and container-death semantics
(Decision 6, Stage 2), credential-source and per-workstream env injection
(Decision 7, Stage 2/3), native-Node resource caps and multi-Node GPU contention
(Decision 8, Stage 2/4), and the warm-container-pool optimization (explicitly out of
scope; revisit only after Stage 5).
