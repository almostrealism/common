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
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
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
