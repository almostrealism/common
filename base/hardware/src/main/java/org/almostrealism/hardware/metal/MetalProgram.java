/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.metal;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

/**
 * Compiled Metal Shading Language (MSL) program containing a {@link MTLFunction}.
 *
 * <p>Wraps Metal function compilation from source code and creates {@link MTLComputePipelineState}
 * instances for kernel execution.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * MetalProgram prog = MetalProgram.create(context, metadata, "myKernel", mslSource);
 * prog.compile();  // Compiles MSL source into MTLFunction
 *
 * // Create pipeline state for execution
 * MTLComputePipelineState pipeline = prog.newComputePipelineState();
 * }</pre>
 *
 * <h2>Lanes per Work Item</h2>
 *
 * <p>A program records the lanes its kernel assigns to each work item (see
 * {@link io.almostrealism.scope.Scope#getLaneCount()}), the number its source was rendered for:
 * one for an ordinary kernel, or the SIMD group width for a kernel that opens by deriving its
 * work item and lane from the position of its thread. A {@link MetalOperator} dispatches the
 * program with that many threads for every work item, and a pipeline whose SIMD groups differ
 * from the lanes is refused (see {@link #newComputePipelineState()}).</p>
 *
 * <h2>Instruction Set Monitoring</h2>
 *
 * <pre>{@code
 * HardwareOperator.enableInstructionSetMonitoring = true;
 * // Generated MSL source written to results/mtl_instruction_set_N.c
 * }</pre>
 *
 * @see MTLFunction
 * @see MTLComputePipelineState
 * @see MetalComputeContext
 */
public class MetalProgram implements OperationInfo, Signature, Destroyable, ConsoleFeatures {

	/**
	 * Metric tracking Metal kernel compilation time.
	 */
	public static TimingMetric compileTime = Hardware.console.timing("mtlCompile");

	/** Metadata describing this program's operation for identification and profiling. */
	private final OperationMetadata metadata;
	/** The Metal device used to compile and execute this program. */
	private final MTLDevice device;
	/** Name of the kernel function entry point within the MSL source. */
	private final String func;
	/** Metal Shading Language source code for this program. */
	private final String src;

	/** The lanes the kernel assigns to each work item, the number its source was rendered for. */
	private final int laneCount;

	/** The compiled Metal function object used to create pipeline states. */
	private MTLFunction function;

	/**
	 * Creates a Metal program for the specified kernel function and MSL source, whose kernel
	 * assigns one thread to each work item.
	 *
	 * @param ctx The {@link MetalComputeContext} providing device access
	 * @param metadata Operation metadata for identification and profiling
	 * @param func Name of the kernel function in the MSL source
	 * @param src Metal Shading Language source code
	 */
	public MetalProgram(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		this(ctx, metadata, func, src, 1);
	}

	/**
	 * Creates a Metal program for the specified kernel function and MSL source, whose kernel
	 * assigns the given number of lanes to each work item.
	 *
	 * @param ctx The {@link MetalComputeContext} providing device access
	 * @param metadata Operation metadata for identification and profiling
	 * @param func Name of the kernel function in the MSL source
	 * @param src Metal Shading Language source code, rendered for {@code laneCount} lanes
	 * @param laneCount The lanes the kernel assigns to each work item, at least 1
	 * @throws IllegalArgumentException if {@code laneCount} is less than 1
	 */
	public MetalProgram(MetalComputeContext ctx, OperationMetadata metadata, String func, String src,
						int laneCount) {
		if (laneCount < 1) {
			throw new IllegalArgumentException("A work item needs at least one lane");
		}

		this.device = ctx.getMtlDevice();
		this.metadata = (metadata == null ?
				new OperationMetadata((String) null, null) : metadata)
				.withContextName(ctx.getDataContext().getName());
		this.func = func;
		this.src = src;
		this.laneCount = laneCount;
	}

	/**
	 * Returns the kernel function name.
	 *
	 * @return Name of the Metal kernel function
	 */
	public String getName() { return func; }

	/**
	 * Returns metadata about this operation.
	 *
	 * @return {@link OperationMetadata} for profiling and identification
	 */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Returns the Metal device this program is compiled for.
	 *
	 * @return The {@link MTLDevice} instance
	 */
	public MTLDevice getDevice() { return device; }

	/**
	 * Returns the lanes the kernel assigns to each work item: the threads dispatched for every
	 * work item, which execute it together as one SIMD group.
	 *
	 * @return the lanes of each work item, at least 1
	 */
	public int getLaneCount() { return laneCount; }

	/**
	 * Returns the compiled Metal function.
	 *
	 * @return The {@link MTLFunction} instance, or null if not yet compiled
	 */
	public MTLFunction getFunction() { return function; }

	/**
	 * Compiles the Metal Shading Language source into a {@link MTLFunction}.
	 *
	 * <p>Creates a Metal function object from the MSL source code. Records instruction
	 * set to disk if monitoring is enabled. Tracks compilation time metrics.</p>
	 *
	 * @throws HardwareException if compilation fails
	 */
	public void compile() {
		if (HardwareOperator.enableInstructionSetMonitoring || (HardwareOperator.enableLargeInstructionSetMonitoring && src.length() > 10000)) {
			recordInstructionSet();
		}

		long start = System.nanoTime();

		try {
			function = device.newFunction(func, src);

			if (function.getNativePointer() == 0) {
				if (HardwareOperator.enableFailedInstructionSetMonitoring)
					recordInstructionSet();

				throw new HardwareException("Failed to compile " + func);
			}
		} finally {
			compileTime.addEntry(System.nanoTime() - start);
		}
	}

	/**
	 * Writes the MSL source code to a file for debugging and analysis.
	 *
	 * <p>Saves source to {@code results/mtl_instruction_set_N.c} where N is an
	 * incrementing counter. Called automatically when instruction set monitoring
	 * is enabled.</p>
	 */
	protected void recordInstructionSet() {
		log("Wrote " + HardwareOperator.recordInstructionSet("mtl", "c", src));
	}

	/**
	 * Creates a compute pipeline state for executing this program's function.
	 *
	 * <p>Must be called after {@link #compile()}. The pipeline state is required
	 * for dispatching compute commands.</p>
	 *
	 * <p>A kernel of several {@link #getLaneCount() lanes} per work item relies on each SIMD group
	 * executing exactly the lanes of one work item, so a pipeline whose
	 * {@link MTLComputePipelineState#threadExecutionWidth() execution width} differs from the
	 * lanes is released and refused.</p>
	 *
	 * @return New {@link MTLComputePipelineState} for the compiled function
	 * @throws HardwareException if program has not been compiled, or if its kernel assigns several
	 *                           lanes to each work item and the pipeline's SIMD groups have a
	 *                           different number of threads
	 */
	public MTLComputePipelineState newComputePipelineState() {
		if (function == null) {
			throw new HardwareException("MetalProgram unavailable");
		}

		MTLComputePipelineState pipeline = device.newComputePipelineState(function);

		if (laneCount > 1 && pipeline.threadExecutionWidth() != laneCount) {
			int width = pipeline.threadExecutionWidth();
			pipeline.release();
			throw new HardwareException(func + " assigns " + laneCount +
					" lanes to each work item, but its SIMD groups have " + width + " threads");
		}

		return pipeline;
	}

	/**
	 * Returns the signature for this program from metadata.
	 *
	 * @return Operation signature string
	 */
	@Override
	public String signature() { return getMetadata().getSignature(); }

	/**
	 * Checks if this program has been destroyed.
	 *
	 * @return True if the Metal function has been released
	 */
	public boolean isDestroyed() {
		return function == null;
	}

	/**
	 * Releases the compiled Metal function and marks this program as destroyed.
	 *
	 * <p>Frees native Metal resources. After calling destroy, this program
	 * cannot be used to create pipeline states.</p>
	 */
	@Override
	public void destroy() {
		if (function != null) {
			function.release();
			function = null;
		}
	}

	/**
	 * Returns a human-readable description of this program.
	 *
	 * @return Display name from metadata
	 */
	@Override
	public String describe() {
		return getMetadata().getDisplayName();
	}

	/**
	 * Returns the console for logging output.
	 *
	 * @return The {@link Console} instance for hardware logging
	 */
	@Override
	public Console console() { return Hardware.console; }

	/**
	 * Factory method to create a new Metal program.
	 *
	 * @param ctx The {@link MetalComputeContext} providing device access
	 * @param metadata Operation metadata for identification and profiling
	 * @param func Name of the kernel function in the MSL source
	 * @param src Metal Shading Language source code
	 * @return New {@link MetalProgram} instance
	 */
	public static MetalProgram create(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		return new MetalProgram(ctx, metadata, func, src);
	}
}
