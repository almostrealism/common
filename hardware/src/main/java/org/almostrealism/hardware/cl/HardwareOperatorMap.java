/*
 * Copyright 2021 Michael Murray
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
import io.almostrealism.code.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;
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
 * Wrapper for a {@link cl_program} that contains the {@link HardwareOperator}s
 * used by {@link org.almostrealism.hardware.AcceleratedFunctions}.
 *
 * @author  Michael Murray
 */
public class HardwareOperatorMap<T extends MemoryData> implements InstructionSet, BiFunction<String, CLException, HardwareException> {
	private CLProgram prog;

	private ThreadLocal<Map<String, HardwareOperator<T>>> operators;
	private List<HardwareOperator<T>> allOperators;
	private Consumer<RunData> profile;

	public HardwareOperatorMap(CLComputeContext h, OperationMetadata metadata, String src, Consumer<RunData> profile) {
		this.operators = new ThreadLocal<>();
		this.allOperators = new ArrayList<>();
		this.profile = profile;
		init(h, metadata, src);
	}

	protected void init(CLComputeContext h, OperationMetadata metadata, String src) {
		if (HardwareOperator.enableLog) {
			System.out.println("HardwareOperatorMap: init " + metadata.getDisplayName());
		}

		if (HardwareOperator.enableVerboseLog) {
			System.out.println("Source:");
			System.out.println(src);
		}

		prog = CLProgram.create(h, metadata, src);

		RuntimeException ex = null;

		try {
			prog.compile();
		} catch (RuntimeException e) {
			ex = e;
		}

		if (ex != null) {
			if (HardwareOperator.enableLog) {
				System.out.println("Error compiling:\n" + src);
			}

			throw ex;
		}
	}

	public HardwareOperator<T> get(String key) {
		return get(key, 2);
	}

	public HardwareOperator<T> get(String key, int argCount) {
		Map<String, HardwareOperator<T>> ops = operators.get();

		if (ops == null) {
			ops = new HashMap<>();
			operators.set(ops);
		}

		if (!ops.containsKey(key)) {
			HardwareOperator<T> op = new HardwareOperator<>(prog, key, argCount, profile, this);
			ops.put(key, op);
			allOperators.add(op);
		}

		return ops.get(key);
	}

	@Override
	public HardwareException apply(String name, CLException e) {
		if ("CL_INVALID_KERNEL_NAME".equals(e.getMessage())) {
			return new HardwareException("\"" + name + "\" is not a valid kernel name", e, prog.getSource());
		} else {
			return new HardwareException(e.getMessage(), e, prog.getSource());
		}
	}

	@Override
	public boolean isDestroyed() {
		return operators == null;
	}

	/**
	 * Release the {@link cl_program} and the {@link ThreadLocal}
	 * that stores the {@link HardwareOperator}s, destroying all
	 * {@link HardwareOperator}s in the process.
	 *
	 * @see  CLProgram#destroy()
	 * @see  HardwareOperator#destroy()
	 */
	public void destroy() {
		if (prog != null) prog.destroy();

		if (operators != null) {
			operators.remove();
			operators = null;
		}

		if (allOperators != null) {
			allOperators.forEach(HardwareOperator::destroy);
			allOperators = null;
		}
	}
}
