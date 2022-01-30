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

import org.almostrealism.hardware.HardwareException;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.cl_program;

public class CLProgram {
	private cl_program prog;
	private final String src;

	private CLProgram(cl_program prog, String src) {
		this.prog = prog;
		this.src = src;
	}

	public cl_program getProgram() {
		return prog;
	}

	public String getSource() {
		return src;
	}

	public void compile() {
		// TODO  CLExceptionProcessor
		try {
			int r = CL.clBuildProgram(getProgram(), 0, null, null, null, null);
			if (r != 0) throw new RuntimeException("Error building CLProgram:" + r);
		} catch (CLException e) {
			throw new HardwareException("Error building CLProgram", e, src);
		}
	}

	public void destroy() {
		CL.clReleaseProgram(prog);
		prog = null;
	}

	public static CLProgram create(CLComputeContext h, String src) {
		int[] result = new int[1];
		cl_program prog = CL.clCreateProgramWithSource(h.getCLContext(), 1, new String[] { src }, null, result);
		if (result[0] != 0) throw new RuntimeException("Error creating HardwareOperatorMap: " + result[0]);

		return new CLProgram(prog, src);
	}
}
