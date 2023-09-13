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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.OperationMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Wrapper for a {@link MTLFunction} and the {@link MetalOperator}s.
 *
 * @author  Michael Murray
 */
public class MetalOperatorMap implements InstructionSet {
	private Supplier<MetalCommandRunner> runner;
	private MetalProgram prog;

	private ThreadLocal<Map<String, MetalOperator>> operators;
	private List<MetalOperator> allOperators;

	public MetalOperatorMap(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		this.operators = new ThreadLocal<>();
		this.allOperators = new ArrayList<>();
		init(ctx, metadata, func, src);
	}

	protected void init(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		if (MetalOperator.enableLog) {
			System.out.println("MetalOperatorMap: init " + metadata.getDisplayName());
		}

		if (MetalOperator.enableVerboseLog) {
			System.out.println("Source:");
			System.out.println(src);
		}

		prog = MetalProgram.create(ctx, metadata, func, src);

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

		runner = ctx::getCommandRunner;
	}

	public MetalOperator get(String key, int argCount) {
		Map<String, MetalOperator> ops = operators.get();

		if (ops == null) {
			ops = new HashMap<>();
			operators.set(ops);
		}

		if (!ops.containsKey(key)) {
			MetalOperator op = new MetalOperator(runner, prog, key, argCount);
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
	public void destroy() {
		if (prog != null) prog.destroy();

		if (operators != null) {
			operators.remove();
			operators = null;
		}

		if (allOperators != null) {
			allOperators.forEach(MetalOperator::destroy);
			allOperators = null;
		}
	}
}
