/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.cl_program;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Wrapper for a {@link cl_program} that contains the {@link HardwareOperator}s
 * used by {@link org.almostrealism.hardware.AcceleratedFunctions}.
 *
 * @author  Michael Murray
 */
public class HardwareOperatorMap<T extends MemoryData> implements BiFunction<String, CLException, HardwareException> {
	private cl_program prog;
	private String src;

	private ThreadLocal<Map<String, HardwareOperator<T>>> operators;

	protected HardwareOperatorMap() { }

	public HardwareOperatorMap(Hardware h, String src) {
		this.src = src;
		this.operators = new ThreadLocal<>();
		init(h);
	}

	protected void init(Hardware h) {
		int[] result = new int[1];
		prog = CL.clCreateProgramWithSource(h.getContext(), 1, new String[] { src }, null, result);
		if (result[0] != 0) throw new RuntimeException("Error creating HardwareOperatorMap: " + result[0]);

		RuntimeException ex = null;

		try {
			int r = CL.clBuildProgram(prog, 0, null, null, null, null);
			if (r != 0) ex = new RuntimeException("Error building HardwareOperatorMap:" + r);
		} catch (CLException e) {
			ex = e;
		}

		if (ex != null) {
			System.out.println("Error compiling:\n" + src);
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
			ops.put(key, new HardwareOperator<>(prog, key, argCount, this));
		}

		return ops.get(key);
	}

	@Override
	public HardwareException apply(String name, CLException e) {
		if ("CL_INVALID_KERNEL_NAME".equals(e.getMessage())) {
			return new HardwareException("\"" + name + "\" is not a valid kernel name", e, src);
		} else {
			return new HardwareException(e.getMessage(), e, src);
		}
	}

	/**
	 * Release the {@link cl_program} and the {@link ThreadLocal}
	 * that stores the {@link HardwareOperator}s.
	 */
	public void destroy() {
		CL.clReleaseProgram(prog);
		if (operators != null) {
			operators.remove();
			operators = null;
		}
	}

	/** Delegates to {@link #destroy}. */
	@Override
	public void finalize() { destroy(); }
}
