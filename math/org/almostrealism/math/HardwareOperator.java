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

/**
 * {@link HardwareOperator}s are intended to be used with {@link ThreadLocal}.
 *
 * @param <T>  Return type. If the scalar flag is true, this must be a subclass of {@link Scalar}.
 */
public class HardwareOperator<T extends MemWrapper> implements Operator<T>, Factory<cl_kernel> {
	private cl_program prog;
	private String name;

	private boolean isKernel;
	private int argCount;

	private cl_kernel kernel;

	public HardwareOperator(cl_program program, String name) {
		this(program, name, 2);
	}

	public HardwareOperator(cl_program program, String name, int argCount) {
		this(program, name, true, argCount);
	}

	public HardwareOperator(cl_program program, String name, boolean kernel, int argCount) {
		this.prog = program;
		this.name = name;
		this.isKernel = kernel;
		this.argCount = argCount;
	}

	// TODO  How do these kernels get released when done?
	@Override
	public cl_kernel construct() { return CL.clCreateKernel(prog, name, null); }

	@Override
	public Scope<? extends Variable> getScope(String prefix) {
		return null;
	}

	/**
	 * Values returned from this method may not be valid if this method is called again
	 * before the value is used. An easy way around this problem is to always use the
	 * {@link HardwareOperator} with a {@link ThreadLocal}.
	 */
	@Override
	public synchronized T evaluate(Object[] args) {
		if (kernel == null) kernel = construct();

		int index = 0;

//		TODO  Remove legacy custom code for dealing with scalars, which are really just Pairs (and so are already MemWrapper implementors just like Vectors)
//		if (scalar) {
//			try {
//				CL.clSetKernelArg(kernel, index, Sizeof.cl_double, Pointer.to(result.getMem())); // Result
//				index++;
//
//				for (int i = 0; i < argCount; i++) {
//					CL.clSetKernelArg(kernel, index, Sizeof.cl_mem, Pointer.to(((MemWrapper) args[i]).getMem()));
//					index++;
//				}
//
//				for (int i = 0; i < argCount; i++) {
//					CL.clSetKernelArg(kernel, index, Sizeof.cl_int, Pointer.to(new int[]{0})); // Offset
//					index++;
//				}
//			} catch (CLException e) {
//				throw new RuntimeException(e.getMessage() + " index = " + index + " argCount = " + argCount);
//			}
//
//			long gws[] = new long[] { 1, 1 };
//
//			CL.clEnqueueNDRangeKernel(Hardware.getLocalHardware().getQueue(), kernel, 1, null,
//									gws, null, 0, null, null);
//
//			return (T) result;
//		} else {
		try {
			for (int i = 0; i < argCount; i++) {
				CL.clSetKernelArg(kernel, index++, Sizeof.cl_mem, Pointer.to(((MemWrapper) args[i]).getMem()));
			}

			for (int i = 0; i < argCount; i++) {
				CL.clSetKernelArg(kernel, index++, Sizeof.cl_int, Pointer.to(new int[]{0})); // Offset
			}

			long gws[] = isKernel ? new long[]{3} : new long[]{1};

			CL.clEnqueueNDRangeKernel(Hardware.getLocalHardware().getQueue(), kernel, 1, null,
					gws, null, 0, null, null);

			return (T) args[0];
		} catch (CLException e) {
			throw new RuntimeException(e.getMessage() + " for function " + name +
							" (index = " + index + " argCount = " + argCount + ")", e);
		}
//		}
	}

	@Override
	public void compact() { }
}
