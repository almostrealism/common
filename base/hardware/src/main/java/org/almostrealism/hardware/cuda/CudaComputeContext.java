/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.cuda;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.ScopeEncoder;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.mem.KernelMemoryGuard;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link io.almostrealism.code.ComputeContext} that compiles scopes to CUDA kernels and runs
 * them on the stream of its own {@link CudaStreamRunner}.
 *
 * <p>Each scope is written as CUDA C++ with {@link CudaPrintWriter}, prefixed with
 * {@link #PREAMBLE}, compiled with NVRTC into a {@link CudaOperatorMap}, and cached by
 * signature (or by name, when instruction-set reuse is disabled), as the other GPU
 * backends do.</p>
 */
public class CudaComputeContext extends AbstractComputeContext<CudaDataContext> implements ConsoleFeatures {
	/**
	 * Definitions every generated program begins with: the constants the generated code
	 * refers to that CUDA's headers do not provide.
	 */
	public static final String PREAMBLE =
			"#define M_PI 3.14159265358979323846\n" +
			"#define M_PI_F 3.14159265358979323846f\n";

	/** Metadata for copies between CUDA allocations. */
	private static final OperationMetadata DEVICE_COPY =
			new OperationMetadata("cudaDeviceCopy", "CUDA device copy");

	/** The runner that owns this context's stream. */
	private final CudaStreamRunner runner;

	/** The maximum number of blocks in a grid, for the device. */
	private final int maxGridSize;

	/** Compiled instruction sets, by signature or name. */
	private Map<String, CudaOperatorMap> instructionSets;

	/**
	 * Creates a compute context with a new stream in the data context's CUDA context.
	 *
	 * @param dc the data context
	 */
	public CudaComputeContext(CudaDataContext dc) {
		super(dc);
		this.runner = new CudaStreamRunner(dc.getCudaContext().newStream());
		this.maxGridSize = dc.getDevice().getMaxGridDimX();
		this.instructionSets = new HashMap<>();
	}

	@Override
	public LanguageOperations getLanguage() {
		return new CudaLanguageOperations(getDataContext().getPrecision());
	}

	/** Returns the runner that owns this context's stream. */
	public CudaStreamRunner getStreamRunner() { return runner; }

	/** Returns the maximum number of blocks in a grid. */
	public int getMaxGridSize() { return maxGridSize; }

	@Override
	public synchronized InstructionSet deliver(Scope scope) {
		String key = instructionSetKey(scope.getName(), scope.signature());

		if (instructionSets.containsKey(key)) {
			if (ScopeSettings.enableInstructionSetReuse) {
				warn("Compiling instruction set " + scope.getName() + " with duplicate signature");
			} else {
				warn("Recompiling instruction set " + scope.getName());
			}

			instructionSets.get(key).destroy();
		}

		long start = System.nanoTime();
		StringBuilder buf = new StringBuilder();

		try {
			buf.append(PREAMBLE);
			buf.append("\n");

			ScopeEncoder enc = new ScopeEncoder(pw ->
					new CudaPrintWriter(pw, scope.getName(), getDataContext().getPrecision()),
					Accessibility.EXTERNAL);
			buf.append(enc.apply(scope));

			CudaOperatorMap instSet = new CudaOperatorMap(this, scope.getMetadata(), scope.getName(), buf.toString());
			instructionSets.put(key, instSet);
			return instSet;
		} finally {
			recordCompilation(scope, buf::toString, System.nanoTime() - start);
		}
	}

	@Override
	public boolean isCPU() { return false; }

	/**
	 * Copies one CUDA allocation into another on the device, ordered after {@code dependsOn},
	 * without waiting for the copy on the calling thread; falls back to the host-mediated copy of
	 * {@link AbstractComputeContext#copy} when either operand is not CUDA memory.
	 *
	 * <p>The device copy is enqueued on the stream and runs after this returns, exactly as a
	 * dispatched kernel does, so it holds a {@link KernelMemoryGuard} scheduling lease over both
	 * regions from the moment it is scheduled until its completion callback, as the fallback copy
	 * does. Without it a region released in the meantime could be freed while the queued copy is
	 * still using it. A lease (rather than a plain execution reservation) is used because a foreign
	 * dependency may hold the submission longer than the deferred-release backstop, which a lease is
	 * exempt from. The lease keeps the memory alive, but destroying either operand still clears its
	 * reference to it, so the copy resolves each region through the lease's
	 * {@link KernelMemoryGuard.Reservation#detachedReference detached reference} when it launches.</p>
	 *
	 * @param source      the memory region to copy from
	 * @param destination the memory region to copy into
	 * @param dependsOn   the completion this copy must be ordered after, or {@code null}
	 * @return the copy's completion
	 */
	@Override
	public Semaphore copy(MemoryData source, MemoryData destination, Semaphore dependsOn) {
		if (source.getMem() instanceof CudaMemory && destination.getMem() instanceof CudaMemory) {
			KernelMemoryGuard.Reservation lease =
					Hardware.getLocalHardware().getKernelMemoryGuard().acquireScheduled(source, destination);
			Supplier<MemoryData> from = lease.detachedReference(source);
			Supplier<MemoryData> to = lease.detachedReference(destination);

			return runner.submit(DEVICE_COPY, stream -> {
				MemoryData src = from.get();
				MemoryData dst = to.get();
				CudaMemory srcMem = (CudaMemory) src.getMem();
				CudaMemory dstMem = (CudaMemory) dst.getMem();
				long elementSize = srcMem.getProvider().getNumberSize();

				stream.copy(srcMem.getBuffer(), src.getOffset() * elementSize,
						dstMem.getBuffer(), dst.getOffset() * elementSize,
						src.getMemLength() * elementSize);
			}, dependsOn, lease::release);
		}

		return super.copy(source, destination, dependsOn);
	}

	/**
	 * Removes a destroyed instruction set from the cache.
	 *
	 * @throws IllegalArgumentException if no such instruction set is cached
	 */
	protected synchronized void destroyed(String name, String signature) {
		if (instructionSets != null) {
			String key = instructionSetKey(name, signature);

			if (instructionSets.remove(key) == null) {
				throw new IllegalArgumentException("No instruction set found for " + key);
			}
		}
	}

	/**
	 * Destroys the context. The stream runner is destroyed first, which waits for every kernel
	 * still on the stream and runs their completion callbacks, so no instruction set's module is
	 * unloaded while a kernel from it is still pending.
	 */
	@Override
	public synchronized void destroy() {
		super.destroy();
		runner.destroy();

		List<CudaOperatorMap> toDestroy = new ArrayList<>(instructionSets.values());
		toDestroy.forEach(CudaOperatorMap::destroy);
		instructionSets = null;
	}

	@Override
	public Console console() { return Hardware.console; }
}
