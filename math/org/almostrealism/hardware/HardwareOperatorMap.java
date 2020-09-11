/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.hardware;

import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.cl_program;

/**
 * Wrapper for a cl_program that contains the accelerated functions used in the running application.
 */
public class HardwareOperatorMap<T extends MemWrapper> {
	private cl_program prog;

	protected HardwareOperatorMap() { }

	public HardwareOperatorMap(Hardware h, String src) {
		init(h, src);
	}

	protected void init(Hardware h, String src) {
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

	public HardwareOperator<T> get(String key) { return new HardwareOperator<>(prog, key); }

	public HardwareOperator<T> get(String key, int argCount) { return new HardwareOperator<>(prog, key, argCount); }

	@Override
	public void finalize() { CL.clReleaseProgram(prog); }
}
