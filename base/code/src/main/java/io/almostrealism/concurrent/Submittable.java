/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.concurrent;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationTimingListener;
import io.almostrealism.profile.OperationWithInfo;
import io.almostrealism.streams.Semaphore;

import java.util.ArrayList;
import java.util.List;

/**
 * An executable operation that can be submitted for asynchronous execution, chaining on a
 * prior operation's completion and yielding its own completion {@link Semaphore} without
 * (necessarily) blocking the host.
 *
 * <p>This is the operation side of the async-execution contract &mdash; the analog, for
 * operations that produce no value, of {@code io.almostrealism.streams.StreamingEvaluable}
 * for {@code Evaluable}. It belongs on the <em>executable</em>, so that any supplier of one
 * (any {@code ParallelProcess} whose compiled result implements it) can participate; it is
 * deliberately not tied to any single composite implementation.</p>
 *
 * <p>It lets a composite thread each member's completion into the next member's
 * {@code dependsOn} and issue a single completion wait at the end, moving the per-operation
 * wait down into the underlying compute provider. A provider that supports asynchronous
 * dispatch returns a live {@link Semaphore} backed by its native completion primitive (an
 * OpenCL {@code cl_event}, a Metal {@code MTLSharedEvent}, ...); a provider that does not
 * returns an already-completed semaphore, so chaining degrades transparently to sequential
 * synchronous execution.</p>
 *
 * <p>An operation that cannot be submitted at all takes part through {@link #of(Runnable)}: it
 * can only run on the host, so its submission waits for the work it depends on, runs it, and
 * leaves nothing in flight.</p>
 */
public interface Submittable {

	/**
	 * Submits this operation for execution, optionally chaining on a prior operation's
	 * completion, and returns this operation's completion {@link Semaphore}.
	 *
	 * @param dependsOn the prior operation's completion that the provider should wait on
	 *                  (inside the provider) before this operation runs, or {@code null} to
	 *                  begin a chain
	 * @return this operation's completion semaphore (possibly already complete), suitable as
	 *         the next operation's {@code dependsOn}; may be {@code null} if the provider
	 *         publishes no completion handle (fully synchronous execution)
	 */
	Semaphore submit(Semaphore dependsOn);

	/**
	 * Submits this operation as {@link #submit(Semaphore)} does, recording with {@code timing}
	 * the time the host takes to issue it.
	 *
	 * <p>The time is recorded under this operation's {@link OperationInfo#getMetadata() metadata},
	 * when it has any, as an operation run by {@code requester}, so a profile attributes it to the
	 * operation rather than to an anonymous entry created afresh for every call. Only the issue is
	 * timed; work that completes asynchronously finishes after the time is recorded.</p>
	 *
	 * @param requester the metadata of the operation issuing this one, or {@code null}
	 * @param dependsOn the completion this operation must be ordered after, or {@code null}
	 * @param timing    the listener that records the time taken to issue this operation
	 * @return this operation's completion, as {@link #submit(Semaphore)} returns it
	 */
	default Semaphore submit(OperationMetadata requester, Semaphore dependsOn, OperationTimingListener timing) {
		Semaphore[] completion = { dependsOn };
		Runnable submission = () -> completion[0] = submit(completion[0]);

		OperationMetadata metadata = OperationInfo.metadataForValue(this);
		timing.recordDuration(requester,
				metadata == null ? submission : OperationWithInfo.RunnableWithInfo.of(metadata, submission));
		return completion[0];
	}

	/**
	 * Returns {@code operation} as a {@link Submittable}: the operation itself when it is one, and
	 * otherwise its submission as an operation that can only run on the host.
	 *
	 * <p>Running on the host requires everything ahead of the operation to be complete, so such a
	 * submission waits for its dependency, runs the operation synchronously and returns
	 * {@code null}, because nothing remains in flight. This is how a composite issues an
	 * arbitrary sequence of operations while waiting only where a member actually requires it.
	 * Its {@link #submit(OperationMetadata, Semaphore, OperationTimingListener) timed submission}
	 * records the run of the operation itself, under the operation's own metadata, and not the
	 * wait for the work ahead of it.</p>
	 *
	 * @param operation the operation to submit
	 * @return {@code operation} itself if it is {@link Submittable}, otherwise a submission that
	 *         runs it on the host
	 */
	static Submittable of(Runnable operation) {
		if (operation instanceof Submittable) return (Submittable) operation;

		return new Submittable() {
			@Override
			public Semaphore submit(Semaphore dependsOn) {
				if (dependsOn != null) dependsOn.waitFor();
				operation.run();
				return null;
			}

			@Override
			public Semaphore submit(OperationMetadata requester, Semaphore dependsOn,
									OperationTimingListener timing) {
				return Submittable.of(() -> timing.recordDuration(requester, operation)).submit(dependsOn);
			}
		};
	}

	/**
	 * Submits a group of independent operations that share a single upstream dependency, returning
	 * the merged completion of all of them.
	 *
	 * <p>Every operation is submitted with the same {@code dependsOn}: the group members do not
	 * depend on one another, only on the work {@code dependsOn} represents. A provider that batches
	 * dispatches is therefore free to group them (they carry no ordering constraint between
	 * themselves), while their shared dependency is still honored.</p>
	 *
	 * <p>The returned completion covers the whole group, built with {@link Semaphore#all(List)}:
	 * waiting on it waits for <em>every</em> submission. The completion of the last one alone would
	 * not, now that a submission may complete asynchronously &mdash; group members submitted with
	 * the same dependency run concurrently rather than in sequence, so the last to be submitted is
	 * not necessarily the last to finish. When a single submission remains, {@code all} returns its
	 * completion directly, so the common one-member case keeps the provider's own completion handle
	 * and costs nothing.</p>
	 *
	 * <p>When a submission throws, the members already submitted are left with no completion
	 * that reaches the caller, yet they may still be running against resources the caller
	 * releases once it sees the failure. Before the failure propagates, each of them is
	 * therefore waited for until it has settled (a failure of its own is attached to the
	 * propagating one as suppressed), so a group submission that throws never leaves work in
	 * flight. This host wait happens only on the failure path.</p>
	 *
	 * <p>The wait is deliberately here rather than left to the caller with the started
	 * completions attached to the failure: every caller would have to perform the same wait,
	 * because a completion from a batching backend (Metal) settles only once something commits
	 * its command buffer, and a passive callback on it never fires if nothing else does.</p>
	 *
	 * @param operations the operations to submit, in order
	 * @param dependsOn  the completion the whole group depends on, or {@code null} to begin a chain
	 * @return the merged completion of the submitted operations, or {@code null} when
	 *         {@code operations} is empty (or every submission published no completion handle)
	 */
	static Semaphore submit(List<Submittable> operations, Semaphore dependsOn) {
		List<Semaphore> completions = new ArrayList<>(operations.size());

		try {
			for (Submittable operation : operations) {
				completions.add(operation.submit(dependsOn));
			}
		} catch (RuntimeException | Error e) {
			for (Semaphore started : completions) {
				if (started != null) started.waitForSettled(e);
			}

			throw e;
		}

		return Semaphore.all(completions);
	}
}
