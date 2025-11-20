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

import io.almostrealism.code.InstructionSet;
import io.almostrealism.profile.OperationMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link InstructionSet} that maps kernel names to thread-local {@link MetalOperator} instances.
 *
 * <p>Wraps a compiled {@link MetalProgram} and provides thread-safe access to Metal kernel operators.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * MetalOperatorMap map = new MetalOperatorMap(context, metadata, funcName, source);
 * // Compiles Metal source during construction
 *
 * MetalOperator op = map.get("matmul", 3);  // 3 arguments
 * op.accept(args);
 *
 * // Thread-local: Each thread gets its own operator instance
 * }</pre>
 *
 * @see MetalOperator
 * @see MetalProgram
 * @see InstructionSet
 */
public class MetalOperatorMap implements InstructionSet {
	private MetalComputeContext context;
	private MetalProgram prog;

	private ThreadLocal<Map<String, MetalOperator>> operators;
	private List<MetalOperator> allOperators;

	public MetalOperatorMap(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		this.context = ctx;
		this.operators = new ThreadLocal<>();
		this.allOperators = new ArrayList<>();
		init(metadata, func, src);
	}

	protected void init(OperationMetadata metadata, String func, String src) {
		if (MetalOperator.enableLog) {
			System.out.println("MetalOperatorMap: init " + metadata.getDisplayName());
		}

		if (MetalOperator.enableVerboseLog) {
			System.out.println("Source:");
			System.out.println(src);
		}

		prog = MetalProgram.create(context, metadata, func, src);

		RuntimeException ex = null;

		try {
			prog.compile();
		} catch (RuntimeException e) {
			ex = e;
		}

		if (ex != null) {
			if (MetalOperator.enableLog) {
				System.out.println("Error compiling:\n" + src);
			}

			throw ex;
		}
	}

	public MetalOperator get(String key, int argCount) {
		Map<String, MetalOperator> ops = operators.get();

		if (ops == null) {
			ops = new HashMap<>();
			operators.set(ops);
		}

		if (!ops.containsKey(key)) {
			MetalOperator op = new MetalOperator(context, prog, key, argCount);
			ops.put(key, op);
			allOperators.add(op);
		}

		return ops.get(key);
	}

	@Override
	public boolean isDestroyed() {
		return operators == null;
	}

	/**
	 * Release the {@link MetalProgram} and the {@link ThreadLocal}
	 * that stores the {@link MetalOperator}s, destroying all
	 * {@link MetalOperator}s in the process.
	 *
	 * @see  MetalProgram#destroy()
	 * @see  MetalOperator#destroy()
	 */
	@Override
	public void destroy() {
		String name = null;
		String signature = null;

		if (prog != null) {
			name = prog.getName();
			signature = prog.signature();
			prog.destroy();
		}

		if (operators != null) {
			operators.remove();
			operators = null;
		}

		if (allOperators != null) {
			allOperators.forEach(MetalOperator::destroy);
			allOperators = null;
		}

		if (name != null || signature != null) {
			context.destroyed(name, signature);
		}
	}
}
