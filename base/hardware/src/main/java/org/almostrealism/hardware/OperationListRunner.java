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

package org.almostrealism.hardware;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationTimingListener;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.List;

/**
 * Compiled runner for executing a sequence of operations with metadata and timing support.
 *
 * <p>Produced by {@link OperationList#get()} when the list cannot be compiled into a single
 * kernel. Members that are {@link Submittable} are dispatched without a host wait, each
 * chaining on the previous member's completion {@link Semaphore}, so ordering holds across
 * {@link io.almostrealism.code.ComputeContext}s while a single wait at the end (or before a
 * non-submittable member) covers the whole group.</p>
 *
 * <p>The runner is itself {@link Submittable}: {@link #submit(Semaphore)} issues the group
 * chained on a prior completion and returns the group's completion without waiting for it.
 * A runner nested in another runner is therefore chained like any other member rather than
 * waited for, and a loop that issues the same runner repeatedly (a simulation step, for
 * example) can chain each issue on the last, waiting only where its own host code requires
 * the work to be complete.</p>
 */
public class OperationListRunner implements Runnable, Submittable, Destroyable, OperationInfo, ConsoleFeatures {
	/** Enable logging of operation list execution (controlled by AR_HARDWARE_RUN_LOGGING environment variable). */
	public static boolean enableRunLogging = SystemUtils.isEnabled("AR_HARDWARE_RUN_LOGGING").orElse(false);

	/** Metadata describing this runner for profiling and identification. */
	private OperationMetadata metadata;
	/** Sequence of compiled runnables to execute when this runner is invoked. */
	private List<Runnable> run;
	/** Compute requirements constraining which backend executes this runner; may be null. */
	private List<ComputeRequirement> requirements;
	/** Listener notified with timing information after each run; may be null. */
	private OperationTimingListener timingListener;

	/**
	 * Creates a runner for the specified operations.
	 *
	 * @param metadata Operation metadata for identification
	 * @param run List of runnables to execute sequentially
	 * @param requirements Compute requirements (may be null)
	 * @param timingListener Timing listener for profiling (may be null)
	 */
	public OperationListRunner(OperationMetadata metadata, List<Runnable> run,
							   List<ComputeRequirement> requirements,
							   OperationTimingListener timingListener) {
		this.metadata = metadata;
		this.run = run;
		this.requirements = requirements;
		this.timingListener = timingListener;
	}

	/**
	 * Returns the operation metadata.
	 *
	 * @return The metadata
	 */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Returns the list of operations to execute.
	 *
	 * @return List of runnables
	 */
	public List<Runnable> getOperations() { return run; }

	/**
	 * Executes all operations in sequence with compute requirements and timing, and waits
	 * once for the whole group: this is {@link #submit(Semaphore) submit(null)} followed by a
	 * wait on the returned completion.
	 */
	@Override
	public void run() {
		Semaphore pending = submit(null);

		if (pending != null) {
			pending.waitFor();
		}
	}

	/**
	 * Issues all operations in sequence, the first chained on {@code dependsOn}, and returns
	 * the completion of the group without waiting for it.
	 *
	 * <p>{@link Submittable} members are issued without a host wait, each chaining on the
	 * previous member's completion {@link Semaphore}, so ordering holds even when
	 * consecutive members execute on different compute contexts (a Metal member followed
	 * by a native member, for example, where issue order alone guarantees nothing). A
	 * provider that already orders same-context work treats a same-batch dependency as
	 * free &mdash; Metal's in-buffer hazard tracking covers a dependency still in the open
	 * command buffer without a commit or an event wait &mdash; so chaining does not defeat
	 * batching, and a foreign dependency is honored by the receiving provider. The only
	 * host wait is before a non-submittable member, which requires everything ahead of it
	 * to be complete (see {@link Submittable#of(Runnable)}).</p>
	 *
	 * <p>The compute requirements pushed here are thread-local; each member's dispatch
	 * captures them on this thread and re-establishes them on whatever thread performs
	 * the asynchronous work (see {@code AcceleratedOperation.apply}).</p>
	 *
	 * <p>When a timing listener is configured, each member records the time taken to issue it
	 * under its own {@link OperationInfo#getMetadata() metadata} (see
	 * {@link Submittable#submit(OperationMetadata, Semaphore, OperationTimingListener)}), so a
	 * profile attributes it to the operation rather than to an anonymous entry created afresh
	 * for every call.</p>
	 *
	 * <p>If a member fails to issue, the work issued before it (and so everything it was
	 * chained on) is waited for before the failure propagates, with any failure of that work
	 * attached as suppressed (see {@link Semaphore#waitForSettled(Throwable)}), so a caller
	 * that sees the failure never releases memory that work is still using.</p>
	 *
	 * @param dependsOn the completion the first operation must be ordered after, or
	 *                  {@code null} to begin a chain
	 * @return the completion of the last operation, or {@code null} when nothing issued
	 *         remains in flight (the last member ran on the host, or none published a
	 *         completion), or {@code dependsOn} itself when the list has no operations
	 */
	@Override
	public Semaphore submit(Semaphore dependsOn) {
		try {
			if (requirements != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(requirements);
			}

			Semaphore pending = dependsOn;

			try {
				for (int i = 0; i < run.size(); i++) {
					Runnable r = run.get(i);
					Submittable member = Submittable.of(r);

					// Chain on the prior member; see the method javadoc
					if (timingListener == null) {
						pending = member.submit(pending);
					} else {
						if (enableRunLogging) log("Running " + OperationInfo.display(r));
						pending = member.submit(getMetadata(), pending, timingListener);
					}
				}
			} catch (RuntimeException | Error e) {
				// Members issued before the failure may still be running against shared memory
				if (pending != null) pending.waitForSettled(e);
				throw e;
			}

			return pending;
		} finally {
			if (requirements != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	/**
	 * Returns a description of this runner.
	 *
	 * @return The short description from metadata
	 */
	@Override
	public String describe() {
		return getMetadata().getShortDescription();
	}

	/**
	 * Destroys all operations and releases resources.
	 */
	@Override
	public void destroy() {
		if (run == null) return;

		run.forEach(Destroyable::destroy);
		run = null;
	}

	/**
	 * Returns the console for logging.
	 *
	 * @return The hardware console
	 */
	@Override
	public Console console() { return Hardware.console; }
}
