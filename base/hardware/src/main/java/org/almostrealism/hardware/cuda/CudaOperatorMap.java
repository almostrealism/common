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

import io.almostrealism.code.InstructionSet;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link InstructionSet} for one compiled {@link CudaProgram}.
 *
 * <p>The program is compiled once, when the map is created. Each thread receives its own
 * {@link CudaOperator} per key, since an operator carries per-dispatch state such as its
 * work size.</p>
 */
public class CudaOperatorMap implements InstructionSet, ConsoleFeatures {
	/** The context the program belongs to. */
	private final CudaComputeContext context;

	/** The compiled program. */
	private CudaProgram prog;

	/** The operators created by each thread, by key. */
	private ThreadLocal<Map<String, CudaOperator>> operators;

	/** Every operator created, for destruction. */
	private List<CudaOperator> allOperators;

	/**
	 * Compiles the given source and creates the instruction set.
	 *
	 * @param context  the context to compile for
	 * @param metadata metadata describing the operation
	 * @param func     the kernel name
	 * @param src      the CUDA C++ source
	 * @throws org.almostrealism.hardware.HardwareException if compilation fails
	 */
	public CudaOperatorMap(CudaComputeContext context, OperationMetadata metadata, String func, String src) {
		this.context = context;
		this.operators = new ThreadLocal<>();
		this.allOperators = new ArrayList<>();
		this.prog = new CudaProgram(context, metadata, func, src);

		if (CudaOperator.enableVerboseLog) {
			log("Source:");
			log(src);
		}

		prog.compile();
	}

	@Override
	public synchronized CudaOperator get(String key, int argCount) {
		Map<String, CudaOperator> ops = operators.get();
		if (ops == null) {
			ops = new HashMap<>();
			operators.set(ops);
		}

		CudaOperator op = ops.get(key);
		if (op == null) {
			op = new CudaOperator(context, prog, key, argCount);
			ops.put(key, op);
			allOperators.add(op);
		}

		return op;
	}

	@Override
	public boolean isDestroyed() {
		return operators == null;
	}

	@Override
	public synchronized void destroy() {
		String name = null;
		String signature = null;

		if (prog != null) {
			name = prog.getName();
			signature = prog.signature();
			prog.destroy();
			prog = null;
		}

		if (operators != null) {
			operators.remove();
			operators = null;
		}

		allOperators = null;

		if (name != null || signature != null) {
			context.destroyed(name, signature);
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
