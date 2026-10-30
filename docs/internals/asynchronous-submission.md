# Asynchronous Submission

How to run a sequence of operations without waiting between them, and where the work of ordering
them belongs. Read this before writing any loop that issues compiled operations, any code that
calls `Semaphore.waitFor()`, and any change to a `Submittable`, `Semaphore` or backend dispatch.
The backend mechanics are in [semaphore-coordination.md](semaphore-coordination.md).

## The contract

`Submittable.submit(Semaphore dependsOn)` issues an operation to run after the work `dependsOn`
stands for, and returns the operation's own completion. The caller does three things and nothing
else:

1. **Chain.** Pass each submission the completion of the one before it, and keep only the latest.
2. **Submit host work the same way.** Code that must run on the host takes part through
   `Submittable.of(runnable)`, which waits for its dependency, runs, and returns `null`. A host
   step is therefore exactly where the host waits, and nowhere else.
3. **Wait once, where host code needs the result.** Call `waitFor()` on the latest completion at a
   true host boundary: the end of a run, a read of device memory, a return to user code.

```java
Semaphore pending = null;
for (Submittable operation : operations) {
    pending = operation.submit(pending);   // never waits; the receiver orders it
}
if (pending != null) pending.waitFor();     // one wait, at the boundary
```

That is the whole of the caller's job, for one operation or for a million, for one backend or a mix
of native, Metal, OpenCL and host operations. `SimulationRunner.run` and
`OperationListRunner.submit` are written exactly this way.

The single wait is also where a failure arrives. Work that failed anywhere in the chain (a native
worker that threw, a Metal command buffer the GPU killed, host work that threw) is not settled as
if it had run: every completion ordered after it rethrows the failure, so the one `waitFor()` at
the end throws instead of returning a plausible but wrong result. The caller needs no check of its
own between submissions; the per-backend rules are invariant 7 in
[semaphore-coordination.md](semaphore-coordination.md).

## Ordering is the receiver's job

The implementation that receives `dependsOn` decides how to order after it: a Metal dispatch after
another Metal dispatch of the same runner is ordered by the command buffer, a native dispatch is
started when its dependency settles, a dispatch after a completion its backend cannot express
natively is bridged. The caller never needs to know which, and must not try to find out.

### What a caller must never do

- **Never inspect a completion to decide whether to wait.** No `instanceof` on a `Semaphore`, no
  method asking a semaphore whether it is "ordered on the device", no check of which backend
  produced it. Whether a dependency can be honoured without a host wait depends on the *receiver*,
  not on the semaphore, so a semaphore cannot answer the question, and a caller that asks it has
  taken over the receiver's job.
- **Never wait before submitting.** `dependsOn.waitFor(); op.submit(null);` serializes the host
  with the device. Pass `dependsOn` instead.
- **Never cap how far ahead it submits.** A "maximum steps in flight" or "wait every N submissions"
  setting in calling code compensates for a backend that cannot keep up. If the backend needs a
  bound, the backend applies it, at the resource that actually runs out (see below).
- **Never add a second submission method** (`chainOnDevice`, `submitOrdered`, ...) whose meaning
  depends on which backend is underneath. There is one: `submit`.

### When chaining does not work, the backend is broken

If chaining N submissions is slower than waiting after each, grows threads or memory with N,
produces wrong values, or hangs, the defect is in a receiver, and the fix goes there. Find it with
evidence before changing anything:

- a thread dump taken while a long chain is in flight shows what each pending submission holds
  (group the threads by stack; a group that grows with the chain is the defect);
- a long chain with one wait at the end, checked against the value the chain must produce, shows
  whether ordering holds (`ChainedSubmissionTest` does this for one backend and for alternating
  backends, and checks the thread count);
- backend warnings such as `commandBufferError=` in the log show work the device discarded.

Two receiver defects of this kind, both found by chaining plainly and both fixed in the receiver:

- **A thread per pending submission.** Every composition primitive on `Semaphore` (`then`,
  `onComplete`, `all`) and every native dispatch waited for its dependency on a parked thread, so a
  chain of N submissions held N threads. The fix was `Semaphore.onSettled`: completions that are
  told when their work settles (`LatchSemaphore`, `DeferredSemaphore`) register the callback
  instead, and the primitives and `NativeExecution` are built on it.
- **GPU work waiting for the host.** A Metal dispatch after a native one waits on an event the host
  signals. Committed ahead of that signal, hundreds of such command buffers sat on the GPU, and some
  outlived the GPU timeout and were killed, so their dispatches silently never ran. The fix was in
  `MetalCommandRunner`: a buffer is committed only once its bridges are signaled, and at most a
  bounded number of buffers (`MAX_HELD`) wait that way before any further dispatch that would open
  a new buffer, bridged or not, waits on its submitting thread.

The second fix shows what a legitimate bound looks like: it lives in the receiver, it is sized by
the resource that runs out (a command queue allows a bounded number of uncompleted command
buffers), and it costs nothing when nothing is waiting for the host. The bound is now part of the
`SubmissionStream` core every device runner is built on, so the CUDA runner applies the same kind
of bound to the submissions it holds behind a foreign dependency.

## Host work and listeners

A step that mixes host and device work is still one chain. Host work is a `Submittable.of`
submission, device work is a compiled operation, and both are submitted in program order:

```java
pending = Submittable.of(() -> listener.accept(step)).submit(pending);  // waits: host code runs
pending = input.submit(pending);                                         // device: no wait
pending = step.submit(pending);
```

Work that a listener would do on the device belongs in a compiled operation submitted next to it,
not inside the listener, so that only the code that truly runs on the host makes the host wait.

## Writing a receiver

A `Submittable` or backend dispatch that receives `dependsOn`:

- orders after it without blocking the submitting thread, and without holding a thread for as long
  as the dependency is pending; `dependsOn.onSettled(start)` is the primitive for starting work when
  a dependency it cannot express natively settles;
- returns its own completion, which must settle only once the work has finished (or failed), and
  must rethrow from `waitFor()` when the work failed or did not run because `dependsOn` failed;
- applies any bound it needs itself, at the resource that runs out;
- when it executes its work in order on a device queue, extends `SubmissionStream`, which
  supplies the submission ordinal, same-stream ordering and merging, admission versus
  destruction, the hold bound, and the `FailureRecord` that fails every completion at or after a
  failed one (the shared model is described in
  [semaphore-coordination.md](semaphore-coordination.md#the-shared-runner-core));
- implements `onSettled` without parking a thread when its completion can be observed, and keeps
  the default (which waits, and so drives a completion that only progresses when waited for) when
  it cannot. `onSettled` callbacks always run on a background thread, never on the registering one.

## See also

- [semaphore-coordination.md](semaphore-coordination.md) — invariants and per-backend mechanics
- [simulation-loop-examples.md](simulation-loop-examples.md) — `SimulationRunner`, the canonical chained loop
