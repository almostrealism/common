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

package org.almostrealism.hardware;

import org.almostrealism.util.Factory;
import org.almostrealism.util.Producer;
import org.jocl.*;

import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link HardwareOperator}s are intended to be used with {@link ThreadLocal}.
 *
 * @param <T> Return type
 */
public class HardwareOperator<T extends MemWrapper> implements Consumer<Object[]>, Factory<cl_kernel> {
	private static Pointer zero = Pointer.to(new int[]{0});

	private cl_program prog;
	private String name;

	private int argCount;

	private long globalWorkSize = 1;
	private long globalWorkOffset;

	private cl_kernel kernel;

	private BiFunction<String, CLException, HardwareException> exceptionProcessor;

	public HardwareOperator(cl_program program, String name, int argCount,
							BiFunction<String, CLException, HardwareException> exceptionProcessor) {
		this.prog = program;
		this.name = name;
		this.argCount = argCount;
		this.exceptionProcessor = exceptionProcessor;
	}

	// TODO  How do these kernels get released when done?
	@Override
	public cl_kernel construct() {
		try {
			return CL.clCreateKernel(prog, name, null);
		} catch (CLException e) {
			throw exceptionProcessor == null ? e : exceptionProcessor.apply(name, e);
		}
	}

	public long getGlobalWorkSize() { return globalWorkSize; }

	public void setGlobalWorkSize(long globalWorkSize) { this.globalWorkSize = globalWorkSize; }

	public long getGlobalWorkOffset() { return globalWorkOffset; }

	public void setGlobalWorkOffset(long globalWorkOffset) { this.globalWorkOffset = globalWorkOffset; }

	/**
	 * Values returned from this method may not be valid if this method is called again
	 * before the value is used. An easy way around this problem is to always use the
	 * {@link HardwareOperator} with a {@link ThreadLocal}.
	 */
	@Override
	public synchronized void accept(Object[] args) {
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
				if (args[i] == null) {
					throw new NullPointerException("argument " + i + " to function " + name);
				}

				if (args[i] instanceof MemWrapper == false) {
					throw new IllegalArgumentException("argument " + i + " to function " + name + " is not a MemWrapper");
				}

				CL.clSetKernelArg(kernel, index++, Sizeof.cl_mem, Pointer.to(((MemWrapper) args[i]).getMem()));
			}

			for (int i = 0; i < argCount; i++) {
				CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
						Pointer.to(new int[] { ((MemWrapper) args[i]).getOffset() })); // Offset
			}

			for (int i = 0; i < argCount; i++) {
				CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
						Pointer.to(new int[] { ((MemWrapper) args[i]).getAtomicMemLength() })); // Size
			}

			CL.clEnqueueNDRangeKernel(Hardware.getLocalHardware().getQueue(), kernel, 1,
					new long[] { globalWorkOffset }, new long[] { globalWorkSize },
					null, 0, null, null);
		} catch (CLException e) {
			// TODO  This should use the exception processor also, but theres no way to pass the message details
			throw new HardwareException(e.getMessage() + " for function " + name +
							" (index = " + index + " argCount = " + argCount + ")", e);
		}
//		}
	}
}
