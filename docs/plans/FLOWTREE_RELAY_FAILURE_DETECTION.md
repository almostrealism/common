# FlowTree Relay Failure Detection

Status: Proposed
Owner: TBD

## TL;DR

When the controller relays a job to a peer that disconnects within milliseconds
of receiving it (observed 2026-05-28 with job `b21082d5-b985-485e-9fd2-c2cfc28c45f8`),
the job is silently lost. The relay code in `Node.java` treats "the bytes left
the socket" as success, the receiving `NodeProxy` then disconnects, and nothing
requeues the job or moves it out of `STARTED` in `JobStatsStore`. From the
outside the job appears to be running forever.

This document proposes a layered set of fixes: peer-side ACK on job receipt,
controller-side in-flight bookkeeping with disconnect-driven requeue, and a
`STARTED → FAILED` transition when an in-flight job's owning peer is lost. The
goal is that **no peer disconnect, hard kill, or network glitch can produce a
job that is neither running nor reported as failed.**

## Background

### The observed incident

Controller log excerpt (`controller-flowtree-controller-1`, 2026-05-28):

```
[22:56.24] FlowTreeApiEndpoint: Submitted job via API: b21082d5-...
[22:56.29] Node ... Relaying job b21082d5-... to peer
            Connection from Node 0 to remote node 1 (/172.66.149.238)
[22:56.29] NodeProxy (/172.66.149.238): Notifying listeners of disconnection (3).
[22:56.29] NodeGroup ... Dropped server /172.66.149.238
[22:56.29] Connection from Node 0 to remote node 1 (/172.66.149.238) : Disconnected
[22:56.29] Node ... Relayed first job (1.0).
```

The 4-ms gap between `Relaying job` and `Disconnected` is the bug surface. The
controller's last log line about this job is `Relayed first job` — the relay
counter increments and the controller's view of the world is "dispatched."
`workstream_get_job` continues to report `STARTED` indefinitely; the
`JobCompletionListener` chain (`SlackNotifier.onJobCompleted`,
`JobStatsStore`) is never invoked.

### Where the relevant code lives

- `flowtree/runtime/.../node/Node.java:1500-1543` — the relay loop. Selects a
  peer, calls `Job j = this.nextJob()` (which **removes** the job from the
  queue), then `c.sendJob(j)`. On `SocketException` it removes the peer from
  `this.peers` and logs `Dropped peer connection`, but the job `j` is already
  out of the queue and is not put back.
- `flowtree/runtime/.../msg/Connection.java:143-149` — `sendJob` packages the
  job as a `Message` and calls `Message.send(id)`. There is no acknowledgement
  in the wire protocol; once the bytes are written, `sendJob` returns.
- `flowtree/runtime/.../msg/NodeProxy.java` — owns the socket and the disconnect
  notification path. Disconnect already fires a listener event (`Notifying
  listeners of disconnection`) that the controller currently uses only to
  remove the peer from its `NodeGroup` server list — not to recover jobs.
- `flowtree/runtime/.../controller/JobStatsStore.java` and
  `flowtree/runtime/.../jobs/JobCompletionListener.java` — the job lifecycle
  store. `onJobStarted` is called from `FlowTreeApiEndpoint` at submission
  time. `onJobCompleted` is called when a peer (or the controller itself)
  finishes the job. There is no `onJobLost` / `onJobFailed` for in-flight jobs
  whose peer connection died.

### Why the symptom is "silent" — three layers all assume success

1. **Wire protocol:** `Message.Job` has no ACK frame. The sender treats a
   successful `writeObject` as delivery confirmation.
2. **Relay accounting:** `Node.nextJob()` removes the job from the local queue
   *before* `sendJob`. There is no "in-flight to peer X" data structure;
   `totalRelay` is just a counter.
3. **Lifecycle reporting:** `JobStatsStore`/`SlackNotifier` only learn about a
   job's completion if a node reports it. Nothing reports "peer that owned
   this job is gone."

Each layer in isolation would be excusable; together they make any
disconnect-during-relay invisible.

## Goal & non-goals

### Goal

A peer disconnect — whether observed mid-relay or some minutes later while the
peer "owns" a job — must result in **exactly one** of:

- the job being requeued and successfully relayed to another peer; or
- the job being marked `FAILED` in `JobStatsStore` with a recorded reason
  (`peer_disconnected`) so the API, Slack notifier, and any external poller
  see a terminal state.

`STARTED` forever is not an acceptable outcome for any failure mode.

### Non-goals

- We are **not** trying to build exactly-once semantics. At-least-once is fine;
  duplicate execution is acceptable because individual jobs are designed to be
  idempotent at the workstream level (a duplicate job hits the branch-lock and
  aborts).
- We are **not** changing the controller-to-peer protocol from streaming
  messages to a request/reply RPC. The change is additive: ACKs and
  bookkeeping on top of the existing message channel.
- We are **not** addressing job loss caused by controller crashes here — that
  is a durability problem (persist the queue) and is separable.

## Proposal

Three coordinated changes — each useful on its own, but the three together
close the failure case end-to-end.

### 1. Peer ACK on `Message.Job` receipt

Add a `Message.JobAck` (or reuse the existing `Message.ConnectionConfirmation`
pattern) sent by the receiving `NodeProxy` as soon as the job is enqueued on
the receiver side — **before** any worker thread starts executing it.

- Sender side (`Connection.sendJob`): switch to `Message.send(id)` with a
  short-timeout wait for the ACK. Timeout should be small (e.g. 2 s) — the ACK
  is only confirming receipt and enqueue, not execution.
- Receiver side: in the message handler for `Message.Job`, after the job is
  added to the local queue, send back `Message.JobAck(jobId)`.
- On ACK timeout or `IOException`: `sendJob` throws, and the caller (the relay
  loop in `Node.java`) treats it the same as the existing `SocketException`
  branch — **except** it must put the job back (see §2).

This change alone closes the specific incident: if the peer disconnects within
4 ms of receiving the bytes, the ACK never arrives and the relay throws.

**Edge case — ACK arrives after a timeout-driven retry to a different peer.**
Acceptable: the original peer is now responsible for the duplicate, the branch
lock prevents real damage, and the duplicate is observable in the stats.

### 2. In-flight bookkeeping with disconnect-driven requeue

Add an `inFlight` map on `Node`, keyed by jobId, recording `(Job, Connection,
sentAt)` for every job that has been handed to a peer and is awaiting either
completion or an ACK.

- On successful ACK: remove from `inFlight` (the peer now owns it).
- On `sendJob` failure: put the job back at the head of `this.jobs` (so the
  next relay tick picks it up) and remove the dead peer from `this.peers`.
  This is the **specific** missing line from `Node.java:1535-1538` today.
- On `NodeProxy` disconnect for a peer that still has entries in `inFlight`:
  iterate those entries, decide per job whether to requeue or fail (see §3),
  and clear the entries.

To keep the diff manageable and respect the file-length advisory on
`Node.java` (1562 lines, soft limit 1500), the in-flight tracking should be
extracted into a small dedicated class — e.g. `RelayTracker` in
`io.flowtree.node` — rather than added as more state inside `Node`. This is
a good seam: the relay loop in `Node.java:1450-1548` is already the most
self-contained subsystem in the file and an obvious candidate for its own
class.

### 3. Lifecycle reporting: `peer_disconnected` is a terminal state

Add a third method to `JobCompletionListener`:

```java
default void onJobLost(String workstreamId, JobCompletionEvent event) {
    // default: route through onJobCompleted with a FAILED status
}
```

- Wire `Node` (via the new `RelayTracker`) to call `onJobLost` for every
  `inFlight` entry whose peer connection drops and which cannot be requeued
  (because we have no other peers, or because we have already attempted N
  retries — bound this to prevent ping-pong between two flaky peers).
- `SlackNotifier.onJobLost` posts a terminal message ("Job `<short id>` lost:
  peer disconnected after relay") and calls `JobStatsStore` to write a
  `FAILED` row with `errorMessage="peer_disconnected"`.
- `FlowTreeApiEndpoint` already exposes the last status event via
  `workstream_get_job`; once `JobStatsStore` has the `FAILED` row, the API
  surface is correct without further changes.

### Bonus: a controller-side heartbeat sweep

Even with §1 and §2, an edge case remains: peer ACKs the job, starts running
it, then the peer's process is hard-killed (no FIN, no RST until TCP keepalive
fires). The controller will think the job is still being executed for as long
as keepalive takes (default minutes).

A periodic sweep (e.g. every 30 s) over `inFlight`:

- For each entry older than a configurable threshold (default 5 min, override
  per `Workstream` since long jobs are legitimate), send a lightweight
  `Message.JobStatusQuery(jobId)` to the owning peer.
- Receiver responds with `RUNNING` / `UNKNOWN`.
- If the query itself fails (`IOException`), treat as disconnect and trigger
  the §3 path.

This is optional and can ship after §1–§3, but it is the only mechanism that
catches a peer that became unresponsive without closing the socket.

## Implementation order

1. **§1 (ACK)** — smallest behavior change, cleanest test surface. Add the
   new `Message` subtype and the `Connection.sendJob` ACK wait. Existing
   peers without the ACK code will time out, so this requires either a
   version bump or a feature flag on `Connection`. Probably easiest: send
   the ACK from `NodeProxy`'s `Message.Job` handler regardless, and have
   `sendJob` wait only when the new flag is negotiated at connection time
   (re-uses the existing `Message.ConnectionConfirmation` handshake).
2. **§2 (RelayTracker + requeue on send failure)** — landed in tandem with
   §1 because §1 is the trigger that makes the existing `SocketException`
   path fire. This step is also when the relay loop should be extracted
   from `Node.java` into its own class for the file-length reason cited
   above.
3. **§3 (`onJobLost` + `FAILED` row)** — small but observable: every
   integration that reads `workstream_get_job` or watches Slack will start
   seeing terminal events for previously-silent failures. Coordinate with
   the dashboards (`tools/mcp/manager`) so the new status is recognized.
4. **Heartbeat sweep** — optional, deferrable.

## Test plan

- **Unit:** `RelayTracker` add/ack/disconnect transitions, including the
  requeue-on-disconnect and the retry-bound. New tests in
  `flowtree/runtime/src/test/java/io/flowtree/node/`.
- **Integration:** in `flowtree/runtime/src/test/java/io/flowtree/...`, add a
  test that:
  1. Submits a job to a `FlowTreeController` test harness.
  2. The peer's `NodeProxy` is stubbed to close the socket immediately after
     receiving the `Message.Job` and before sending the ACK.
  3. Asserts the controller (a) does **not** lose the job — it ends up either
     re-relayed to a second peer or marked `FAILED` via `onJobLost` — and (b)
     the `JobStatsStore` row is in a terminal state within a bounded time.
- **End-to-end:** reproduce the original incident by killing one
  `flowtree-agent-*` container in the middle of a relay (a `docker kill` after
  intercepting the controller log line `Relaying job`) and verify the
  controller either retries or reports `FAILED`.

## Open questions

- **How many retries before `FAILED`?** Proposal: max 2 re-relays per job,
  configurable per workstream. The branch-lock makes more retries
  low-value (the first re-execution will either succeed or hit the lock).
- **Where does the requeue go — head, tail, or by submission time?** Head, so
  failed-to-relay jobs preempt the next normal selection and don't get stuck
  behind a flood of new submissions.
- **Should `onJobLost` post to Slack?** Yes, but quietly — a reply on the
  original `onJobStarted` Slack thread, not a new top-level message. This
  matches how `onJobCompleted` already behaves.
- **Persistence of `inFlight` across controller restart?** Out of scope here.
  A controller crash with jobs in flight is the durability problem mentioned
  under non-goals; if/when the job queue is persisted, `inFlight` should be
  persisted alongside it.

## Related

- `JOB_MESSAGE_INBOX.md` — separate work on inbound message handling; the ACK
  message added here should be routed through the same inbox abstraction once
  that lands.
- `FLOWTREE_WORKSTREAM_JOB_CONTROLS.md` §4 (completion signal) — `onJobLost`
  is the failure-side companion of the completion-signal webhook proposed
  there; both should be exposed through the same notification surface.
- Memory `0a646d29-75fa-4270-90b0-1f18abca6d2e` (bugs namespace) — the
  original incident report for job `b21082d5`.
