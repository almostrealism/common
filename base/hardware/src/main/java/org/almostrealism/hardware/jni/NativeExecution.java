/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.jni;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.KernelMemoryGuard;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.io.TimingMetric;

import java.lang.ref.Reference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Execution wrapper for {@link NativeInstructionSet} that manages parallel dispatch and synchronization.
 *
 * <p>{@link NativeExecution} extends {@link HardwareOperator} to provide execution orchestration for
 * compiled native code. It handles:</p>
 * <ul>
 *   <li><strong>Parallel dispatch:</strong> Splits work across multiple CPU threads</li>
 *   <li><strong>Argument preparation:</strong> Marshals arguments via {@link #prepareArguments}</li>
 *   <li><strong>Semaphore coordination:</strong> Manages dependencies and completion signaling</li>
 *   <li><strong>Performance tracking:</strong> Records execution duration</li>
 * </ul>
 *
 * <h2>Execution Flow</h2>
 *
 * <p>When {@link #accept(Object[], Semaphore)} is called:</p>
 * <pre>
 * 1. Wait for dependencies   dependsOn.waitFor() if present
 * 2. Prepare arguments       MemoryData[] from Object[]
 * 3. Calculate parallelism   min(workSize, configured parallelism)
 * 4. Submit tasks           p tasks to executor, each runs inst.apply()
 * 5. Wait for completion    latch.waitFor()
 * 6. Return semaphore       For downstream dependencies
 * </pre>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * // Created internally by NativeInstructionSet.get()
 * Execution execution = instructionSet.get("function", 2);
 *
 * // Configure work size
 * execution.setGlobalWorkSize(1000);
 * execution.setGlobalWorkOffset(0);
 *
 * // Execute with arguments
 * PackedCollection input = ...;
 * PackedCollection output = ...;
 * Semaphore result = execution.accept(new Object[] { input, output }, null);
 *
 * // Wait for completion (already done, but can be passed to next operation)
 * result.waitFor();
 * }</pre>
 *
 * <h2>Parallel Dispatch</h2>
 *
 * <p>Work is split across multiple threads based on {@link NativeInstructionSet#setParallelism(int)}:</p>
 * <pre>{@code
 * // Parallelism = 8, work size = 1000
 * // -> Submits 8 tasks, each processes portion of 1000 items
 *
 * // Task 0: globalId = 0 + 0, kernelSize = 1000
 * // Task 1: globalId = 0 + 1, kernelSize = 1000
 * // ...
 * // Task 7: globalId = 0 + 7, kernelSize = 1000
 *
 * // Native code uses globalId to determine which subset to process
 * }</pre>
 *
 * <h2>Executor Configuration</h2>
 *
 * <p>Uses a shared thread pool sized to CPU parallelism:</p>
 * <pre>{@code
 * // Thread pool size = KernelPreferences.getCpuParallelism()
 * ExecutorService executor = Executors.newFixedThreadPool(parallelism);
 *
 * // Disable executor (tasks run sequentially)
 * NativeExecution.enableExecutor = false;
 * }</pre>
 *
 * <h2>Semaphore Coordination</h2>
 *
 * <p>{@link DefaultLatchSemaphore} manages task completion:</p>
 * <pre>{@code
 * // Create latch with count = parallelism
 * DefaultLatchSemaphore latch = new DefaultLatchSemaphore(dependsOn, 8);
 *
 * // Each task calls latch.countDown() when done
 * // After 8 countdowns, latch.waitFor() returns
 * }</pre>
 *
 * <h2>Argument Preparation</h2>
 *
 * <p>{@link #prepareArguments(int, Object[])} marshals Java objects to {@link MemoryData}:</p>
 * <pre>{@code
 * Object[] args = { packedCollection, vector, scalar };
 * MemoryData[] prepared = prepareArguments(3, args);
 *
 * // prepared[0] = PackedCollection (MemoryData)
 * // prepared[1] = Vector (MemoryData)
 * // prepared[2] = PackedCollection wrapped as MemoryData
 * }</pre>
 *
 * <h2>Supported Memory</h2>
 *
 * <p>Reports memory providers from the compute context, excluding {@link JVMMemoryProvider}:</p>
 * <pre>{@code
 * List<MemoryProvider> supported = execution.getSupportedMemory();
 * // Returns: [NativeMemoryProvider, MetalMemoryProvider, ...]
 * // Excludes: JVMMemoryProvider (not hardware-accelerated)
 * }</pre>
 *
 * <h2>Error Handling</h2>
 *
 * <p>Exceptions during native execution are caught and logged:</p>
 * <pre>{@code
 * try {
 *     inst.apply(globalId, kernelSize, data);
 * } catch (Exception e) {
 *     warn("Operation " + id + " of " + workSize + " failed", e);
 * } finally {
 *     latch.countDown();  // Ensure latch progresses
 * }
 * }</pre>
 *
 * <h2>Performance Tracking</h2>
 *
 * <p>Execution duration is recorded via {@link #recordDuration(Semaphore, Runnable)}:</p>
 * <pre>{@code
 * recordDuration(latch, () -> {
 *     // Submit tasks...
 *     latch.waitFor();
 * });
 * // Duration includes submission + execution + wait
 * }</pre>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 *   <li><strong>Work size:</strong> Must be &lt;= Integer.MAX_VALUE</li>
 *   <li><strong>Work offset:</strong> Must be 0 if parallelism > 1</li>
 *   <li><strong>Blocking:</strong> Currently blocks waiting for completion (TODO: make async)</li>
 * </ul>
 *
 * @see HardwareOperator
 * @see NativeInstructionSet
 * @see DefaultLatchSemaphore
 */
public class NativeExecution extends HardwareOperator {
	/** If true, kernel execution is dispatched to the thread pool; if false, it runs synchronously. */
	public static boolean enableExecutor = true;

	/** Metric tracking dimension mask computation time for JNI kernel invocations. */
	public static TimingMetric dimMaskMetric = Hardware.console.timing("dimMask");

	/** Shared thread pool sized to the available CPU parallelism for concurrent kernel execution. */
	private static ExecutorService executor = Executors.newFixedThreadPool(KernelPreferences.getCpuParallelism());

	/**
	 * Executor for dispatch coordination: each {@link #accept(Object[], Semaphore)} runs its
	 * dependency wait, worker submission, and completion bookkeeping on one of these threads so
	 * the submitting thread never blocks. Kept separate from the fixed-size {@link #executor}
	 * worker pool because coordinators block on their latch &mdash; running them on the worker
	 * pool could occupy every thread with waiting coordinators and starve the workers they wait
	 * for.
	 */
	private static ExecutorService dispatchExecutor = Executors.newCachedThreadPool();

	/** The native instruction set providing access to the compiled JNI function. */
	private NativeInstructionSet inst;
	/** Number of {@link io.almostrealism.code.MemoryData} arguments expected by the native function. */
	private int argCount;

	/**
	 * Creates a native execution operator backed by a compiled JNI instruction set.
	 *
	 * @param inst The {@link NativeInstructionSet} providing the compiled native function
	 * @param argCount Number of memory arguments expected by the native function
	 */
	protected NativeExecution(NativeInstructionSet inst, int argCount) {
		this.inst = inst;
		this.argCount = argCount;
	}

	@Override
	protected String getHardwareName() { return "JNI"; }

	@Override
	public String getName() { return inst.getFunctionName() + "(execution " + getId() + ")"; }

	@Override
	public OperationMetadata getMetadata() { return inst.getMetadata(); }

	@Override
	public boolean isGPU() {
		return !inst.getComputeContext().isCPU();
	}

	@Override
	protected int getArgCount() { return argCount; }

	@Override
	public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
		return inst.getComputeContext().getDataContext().getMemoryProviders()
				.stream().filter(Predicate.not(JVMMemoryProvider.class::isInstance))
				.collect(Collectors.toList());
	}

	/**
	 * Dispatches the native kernel and returns its completion {@link Semaphore} without
	 * blocking the submitting thread.
	 *
	 * <p>When {@link #enableExecutor} is true, a coordinator task (on the
	 * {@link #dispatchExecutor}) waits for {@code dependsOn}, submits the parallel worker
	 * tasks, and releases the dispatch's memory guard once every worker has finished; the
	 * returned latch fires at that point, so a caller (or a dependent operation receiving it
	 * as {@code dependsOn}) waits on genuine completion. When {@link #enableExecutor} is
	 * false, execution is fully synchronous and the returned latch has already fired.</p>
	 *
	 * @param args      the kernel arguments
	 * @param dependsOn the completion this dispatch must be ordered after, or {@code null}
	 * @return the dispatch's completion semaphore
	 */
	@Override
	public Semaphore accept(Object[] args, Semaphore dependsOn) {
		MemoryData data[] = prepareArguments(argCount, args);

		if (enableVerboseLog) {
			StringBuilder desc = new StringBuilder();
			for (MemoryData d : data) {
				if (desc.length() > 0) desc.append(",");
				desc.append(d.getMem().getClass().getSimpleName())
						.append("@").append(System.identityHashCode(d.getMem()))
						.append("+").append(d.getOffset())
						.append("x").append(d.getMemLength())
						.append("=").append(d.toDouble(0));
			}

			log(getName() + " workSize=" + getGlobalWorkSize() +
					" dependsOn=" + (dependsOn == null ? "none" : dependsOn.getClass().getSimpleName()) +
					" args=" + desc);
		}

		if (getGlobalWorkSize() > Integer.MAX_VALUE ||
				inst.getParallelism() != 1 && getGlobalWorkOffset() != 0) {
			throw new UnsupportedOperationException();
		}

		int p = getGlobalWorkSize() < inst.getParallelism() ? (int) getGlobalWorkSize() : inst.getParallelism();

		KernelMemoryGuard guard = KernelMemoryGuard.acquireFor(data);

		DefaultLatchSemaphore latch = new DefaultLatchSemaphore(dependsOn, p);

		if (enableExecutor) {
			dispatchExecutor.submit(() -> coordinate(data, args, dependsOn, guard, latch, p));
		} else {
			try {
				if (dependsOn != null) dependsOn.waitFor();

				recordDuration(latch, () -> {
					for (int i = 0; i < inst.getParallelism(); i++) {
						inst.apply(getGlobalWorkOffset() + i, getGlobalWorkSize(), data);
						latch.countDown();
					}
				});
			} finally {
				KernelMemoryGuard.releaseFor(guard, data);
			}

			Reference.reachabilityFence(data);
			Reference.reachabilityFence(args);
		}

		return latch;
	}

	/**
	 * Coordinates one dispatch on a {@link #dispatchExecutor} thread: waits for the dispatch's
	 * dependency, submits the parallel worker tasks, waits for them all, and releases the
	 * memory guard. The latch always reaches zero &mdash; workers count down in their own
	 * finally blocks, and any workers that could not be submitted are counted down here &mdash;
	 * so no waiter can hang on a failed dispatch.
	 *
	 * @param data      the resolved kernel arguments
	 * @param args      the original arguments (retained so backing memory stays reachable)
	 * @param dependsOn the completion this dispatch is ordered after, or {@code null}
	 * @param guard     the memory guard held for the duration of the dispatch
	 * @param latch     the dispatch's completion latch
	 * @param p         the number of parallel worker tasks
	 */
	private void coordinate(MemoryData[] data, Object[] args, Semaphore dependsOn,
							KernelMemoryGuard guard, DefaultLatchSemaphore latch, int p) {
		int submitted = 0;

		try {
			if (dependsOn != null) dependsOn.waitFor();

			recordDuration(latch, () -> {
				for (int i = 0; i < p; i++) {
					int id = i;

					executor.submit(() -> {
						try {
							inst.apply(getGlobalWorkOffset() + id, getGlobalWorkSize(), data);
						} catch (Exception e) {
							warn("Operation " + id + " of " +
									getGlobalWorkSize() + " failed", e);
						} finally {
							latch.countDown();
						}
					});
				}

				latch.waitFor();
			});

			submitted = p;

			if (enableVerboseLog) {
				log(getName() + " result0=" + data[0].toDouble(0));
			}
		} catch (Exception e) {
			warn(getName() + " dispatch failed", e);
		} finally {
			for (int i = submitted; i < p; i++) {
				latch.countDown();
			}

			KernelMemoryGuard.releaseFor(guard, data);
		}

		Reference.reachabilityFence(data);
		Reference.reachabilityFence(args);
	}
}
