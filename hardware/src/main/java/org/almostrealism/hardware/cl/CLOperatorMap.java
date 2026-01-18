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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.InstructionSet;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.CLException;
import org.jocl.cl_program;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * {@link InstructionSet} implementation that maps kernel names to {@link CLOperator} instances.
 *
 * <p>{@link CLOperatorMap} wraps a compiled OpenCL {@link cl_program} and provides thread-local
 * access to {@link CLOperator} instances for each kernel function.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * CLOperatorMap map = new CLOperatorMap(context, metadata, source, profile);
 * // Compiles source to cl_program during construction
 *
 * // Get operator for kernel function
 * CLOperator matmul = map.get("matmul", 3);  // 3 arguments
 * matmul.accept(args);
 *
 * // Thread-local operators
 * // Each thread gets its own CLOperator instance
 * }</pre>
 *
 * <h2>Thread-Local Operators</h2>
 *
 * <p>Operators are stored per-thread to avoid synchronization:</p>
 *
 * <pre>{@code
 * // Thread A
 * CLOperator op1 = map.get("kernel");  // Creates new operator
 * op1.accept(args);
 *
 * // Thread B (concurrent)
 * CLOperator op2 = map.get("kernel");  // Creates separate operator
 * op2.accept(args);  // No synchronization needed
 * }</pre>
 *
 * <h2>Exception Processing</h2>
 *
 * <p>Converts {@link CLException} to {@link HardwareException} with source context:</p>
 *
 * <pre>{@code
 * // When kernel creation fails:
 * throw new HardwareException(
 *     "\"invalidKernel\" is not a valid kernel name",
 *     clException,
 *     prog.getSource());  // Includes full source for debugging
 * }</pre>
 *
 * @see CLOperator
 * @see CLProgram
 * @see InstructionSet
 */
public class CLOperatorMap implements InstructionSet, BiFunction<String, CLException, HardwareException> {
	/** The compute context for OpenCL operations. */
	private CLComputeContext context;

	/** The compiled OpenCL program. */
	private CLProgram prog;

	/** Thread-local map of operator name to CLOperator instances. */
	private ThreadLocal<Map<String, CLOperator>> operators;

	/** List of all created operators for cleanup during destroy. */
	private List<CLOperator> allOperators;

	/** Consumer for recording execution timing data. */
	private Consumer<RunData> profile;

	/**
	 * Creates a new CLOperatorMap from OpenCL source code.
	 *
	 * @param ctx       the compute context for OpenCL operations
	 * @param metadata  metadata describing the operation
	 * @param src       the OpenCL C source code to compile
	 * @param profile   consumer for recording execution timing, or null to skip profiling
	 */
	public CLOperatorMap(CLComputeContext ctx, OperationMetadata metadata, String src, Consumer<RunData> profile) {
		this.context = ctx;
		this.operators = new ThreadLocal<>();
		this.allOperators = new ArrayList<>();
		this.profile = profile;
		init(metadata, src);
	}

	/**
	 * Compiles the OpenCL source code and creates the program.
	 *
	 * @param metadata  metadata describing the operation
	 * @param src       the OpenCL C source code to compile
	 * @throws RuntimeException if compilation fails
	 */
	protected void init(OperationMetadata metadata, String src) {
		if (CLOperator.enableLog) {
			System.out.println("HardwareOperatorMap: init " + metadata.getDisplayName());
		}

		if (src.length() > 100000) {
			System.out.println("WARN: CLOperatorMap source length is " + src.length() + " characters");
		}

		if (CLOperator.enableVerboseLog) {
			System.out.println("Source:");
			System.out.println(src);
		}

		prog = CLProgram.create(context, metadata, src);

		RuntimeException ex = null;

		try {
			prog.compile();
		} catch (RuntimeException e) {
			ex = e;
		}

		if (ex != null) {
			if (CLOperator.enableLog) {
				System.out.println("Error compiling:\n" + src);
			}

			throw ex;
		}
	}

	/**
	 * Returns the operator for the specified kernel function with the default argument count of 2.
	 *
	 * @param key  the kernel function name
	 * @return the CLOperator for executing the kernel
	 */
	public CLOperator get(String key) {
		return get(key, 2);
	}

	/**
	 * Returns the operator for the specified kernel function.
	 * Creates a new thread-local operator if one does not exist for the current thread.
	 *
	 * @param key       the kernel function name
	 * @param argCount  the number of arguments the kernel expects
	 * @return the CLOperator for executing the kernel
	 */
	public CLOperator get(String key, int argCount) {
		Map<String, CLOperator> ops = operators.get();

		if (ops == null) {
			ops = new HashMap<>();
			operators.set(ops);
		}

		if (!ops.containsKey(key)) {
			CLOperator op = new CLOperator(context, prog, key, argCount, profile, this);
			ops.put(key, op);
			allOperators.add(op);
		}

		return ops.get(key);
	}

	/**
	 * Converts a CLException to a HardwareException with source context information.
	 *
	 * @param name  the kernel function name that caused the exception
	 * @param e     the original OpenCL exception
	 * @return a HardwareException containing error details and source code for debugging
	 */
	@Override
	public HardwareException apply(String name, CLException e) {
		if ("CL_INVALID_KERNEL_NAME".equals(e.getMessage())) {
			return new HardwareException("\"" + name + "\" is not a valid kernel name", e, prog.getSource());
		} else {
			return new HardwareException(e.getMessage(), e, prog.getSource());
		}
	}

	/**
	 * Returns whether this operator map has been destroyed.
	 *
	 * @return true if {@link #destroy()} has been called, false otherwise
	 */
	@Override
	public boolean isDestroyed() {
		return operators == null;
	}

	/**
	 * Release the {@link cl_program} and the {@link ThreadLocal}
	 * that stores the {@link CLOperator}s, destroying all
	 * {@link CLOperator}s in the process.
	 *
	 * @see  CLProgram#destroy()
	 * @see  CLOperator#destroy()
	 */
	public void destroy() {
		if (prog != null) prog.destroy();

		if (operators != null) {
			operators.remove();
			operators = null;
		}

		if (allOperators != null) {
			allOperators.forEach(CLOperator::destroy);
			allOperators = null;
		}
	}
}
