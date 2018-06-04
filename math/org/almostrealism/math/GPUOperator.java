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

package org.almostrealism.math;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.relation.Operator;

import org.almostrealism.util.Factory;
import org.jocl.*;

public class GPUOperator<T extends MemWrapper> implements Operator<T>, Factory<cl_kernel> {
	private cl_program prog;
	private String name;
	private boolean scalar;

	private cl_kernel kernel;

	public GPUOperator(cl_program program, String name, boolean scalar) {
		this.prog = program;
		this.name = name;
		this.scalar = scalar;
	}

	// TODO  How do these kernels get released when done?
	@Override
	public cl_kernel construct() { return CL.clCreateKernel(prog, name, null); }

	@Override
	public Scope<? extends Variable> getScope(String prefix) {
		return null;
	}

	@Override
	public synchronized T evaluate(Object[] args) {
		if (kernel == null) kernel = construct();

		if (scalar) {
			double[] res = new double[1];
			CL.clSetKernelArg(kernel, 0, Sizeof.cl_double, Pointer.to(res)); // result
			CL.clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(((MemWrapper) args[0]).getMem())); // this
			CL.clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(((MemWrapper) args[1]).getMem())); // target
			CL.clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[] { 0 }));
			CL.clSetKernelArg(kernel, 4, Sizeof.cl_int, Pointer.to(new int[] { 0 }));
			CL.clSetKernelArg(kernel, 5, Sizeof.cl_int, Pointer.to(new int[] { 3 }));
			CL.clSetKernelArg(kernel, 6, Sizeof.cl_int, Pointer.to(new int[] { 1 }));
			CL.clSetKernelArg(kernel, 7, Sizeof.cl_int, Pointer.to(new int[] { 3 }));

			long gws[] = new long[] { 1, 1 };

			CL.clEnqueueNDRangeKernel(Hardware.getLocalHardware().getQueue(), kernel, 1, null,
									gws, null, 0, null, null);

			return (T) new Scalar(res[0]);
		} else {
			CL.clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(((MemWrapper) args[0]).getMem()));
			CL.clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(((MemWrapper) args[1]).getMem()));
			CL.clSetKernelArg(kernel, 2, Sizeof.cl_int, Pointer.to(new int[]{0}));
			CL.clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{0}));

			long gws[] = new long[] { 3 };

			CL.clEnqueueNDRangeKernel(Hardware.getLocalHardware().getQueue(), kernel, 1, null,
									gws, null, 0, null, null);

			return (T) args[0];
		}
	}

	@Override
	public void compact() { }
}
