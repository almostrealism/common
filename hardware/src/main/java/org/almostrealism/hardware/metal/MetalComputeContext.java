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
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.ScopeEncoder;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
									"using metal::tan;\n" +
									"using metal::tanh;\n";

	private MTLDevice mainDevice;
	private MTLCommandQueue queue;
	private MTLCommandQueue fastQueue;

	private MetalCommandRunner runner;

	private Map<String, MetalOperatorMap> instructionSets;

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
			System.out.println("Hardware[" + getDataContext().getName() + "]: Max Threadgroup Size (" +
					mainDevice.maxThreadgroupWidth() + ", " +
					mainDevice.maxThreadgroupHeight() + ", " +
					mainDevice.maxThreadgroupDepth() + ")");
		}

		queue = mainDevice.newCommandQueue();
		if (Hardware.enableVerbose) System.out.println("Hardware[" + getDataContext().getName() + "]: Metal command queue initialized");

		if (enableFastQueue) {
			fastQueue = mainDevice.newCommandQueue();
			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + getDataContext().getName() + "]: Metal fast command queue initialized");
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
	public InstructionSet deliver(Scope scope) {
		if (instructionSets.containsKey(key(scope.getName(), scope.signature()))) {
			if (ScopeSettings.enableInstructionSetReuse) {
				warn("Compiling instruction set " + scope.getName() +
						" with duplicate signature");
			} else {
				warn("Recompiling instruction set " + scope.getName());
			}

			instructionSets.get(key(scope.getName(), scope.signature())).destroy();
		}

		long start = System.nanoTime();
		StringBuilder buf = new StringBuilder();

		try {
			buf.append(includes);
			buf.append("\n");

			ScopeEncoder enc = new ScopeEncoder(pw -> new MetalPrintWriter(pw, scope.getName(), getLanguage().getPrecision()), Accessibility.EXTERNAL);
			buf.append(enc.apply(scope));

			MetalOperatorMap instSet = new MetalOperatorMap(this, scope.getMetadata(), scope.getName(), buf.toString());
			instructionSets.put(key(scope.getName(), scope.signature()), instSet);
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
	protected void destroyed(String name, String signature) {
		if (instructionSets != null) {
			String key = key(name, signature);

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
	public void destroy() {
		List<MetalOperatorMap> toDestroy = new ArrayList<>(instructionSets.values());
		toDestroy.forEach(MetalOperatorMap::destroy);

		this.instructionSets = null;

		if (queue != null) queue.release();
		if (fastQueue != null) fastQueue.release();

		queue = null;
		fastQueue = null;
	}

	/**
	 * Returns the console for logging output.
	 *
	 * @return The {@link Console} instance for hardware logging
	 */
	@Override
	public Console console() { return Hardware.console; }

	protected static String key(String name, String signature) {
		if (ScopeSettings.enableInstructionSetReuse && signature != null) {
			return signature;
		}

		return name;
	}
}
