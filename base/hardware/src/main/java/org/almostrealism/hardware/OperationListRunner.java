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
 */
public class OperationListRunner implements Runnable, Destroyable, OperationInfo, ConsoleFeatures {
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
	 * Executes all operations in sequence with compute requirements and timing.
	 *
	 * <p>{@link Submittable} members are issued without a host wait, each chaining on the
	 * previous member's completion {@link Semaphore}, so ordering holds even when
	 * consecutive members execute on different compute contexts (a Metal member followed
	 * by a native member, for example, where issue order alone guarantees nothing). A
	 * provider that already orders same-context work treats a same-batch dependency as
	 * free &mdash; Metal's in-buffer hazard tracking covers a dependency still in the open
	 * command buffer without a commit or an event wait &mdash; so chaining does not defeat
	 * batching, and a foreign dependency is honored by the receiving provider. A single
	 * wait covers the whole group, taken at the end of the list or before a
	 * non-submittable member (which requires everything ahead of it to be complete).</p>
	 *
	 * <p>The compute requirements pushed here are thread-local; each member's dispatch
	 * captures them on this thread and re-establishes them on whatever thread performs
	 * the asynchronous work (see {@code AcceleratedOperation.apply}).</p>
	 */
	@Override
	public void run() {
		try {
			if (requirements != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(requirements);
			}

			Semaphore pending = null;

			for (int i = 0; i < run.size(); i++) {
				Runnable r = run.get(i);

				if (r instanceof Submittable) {
					// Chain on the prior member; see the method javadoc
					Submittable s = (Submittable) r;

					if (timingListener == null) {
						pending = s.submit(pending);
					} else {
						if (enableRunLogging) log("Running " + OperationInfo.display(r));
						Semaphore[] completion = { pending };
						timingListener.recordDuration(getMetadata(),
								() -> completion[0] = s.submit(completion[0]));
						pending = completion[0];
					}
				} else {
					// Non-submittable members require everything ahead of them to be complete
					if (pending != null) {
						pending.waitFor();
						pending = null;
					}

					if (timingListener == null) {
						r.run();
					} else {
						if (enableRunLogging) log("Running " + OperationInfo.display(r));
						timingListener.recordDuration(getMetadata(), r);
					}
				}
			}

			if (pending != null) {
				pending.waitFor();
			}
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
