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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.jocl.cl_command_queue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link InstructionSet} implementation for executing compiled native C code via JNI.
 *
 * <p>{@link NativeInstructionSet} serves as the bridge between Java computation graphs and
 * compiled native code. Implementers of this interface are pre-generated Java classes with
 * native method declarations that link to compiled shared libraries:</p>
 * <ul>
 *   <li><strong>JNI linking:</strong> Maps Java native methods to C functions by naming convention</li>
 *   <li><strong>Argument marshalling:</strong> Converts {@link MemoryData} to native pointers</li>
 *   <li><strong>Execution coordination:</strong> Manages kernel launches with parallelism control</li>
 *   <li><strong>Optional CL integration:</strong> Supports OpenCL command queues for CL memory</li>
 * </ul>
 *
 * <h2>JNI Function Naming Convention</h2>
 *
 * <p>The native C function name follows JNI conventions:</p>
 * <pre>{@code
 * // For class: org.almostrealism.generated.GeneratedOperation42
 * // Native function name:
 * Java_org_almostrealism_generated_GeneratedOperation42_apply
 *
 * // getFunctionName() returns:
 * "Java_org_almostrealism_generated_GeneratedOperation42_apply"
 * }</pre>
 *
 * <h2>Execution Flow</h2>
 *
 * <p>When {@link #apply(long, long, MemoryData...)} is called:</p>
 * <pre>
 * 1. Extract native pointers   MemoryData -> RAM.getContentPointer()
 * 2. Extract offsets/sizes      MemoryData.getOffset(), getAtomicMemLength()
 * 3. Optional: Get CL queue     Check for CLComputeContext, get cl_command_queue
 * 4. Call native method        apply(commandQueue, pointers, offsets, ...)
 * 5. Native C code executes    Via JNI bridge
 * </pre>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * // Compiler reserves a pre-generated class
 * NativeInstructionSet instructionSet = (NativeInstructionSet)
 *     compiler.reserveLibraryTarget();
 *
 * // Set context and metadata
 * instructionSet.setComputeContext(computeContext);
 * instructionSet.setMetadata(metadata);
 * instructionSet.setParallelism(8);  // 8-way parallelism
 *
 * // Compile C code and load library
 * compiler.compile(instructionSet, generatedCCode);
 *
 * // Execute
 * PackedCollection input = ...;
 * PackedCollection output = ...;
 * instructionSet.apply(0, 1000, input, output);
 * // Native code runs, modifies output in-place
 * }</pre>
 *
 * <h2>Argument Marshalling</h2>
 *
 * <p>The apply(MemoryData...) method chain extracts native information:</p>
 * <pre>{@code
 * // Java side:
 * MemoryData input = ...;  // offset=100, atomicMemLength=3
 * instructionSet.apply(0, 1000, input);
 *
 * // Marshalled to native:
 * apply(
 *   commandQueue = -1,              // No CL queue
 *   pointers = [input.getMem().getContentPointer()],
 *   offsets = [100],                // Start at element 100
 *   sizes = [3],                    // 3 elements per atomic unit
 *   count = 1,                      // 1 argument
 *   globalId = 0,                   // Kernel start index
 *   kernelSize = 1000               // Total kernel iterations
 * )
 * }</pre>
 *
 * <h2>OpenCL Integration</h2>
 *
 * <p>If the compute context includes a {@link CLComputeContext}, the OpenCL command queue
 * pointer is passed to native code:</p>
 * <pre>{@code
 * // With OpenCL:
 * // - commandQueue = cl_command_queue native pointer
 * // - Native code can use clEnqueue* APIs
 *
 * // Without OpenCL:
 * // - commandQueue = -1
 * // - Native code uses standard RAM access
 * }</pre>
 *
 * <h2>Parallelism Configuration</h2>
 *
 * <p>The {@link #setParallelism(int)} value determines multi-threaded execution:</p>
 * <pre>{@code
 * instructionSet.setParallelism(8);
 *
 * // Native C code receives parallelism value
 * // Can spawn threads or adjust iteration strategy
 * // Example: OpenMP parallel for with num_threads(8)
 * }</pre>
 *
 * <h2>Invocation Tracking</h2>
 *
 * <p>Total invocations are tracked via {@link NativeComputeContext#totalInvocations}:</p>
 * <pre>{@code
 * // Every apply() call increments counter
 * instructionSet.apply(...);
 * long count = NativeComputeContext.totalInvocations;
 *
 * // Verbose mode logs every 100,000 invocations
 * NativeComputeContext.enableVerbose = true;
 * }</pre>
 *
 * <h2>Native Method Declaration</h2>
 *
 * <p>Implementations must declare the native method:</p>
 * <pre>{@code
 * public class GeneratedOperation0 extends BaseGeneratedOperation
 *         implements NativeInstructionSet {
 *
 *     // Native method linked to compiled C code
 *     public native void apply(long commandQueue, long[] arg,
 *                             int[] offset, int[] size, int count,
 *                             int globalId, long kernelSize);
 * }
 * }</pre>
 *
 * <h2>Argument Validation</h2>
 *
 * <p>The {@link #apply(RAM[], int[], int[], int, long)} method validates arguments:</p>
 * <pre>{@code
 * // Throws NullPointerException if any argument is null
 * RAM[] args = new RAM[] { null, validRAM };
 * instructionSet.apply(args, ...);
 * // -> NullPointerException: "Argument 0 is null"
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Native instruction sets are loaded once and reused:</p>
 * <ul>
 *   <li><strong>Created:</strong> By {@link NativeCompiler#reserveLibraryTarget()}</li>
 *   <li><strong>Compiled:</strong> Via {@link NativeCompiler#compile(NativeInstructionSet, String)}</li>
 *   <li><strong>Loaded:</strong> Via {@code System.load(libraryPath)}</li>
 *   <li><strong>Executed:</strong> Multiple times via {@link #apply(long, long, MemoryData...)}</li>
 *   <li><strong>Destroyed:</strong> {@link #destroy()} is currently a no-op (library stays loaded)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Multiple threads can invoke {@link #apply(long, long, MemoryData...)} concurrently on the
 * same instruction set. The underlying native code must be thread-safe if this is required.</p>
 *
 * @see InstructionSet
 * @see NativeCompiler
 * @see NativeComputeContext
 * @see NativeExecution
 */
public interface NativeInstructionSet extends InstructionSet, ConsoleFeatures {
	/**
	 * Returns the JNI function name for the native apply method.
	 * The name follows JNI naming conventions: {@code Java_<package>_<class>_apply}
	 * with dots replaced by underscores.
	 *
	 * @return the fully qualified JNI function name
	 */
	default String getFunctionName() {
		return "Java_" +
				getClass().getName().replaceAll("\\.", "_") +
				"_apply";
	}

	/**
	 * Returns the compute context used for execution.
	 *
	 * @return the compute context
	 */
	ComputeContext<MemoryData> getComputeContext();

	/**
	 * Sets the compute context for execution.
	 *
	 * @param context the compute context to use
	 */
	void setComputeContext(ComputeContext<MemoryData> context);

	/**
	 * Returns the operation metadata associated with this instruction set.
	 *
	 * @return the operation metadata
	 */
	OperationMetadata getMetadata();

	/**
	 * Sets the operation metadata for profiling and debugging.
	 *
	 * @param metadata the operation metadata
	 */
	void setMetadata(OperationMetadata metadata);

	/**
	 * Returns the parallelism level for native execution.
	 *
	 * @return the parallelism level
	 */
	int getParallelism();

	/**
	 * Sets the parallelism level for multi-threaded native execution.
	 *
	 * @param parallelism the number of parallel execution threads
	 */
	void setParallelism(int parallelism);

	/**
	 * Returns a {@link NativeExecution} wrapper for this instruction set.
	 *
	 * @param function ignored; native instruction sets have a single entry point
	 * @param argCount the number of arguments expected by the native function
	 * @return a new {@link NativeExecution} instance
	 */
	@Override
	default Execution get(String function, int argCount) {
		return new NativeExecution(this, argCount);
	}

	/** Returns {@code false}; native instruction sets are not destroyed after use. */
	@Override
	default boolean isDestroyed() {
		return false;
	}

	/** No-op; native libraries remain loaded for the lifetime of the JVM. */
	@Override
	default void destroy() { }

	/**
	 * Executes the native kernel with the given arguments.
	 * Extracts memory pointers, offsets, and sizes from the {@link MemoryData} arguments
	 * and delegates to the lower-level apply method.
	 *
	 * @param idx the starting global index for kernel execution
	 * @param kernelSize the total number of kernel iterations
	 * @param args the memory data arguments to pass to the native function
	 * @throws UnsupportedOperationException if idx exceeds {@link Integer#MAX_VALUE}
	 */
	default void apply(long idx, long kernelSize, MemoryData... args) {
		long id = NativeComputeContext.totalInvocations++;

		if (NativeComputeContext.enableVerbose && (id + 1) % 100000 == 0) {
			System.out.println("NativeInstructionSet: " + id);
		}

		if (idx > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}

		int i = (int) idx;

		apply(Stream.of(args).map(MemoryData::getMem).toArray(RAM[]::new),
					Stream.of(args).mapToInt(MemoryData::getOffset).toArray(),
					Stream.of(args).mapToInt(MemoryData::getAtomicMemLength).toArray(),
					i, kernelSize);
	}

	/**
	 * Executes the native kernel with RAM arrays and explicit offsets/sizes.
	 * Validates that no arguments are null and obtains the OpenCL command queue
	 * if available from the compute context.
	 *
	 * @param args the RAM memory regions containing the data
	 * @param offsets the starting offset within each RAM region
	 * @param sizes the size of each memory region in atomic units
	 * @param globalId the starting global index for kernel execution
	 * @param kernelSize the total number of kernel iterations
	 * @throws NullPointerException if any element of args is null
	 */
	default void apply(RAM[] args, int[] offsets, int[] sizes, int globalId, long kernelSize) {
		int bytes = getComputeContext().getDataContext().getPrecision().bytes();

		for (int i = 0; i < args.length; i++) {
			if (args[i] == null) {
				throw new NullPointerException("Argument " + i + " is null");
			}
		}

		apply(Optional.ofNullable(getComputeContext())
					.map(ComputeContext::getDataContext)
					.map(DataContext::getComputeContexts)
					.stream()
					.flatMap(List::stream)
					.filter(CLComputeContext.class::isInstance)
					.map(CLComputeContext.class::cast)
					.map(CLComputeContext::getClQueue)
					.map(cl_command_queue::getNativePointer).findFirst().orElse(-1L),
				args, offsets, sizes, globalId, kernelSize);
	}

	/**
	 * Executes the native kernel with an explicit command queue and RAM arrays.
	 * Converts RAM regions to native content pointers and delegates to the
	 * primitive array apply method.
	 *
	 * @param commandQueue the OpenCL command queue pointer, or -1 if not using OpenCL
	 * @param args the RAM memory regions containing the data
	 * @param offsets the starting offset within each RAM region
	 * @param sizes the size of each memory region in atomic units
	 * @param globalId the starting global index for kernel execution
	 * @param kernelSize the total number of kernel iterations
	 */
	default void apply(long commandQueue, RAM[] args, int[] offsets, int[] sizes, int globalId, long kernelSize) {
		apply(commandQueue,
				Stream.of(args).mapToLong(RAM::getContentPointer).toArray(),
				offsets, sizes, args.length, globalId, kernelSize);
	}

	/**
	 * Native method to execute the compiled kernel.
	 * Implementations must declare this as a native method linked to compiled C code.
	 *
	 * @param commandQueue the OpenCL command queue pointer, or -1 if not using OpenCL
	 * @param arg array of native memory pointers for each argument
	 * @param offset the starting offset within each memory region
	 * @param size the size of each memory region in atomic units
	 * @param count the number of arguments
	 * @param globalId the starting global index for kernel execution
	 * @param kernelSize the total number of kernel iterations
	 */
	void apply(long commandQueue, long[] arg, int[] offset, int[] size, int count, int globalId, long kernelSize);

	/**
	 * Returns the hardware console for logging.
	 *
	 * @return the hardware console
	 */
	@Override
	default Console console() { return Hardware.console; }
}
