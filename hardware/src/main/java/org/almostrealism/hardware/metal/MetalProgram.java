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
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

	private static int monitorOutputCount;

	private final OperationMetadata metadata;
	private final MTLDevice device;
	private final String func, src;

	private MTLFunction function;

	/**
	 * Creates a Metal program for the specified kernel function and MSL source.
	 *
	 * @param ctx The {@link MetalComputeContext} providing device access
	 * @param metadata Operation metadata for identification and profiling
	 * @param func Name of the kernel function in the MSL source
	 * @param src Metal Shading Language source code
	 */
	public MetalProgram(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		this.device = ctx.getMtlDevice();
		this.metadata = (metadata == null ?
				new OperationMetadata((String) null, null) : metadata)
				.withContextName(ctx.getDataContext().getName());
		this.func = func;
		this.src = src;
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
		String name = "mtl_instruction_set_" + (monitorOutputCount++) + ".c";

		try {
			Files.writeString(Path.of("results/" + name), src);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}

		ScopeSettings.printStats();
		log("Wrote " + name);
	}

	/**
	 * Creates a compute pipeline state for executing this program's function.
	 *
	 * <p>Must be called after {@link #compile()}. The pipeline state is required
	 * for dispatching compute commands.</p>
	 *
	 * @return New {@link MTLComputePipelineState} for the compiled function
	 * @throws HardwareException if program has not been compiled
	 */
	public MTLComputePipelineState newComputePipelineState() {
		if (function == null) {
			throw new HardwareException("MetalProgram unavailable");
		}

		return device.newComputePipelineState(function);
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
