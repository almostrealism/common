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
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.io.TimingMetric;

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
	public static boolean enableExecutor = true;

	public static TimingMetric dimMaskMetric = Hardware.console.timing("dimMask");

	private static ExecutorService executor = Executors.newFixedThreadPool(KernelPreferences.getCpuParallelism());

	private NativeInstructionSet inst;
	private int argCount;

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

	@Override
	public Semaphore accept(Object[] args, Semaphore dependsOn) {
		if (dependsOn != null) dependsOn.waitFor(); // TODO  We can do better than forcing this method to block

		MemoryData data[] = prepareArguments(argCount, args);

		if (getGlobalWorkSize() > Integer.MAX_VALUE ||
				inst.getParallelism() != 1 && getGlobalWorkOffset() != 0) {
			throw new UnsupportedOperationException();
		}

		int p = getGlobalWorkSize() < inst.getParallelism() ? (int) getGlobalWorkSize() : inst.getParallelism();

		DefaultLatchSemaphore latch = new DefaultLatchSemaphore(dependsOn, p);

		if (enableExecutor) {
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

				// TODO  The user of the semaphore should decide when to wait
				// TODO  rather than it happening proactively here
				latch.waitFor();
			});
		} else {
			recordDuration(latch, () -> {
				for (int i = 0; i < inst.getParallelism(); i++) {
					inst.apply(getGlobalWorkOffset() + i, getGlobalWorkSize(), data);
					latch.countDown();
				}
			});
		}

		return latch;
	}
}
