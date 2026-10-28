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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeExecution;
import org.almostrealism.hardware.jni.NativeInstructionSet;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link NativeInstructionSet} whose kernel is a Java stand-in that counts its invocations and,
 * when configured to, throws instead of running. The {@link NativeExecution} it produces is the
 * real one, so a test drives the real dispatch coordination, latch and memory guard of the
 * native backend without compiling a kernel.
 */
class ScriptedInstructionSet implements NativeInstructionSet {
	/** Number of times the kernel has been invoked. */
	private final AtomicInteger invocations = new AtomicInteger();
	/** The failure every invocation throws, or {@code null} to complete normally. */
	private final RuntimeException failure;

	/** The compute context whose memory providers the execution accepts. */
	private ComputeContext<MemoryData> context = Hardware.getLocalHardware().getComputeContext();
	/** Metadata identifying the kernel. */
	private OperationMetadata metadata = new OperationMetadata("scripted", "Scripted native kernel");
	/** Number of workers a dispatch is split across. */
	private int parallelism;

	/**
	 * Creates an instruction set.
	 *
	 * @param parallelism the number of workers a dispatch is split across
	 * @param failure     the failure every invocation throws, or {@code null} to complete normally
	 */
	ScriptedInstructionSet(int parallelism, RuntimeException failure) {
		this.parallelism = parallelism;
		this.failure = failure;
	}

	/**
	 * Returns an execution of this kernel over one argument, with one unit of work per worker.
	 *
	 * @return the native execution
	 */
	NativeExecution execution() {
		NativeExecution execution = (NativeExecution) get("scripted", 1);
		execution.setGlobalWorkSize(parallelism);
		return execution;
	}

	/**
	 * Returns how many times the kernel has been invoked.
	 *
	 * @return the invocation count
	 */
	int getInvocations() { return invocations.get(); }

	@Override
	public void apply(long idx, long kernelSize, MemoryData... args) {
		invocations.incrementAndGet();
		if (failure != null) throw failure;
	}

	@Override
	public void apply(long commandQueue, long[] arg, int[] offset, int[] size, int count,
					  int globalId, long kernelSize) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ComputeContext<MemoryData> getComputeContext() { return context; }

	@Override
	public void setComputeContext(ComputeContext<MemoryData> context) { this.context = context; }

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public void setMetadata(OperationMetadata metadata) { this.metadata = metadata; }

	@Override
	public int getParallelism() { return parallelism; }

	@Override
	public void setParallelism(int parallelism) { this.parallelism = parallelism; }
}
