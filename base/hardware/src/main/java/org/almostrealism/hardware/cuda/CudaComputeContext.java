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
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	@Override
	public Semaphore copy(MemoryData source, MemoryData destination, Semaphore dependsOn) {
		if (source.getMem() instanceof CudaMemory && destination.getMem() instanceof CudaMemory) {
			CudaMemory src = (CudaMemory) source.getMem();
			CudaMemory dst = (CudaMemory) destination.getMem();
			long elementSize = src.getProvider().getNumberSize();
			long sourceOffset = source.getOffset() * elementSize;
			long destinationOffset = destination.getOffset() * elementSize;
			long size = source.getMemLength() * elementSize;

			return runner.submit(DEVICE_COPY,
					stream -> stream.copy(src.getBuffer(), sourceOffset, dst.getBuffer(), destinationOffset, size),
					dependsOn, null);
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

	@Override
	public synchronized void destroy() {
		super.destroy();

		List<CudaOperatorMap> toDestroy = new ArrayList<>(instructionSets.values());
		toDestroy.forEach(CudaOperatorMap::destroy);
		instructionSets = null;

		runner.destroy();
	}

	@Override
	public Console console() { return Hardware.console; }
}
