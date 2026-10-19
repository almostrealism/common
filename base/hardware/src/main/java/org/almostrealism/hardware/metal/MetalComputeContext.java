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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.lang.ScopeEncoder;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link io.almostrealism.code.ComputeContext} for Apple Metal shader compilation and execution.
 *
 * <p>Compiles {@link Scope} to Metal Shading Language (MSL), creates {@link MTLComputePipelineState},
 * and executes kernels on Metal GPU.</p>
 *
 * <h2>Metal Shading Language Generation</h2>
 *
 * <pre>{@code
 * MetalComputeContext context = ...;
 * Scope<Matrix> scope = operation.getScope(context);
 *
 * // Generates MSL code:
 * // #include <metal_stdlib>
 * // kernel void matmul(...) { ... }
 *
 * MetalOperatorMap instructions = (MetalOperatorMap) context.deliver(scope);
 * }</pre>
 *
 * <h2>Command Queue Management</h2>
 *
 * <pre>{@code
 * MTLCommandQueue queue = context.getQueue();         // Main queue
 * MTLCommandQueue fast = context.getFastQueue();      // Fast queue (if enabled)
 * }</pre>
 *
 * @see MetalDataContext
 * @see MetalOperator
 * @see MTLDevice
 */
public class MetalComputeContext extends AbstractComputeContext implements ConsoleFeatures {
	/**
	 * Enables creation of a second "fast" command queue for parallel execution.
	 */
	public static boolean enableFastQueue = false;

	/** Requester metadata identifying copies queued by {@link #copy(MemoryData, MemoryData, Semaphore)}. */
	private static final OperationMetadata COPY =
			new OperationMetadata("mtlCopy", "Metal buffer copy");

	/**
	 * Names of the copy kernels {@link #copy(MemoryData, MemoryData, Semaphore)} dispatches, indexed
	 * by the base-2 logarithm of the number of bytes each thread copies.
	 */
	private static final String[] COPY_KERNEL_NAMES = {"copy8", "copy16", "copy32"};

	/** Metal types of the units each copy kernel copies, indexed as {@link #COPY_KERNEL_NAMES}. */
	private static final String[] COPY_KERNEL_TYPES = {"uchar", "ushort", "uint"};

	/**
	 * Standard Metal Shading Language includes prepended to every compiled kernel.
	 *
	 * <p>Kernels are compiled with fast math (see {@code MTL.cpp}), under which the plain
	 * {@code metal::tanh} evaluates through an exponential that overflows for arguments beyond
	 * roughly 44 in magnitude, returning NaN where the true value is &plusmn;1. The precise variant
	 * is selected for {@code tanh} so that saturating activations stay finite for every input,
	 * matching the native backends.</p>
	 *
	 * <p>Only {@code tanh} needs this treatment. The other fast-math functions above overflow to
	 * &plusmn;infinity or NaN only where the true function itself diverges &mdash; {@code exp} of a
	 * large argument, {@code log} of zero, {@code pow}/{@code tan} at their singularities &mdash; so
	 * their fast-math edge results already agree with the mathematical limit and never turn a
	 * finite true value into NaN. {@code tanh} is the sole function here whose true value is bounded
	 * everywhere yet whose fast-math implementation overflows, which is why it alone is made
	 * precise.</p>
	 */
	private static String includes = "#include <metal_stdlib>\n" +
									"using metal::min;\n" +
									"using metal::max;\n" +
									"using metal::fmod;\n" +
									"using metal::floor;\n" +
									"using metal::ceil;\n" +
									"using metal::abs;\n" +
									"using metal::pow;\n" +
									"using metal::exp;\n" +
									"using metal::log;\n" +
									"using metal::sin;\n" +
									"using metal::cos;\n" +
									"using metal::acos;\n" +
									"using metal::tan;\n" +
									// Only tanh; see the field javadoc for why the others stay fast-math.
									"using metal::precise::tanh;\n";

	/** The primary Metal device used for kernel compilation and execution. */
	private MTLDevice mainDevice;
	/** The main command queue for serialized GPU command submission. */
	private MTLCommandQueue queue;
	/** An optional second command queue for parallel ("fast") execution when {@link #enableFastQueue} is true. */
	private MTLCommandQueue fastQueue;

	/** Runner that serializes Metal command submission and manages pre-allocated argument buffers. */
	private MetalCommandRunner runner;

	/** Cache of compiled instruction sets, keyed by name and signature, to avoid redundant compilation. */
	private Map<String, MetalOperatorMap> instructionSets;

	/** Guards compilation of the copy kernels, separately from {@link #deliver(Scope)}. */
	private final Object copyKernelLock = new Object();

	/** Compiled programs of the copy kernels, indexed as {@link #COPY_KERNEL_NAMES}; null until first use. */
	private final MetalProgram[] copyPrograms = new MetalProgram[COPY_KERNEL_NAMES.length];

	/** Pipeline states of the copy kernels, indexed as {@link #COPY_KERNEL_NAMES}; null until first use. */
	private final MTLComputePipelineState[] copyKernels = new MTLComputePipelineState[COPY_KERNEL_NAMES.length];

	/**
	 * Creates a Metal compute context for the specified data context.
	 *
	 * @param dc The {@link MetalDataContext} providing device and memory access
	 */
	public MetalComputeContext(MetalDataContext dc) {
		super(dc);
		this.instructionSets = new HashMap<>();
	}

	/**
	 * Initializes the compute context with the Metal device and command queues.
	 *
	 * <p>Creates the main command queue and optionally a fast queue if
	 * {@link #enableFastQueue} is enabled. Initializes the {@link MetalCommandRunner}.</p>
	 *
	 * @param mainDevice The {@link MTLDevice} to use for computation
	 */
	protected void init(MTLDevice mainDevice) {
		if (queue != null) return;

		this.mainDevice = mainDevice;

		if (Hardware.enableVerbose) {
			log("Hardware[" + getDataContext().getName() + "]: Max Threadgroup Size (" +
					mainDevice.maxThreadgroupWidth() + ", " +
					mainDevice.maxThreadgroupHeight() + ", " +
					mainDevice.maxThreadgroupDepth() + ")");
		}

		queue = mainDevice.newCommandQueue();
		if (Hardware.enableVerbose) log("Hardware[" + getDataContext().getName() + "]: Metal command queue initialized");

		if (enableFastQueue) {
			fastQueue = mainDevice.newCommandQueue();
			if (Hardware.enableVerbose)
				log("Hardware[" + getDataContext().getName() + "]: Metal fast command queue initialized");
		}

		this.runner = new MetalCommandRunner(queue);
	}

	/**
	 * Returns the language operations for generating Metal Shading Language code.
	 *
	 * @return {@link MetalLanguageOperations} configured for the context's precision
	 */
	@Override
	public LanguageOperations getLanguage() {
		return new MetalLanguageOperations(getDataContext().getPrecision());
	}

	/**
	 * Compiles a {@link Scope} into Metal Shading Language and creates executable instruction set.
	 *
	 * <p>Generates MSL code with standard Metal includes, encodes the scope using
	 * {@link MetalPrintWriter}, and creates a {@link MetalOperatorMap} for execution.
	 * Caches compiled instruction sets by name or signature.</p>
	 *
	 * @param scope The computation scope to compile
	 * @return {@link MetalOperatorMap} containing compiled Metal operators
	 */
	@Override
	public synchronized InstructionSet deliver(Scope scope) {
		if (instructionSets.containsKey(instructionSetKey(scope.getName(), scope.signature()))) {
			if (ScopeSettings.enableInstructionSetReuse) {
				warn("Compiling instruction set " + scope.getName() +
						" with duplicate signature");
			} else {
				warn("Recompiling instruction set " + scope.getName());
			}

			instructionSets.get(instructionSetKey(scope.getName(), scope.signature())).destroy();
		}

		long start = System.nanoTime();
		StringBuilder buf = new StringBuilder();

		try {
			buf.append(includes);
			buf.append("\n");

			ScopeEncoder enc = new ScopeEncoder(pw -> new MetalPrintWriter(pw, scope.getName(), getLanguage().getPrecision()), Accessibility.EXTERNAL);
			buf.append(enc.apply(scope));

			MetalOperatorMap instSet = new MetalOperatorMap(this, scope.getMetadata(), scope.getName(), buf.toString());
			instructionSets.put(instructionSetKey(scope.getName(), scope.signature()), instSet);
			return instSet;
		} finally {
			recordCompilation(scope, buf::toString, System.nanoTime() - start);
		}
	}

	/**
	 * Checks if this context runs on CPU.
	 *
	 * @return Always false (Metal is GPU-only)
	 */
	@Override
	public boolean isCPU() { return false; }

	/**
	 * Copies all of {@code source} into {@code destination} by queuing a copy onto the command buffer
	 * (via the {@link MetalCommandRunner}), so the copy batches with the surrounding kernel dispatches,
	 * and returns its completion semaphore.
	 *
	 * <p>The copy is encoded as a compute dispatch of a copy kernel (see {@link #copyCommand}), not as
	 * a blit. Copies are queued between kernel dispatches — argument preparation copies in before a
	 * kernel and copies out after it — and every switch between a compute and a blit encoder within a
	 * command buffer costs the GPU tens of microseconds, several times what a small kernel itself
	 * costs. A copy kernel keeps the whole command buffer on compute encoders.</p>
	 *
	 * <p>{@code dependsOn} is honored by the {@link MetalCommandRunner}: a dependency produced by
	 * this context's runner orders the copy after it on the GPU (Metal's in-buffer hazard tracking
	 * covers a dependency still in the open buffer), and a foreign dependency is bridged through a
	 * host-signaled event without blocking (see {@link MetalCommandRunner#submit}). When either
	 * side is not Metal memory there is nothing to queue, so the
	 * {@link AbstractComputeContext#copy(MemoryData, MemoryData, Semaphore) inherited} direct
	 * {@code setMem} copy is used.</p>
	 *
	 * @param source      the memory region to copy from
	 * @param destination the memory region to copy into, which must not overlap {@code source}
	 * @param dependsOn   the completion this copy must be ordered after, or {@code null}
	 * @return the copy's completion semaphore, or {@code null} for the direct-copy fallback
	 */
	@Override
	public Semaphore copy(MemoryData source, MemoryData destination, Semaphore dependsOn) {
		if (source.getMem() instanceof MetalMemory && destination.getMem() instanceof MetalMemory) {
			MetalMemory src = (MetalMemory) source.getMem();
			MetalMemory dst = (MetalMemory) destination.getMem();
			long elementSize = src.getProvider().getNumberSize();
			long sourceOffset = source.getOffset() * elementSize;
			long destinationOffset = destination.getOffset() * elementSize;
			long size = source.getMemLength() * elementSize;

			return runner.submit(COPY,
					copyCommand(src.getMem(), sourceOffset, dst.getMem(), destinationOffset, size),
					dependsOn, null);
		}

		return super.copy(source, destination, dependsOn);
	}

	/**
	 * Returns a command that copies {@code size} bytes from {@code source} to {@code destination}
	 * with a copy kernel, each thread copying the widest unit (four, two or one bytes) that both
	 * offsets and the size are multiples of. The regions must not overlap.
	 *
	 * <p>The kernel addresses units with 32-bit indices, so a copy reaching beyond what they can
	 * address is encoded as a blit instead. A copy of nothing encodes nothing.</p>
	 *
	 * @param source            the buffer to copy from
	 * @param sourceOffset      byte offset within {@code source}
	 * @param destination       the buffer to copy into
	 * @param destinationOffset byte offset within {@code destination}
	 * @param size              number of bytes to copy
	 * @return the command encoding the copy, to be submitted to this context's
	 *         {@link #getCommandRunner() command runner}
	 */
	public MetalCommand copyCommand(MTLBuffer source, long sourceOffset,
									MTLBuffer destination, long destinationOffset, long size) {
		int unitShift = Math.min(COPY_KERNEL_NAMES.length - 1,
				Long.numberOfTrailingZeros(sourceOffset | destinationOffset | size));
		long units = size >> unitShift;
		long sourceUnit = sourceOffset >> unitShift;
		long destinationUnit = destinationOffset >> unitShift;

		if (Math.max(sourceUnit, destinationUnit) + units > Integer.MAX_VALUE) {
			return cmdBuf -> cmdBuf.blitCopy(source, sourceOffset, destination, destinationOffset, size);
		}

		if (units == 0) {
			return cmdBuf -> { };
		}

		MTLComputePipelineState kernel = copyKernel(unitShift);
		int[] offsets = {(int) destinationUnit, (int) sourceUnit};
		int groupWidth = (int) Math.min(units, kernel.maxTotalThreadsPerThreadgroup());

		return cmdBuf -> {
			MTLComputeCommandEncoder encoder = cmdBuf.encoder();
			encoder.setComputePipelineState(kernel);
			encoder.setBuffer(0, destination);
			encoder.setBuffer(1, source);
			encoder.setBytes(2, offsets);
			encoder.dispatchThreads(groupWidth, 1, 1, (int) units, 1, 1);
			encoder.endEncoding();
		};
	}

	/**
	 * Returns the pipeline state of the copy kernel for the given unit width, compiling it on
	 * first use.
	 *
	 * @param unitShift base-2 logarithm of the number of bytes each thread copies
	 * @return the copy kernel's pipeline state
	 */
	private MTLComputePipelineState copyKernel(int unitShift) {
		synchronized (copyKernelLock) {
			if (copyKernels[unitShift] == null) {
				MetalProgram program = new MetalProgram(this, COPY,
						COPY_KERNEL_NAMES[unitShift], copyKernelSource(unitShift));
				program.compile();
				copyPrograms[unitShift] = program;
				copyKernels[unitShift] = program.newComputePipelineState();
			}

			return copyKernels[unitShift];
		}
	}

	/**
	 * Returns the Metal source of the copy kernel for the given unit width, containing only that
	 * kernel so each width compiles nothing else. Each thread copies one unit from the source buffer
	 * (index 1) to the destination buffer (index 0), at the destination and source unit offsets
	 * bound at index 2.
	 *
	 * @param unitShift base-2 logarithm of the number of bytes each thread copies
	 * @return the kernel source, whose entry point is {@code COPY_KERNEL_NAMES[unitShift]}
	 */
	private static String copyKernelSource(int unitShift) {
		String type = COPY_KERNEL_TYPES[unitShift];
		return "#include <metal_stdlib>\n" +
				"[[kernel]] void " + COPY_KERNEL_NAMES[unitShift] +
				"(device " + type + " *destination [[buffer(0)]], " +
				"device const " + type + " *source [[buffer(1)]], constant uint *offsets [[buffer(2)]], " +
				"uint index [[thread_position_in_grid]]) { " +
				"destination[offsets[0] + index] = source[offsets[1] + index]; }\n";
	}

	/**
	 * Returns the Metal device for this context.
	 *
	 * @return The {@link MTLDevice} instance
	 */
	public MTLDevice getMtlDevice() { return mainDevice; }

	/**
	 * Returns the main Metal command queue.
	 *
	 * @return The primary {@link MTLCommandQueue}
	 */
	public MTLCommandQueue getMtlQueue() { return queue; }

	/**
	 * Returns the command runner for submitting Metal operations.
	 *
	 * @return The {@link MetalCommandRunner} instance
	 */
	public MetalCommandRunner getCommandRunner() { return runner; }

	/**
	 * Removes a destroyed instruction set from the cache.
	 *
	 * @param name Scope name
	 * @param signature Scope signature
	 * @throws IllegalArgumentException if no instruction set found with that key
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
	 * Destroys this compute context and releases all Metal resources.
	 *
	 * <p>Destroys all cached instruction sets, releases command queues, and
	 * clears internal state. After calling destroy, this context cannot be used.</p>
	 */
	@Override
	public synchronized void destroy() {
		super.destroy();

		List<MetalOperatorMap> toDestroy = new ArrayList<>(instructionSets.values());
		toDestroy.forEach(MetalOperatorMap::destroy);

		this.instructionSets = null;

		if (queue != null) queue.release();
		if (fastQueue != null) fastQueue.release();

		queue = null;
		fastQueue = null;

		synchronized (copyKernelLock) {
			for (int i = 0; i < copyKernels.length; i++) {
				if (copyKernels[i] != null) copyKernels[i].release();
				if (copyPrograms[i] != null) copyPrograms[i].destroy();
				copyKernels[i] = null;
				copyPrograms[i] = null;
			}
		}
	}

	/**
	 * Returns the console for logging output.
	 *
	 * @return The {@link Console} instance for hardware logging
	 */
	@Override
	public Console console() { return Hardware.console; }
}
