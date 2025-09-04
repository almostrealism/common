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

package org.almostrealism.hardware.mem;

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class AcceleratedProcessDetails implements ConsoleFeatures {
	private boolean enableAggregation = true;

	private Object[] originalArguments;
	private Object[] arguments;
	private int kernelSize;

	private MemoryReplacementManager replacementManager;

	private Executor executor;
	private DefaultLatchSemaphore semaphore;
	private List<Runnable> listeners;

	public AcceleratedProcessDetails(Object[] args, int kernelSize,
									 MemoryReplacementManager replacementManager,
									 Executor executor) {
		this.originalArguments = args;
		this.kernelSize = kernelSize;
		this.replacementManager = replacementManager;
		this.executor = executor;
		this.listeners = new ArrayList<>();
	}

	public OperationList getPrepare() { return replacementManager.getPrepare(); }
	public OperationList getPostprocess() { return replacementManager.getPostprocess(); }
	public boolean isEmpty() { return replacementManager.isEmpty(); }

	public Semaphore getSemaphore() { return semaphore; }
	public void setSemaphore(DefaultLatchSemaphore semaphore) { this.semaphore = semaphore; }

	public <A> A[] getArguments(IntFunction<A[]> generator) {
		return Stream.of(getArguments()).toArray(generator);
	}

	public Object[] getArguments() { return arguments; }
	public Object[] getOriginalArguments() { return originalArguments; }

	public int getKernelSize() { return kernelSize; }

	protected synchronized void checkReady() {
		if (!isReady()) return;

		if (arguments == null) {
			arguments = enableAggregation ? replacementManager.processArguments(originalArguments) : originalArguments;
		}

		executor.execute(() -> {
			listeners.forEach(r -> {
				r.run();

				if (semaphore != null) {
					semaphore.countDown();
				}
			});
		});
	}

	public boolean isReady() {
		return Stream.of(originalArguments).noneMatch(Objects::isNull);
	}

	public void result(int index, Object result) {
		originalArguments[index] = result;

		// TODO  This check should not block the
		// TODO  return of the results method
		checkReady();
	}

	public synchronized void whenReady(Runnable r) {
		this.listeners.add(r);
		checkReady();
	}

	@Override
	public Console console() { return Hardware.console; }
}
