/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.Semaphore;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class NativeExecution extends HardwareOperator {
	private static ExecutorService executor = Executors.newFixedThreadPool(20);

	private NativeInstructionSet inst;
	private int argCount;

	protected NativeExecution(NativeInstructionSet inst, int argCount) {
		this.inst = inst;
		this.argCount = argCount;
	}

	@Override
	protected String getHardwareName() { return "JNI"; }

	@Override
	public String getName() { return getClass().getSimpleName(); }

	@Override
	public OperationMetadata getMetadata() { return inst.getMetadata(); }

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

		int dim0[];
		MemoryData data[] = prepareArguments(argCount, args);
		int dimMasks[] = computeDimensionMasks(data);

		if (enableDimensionMasks) {
			dim0 = IntStream.range(0, getArgCount()).map(i -> data[i].getAtomicMemLength() * dimMasks[i]).toArray();
		} else {
			dim0 = IntStream.range(0, getArgCount()).map(i -> data[i].getAtomicMemLength()).toArray();
		}

		if (getGlobalWorkSize() > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}

		int size = (int) getGlobalWorkSize();

		CountDownLatch latch = new CountDownLatch(size);

		long start = System.currentTimeMillis();

		recordDuration(() -> {
					IntStream.range(0, size).parallel()
							.mapToObj(id ->
									executor.submit(() -> {
										try {
											inst.apply(getGlobalWorkOffset() + id, dim0, data);
										} finally {
											latch.countDown();
										}
									}))
							.collect(Collectors.toList());

					try {
						latch.await();
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				});

		// TODO  The latch should be turned into a Semaphore and returned
		return null;
	}
}
