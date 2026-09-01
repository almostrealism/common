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

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.HardwareException;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.cl_program;

/**
 * Wrapper for OpenCL {@link cl_program} with compilation and metadata management.
 *
 * <p>{@link CLProgram} represents a compiled OpenCL program created from source code,
 * providing access to the {@link cl_program} object and associated metadata.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * String source = "__kernel void add(...) { ... }";
 * CLProgram prog = CLProgram.create(context, metadata, source);
 *
 * // Compile the program
 * prog.compile();
 *
 * // Access compiled program
 * cl_program clProg = prog.getProgram();
 *
 * // Create kernels from program
 * cl_kernel kernel = CL.clCreateKernel(clProg, "add", null);
 * }</pre>
 *
 * <h2>Compilation Error Handling</h2>
 *
 * <pre>{@code
 * try {
 *     prog.compile();
 * } catch (HardwareException e) {
 *     // Exception includes full source code for debugging
 *     System.out.println(e.getSource());
 * }
 * }</pre>
 *
 * @see CLOperatorMap
 * @see CLComputeContext
 */
public class CLProgram implements OperationInfo {
	/**
	 * Stack size, in bytes, of the thread {@link #compile()} builds on.
	 *
	 * <p>Measured against a 986,390 character kernel, which overflows the 1MB
	 * default and builds successfully at 2MB. The value here is far above that
	 * because the depth an OpenCL implementation reaches is a property of the
	 * implementation rather than something this class can predict, and because
	 * the cost of the margin is only reserved address space: a thread stack is
	 * committed as it is used, and one program is built per thread.</p>
	 */
	private static final long COMPILATION_STACK_SIZE = 64 * 1024 * 1024;

	/** The compute context this program belongs to. */
	private CLComputeContext ctx;

	/** The underlying OpenCL program object. */
	private cl_program prog;

	/** Metadata describing this operation. */
	private final OperationMetadata metadata;

	/** The OpenCL source code for this program. */
	private final String src;

	/**
	 * Constructs a new CLProgram with the given context, OpenCL program, metadata, and source.
	 *
	 * @param ctx      the compute context this program belongs to
	 * @param prog     the underlying OpenCL program object
	 * @param metadata the operation metadata (may be null)
	 * @param src      the OpenCL source code
	 */
	private CLProgram(CLComputeContext ctx, cl_program prog, OperationMetadata metadata, String src) {
		this.ctx = ctx;
		this.prog = prog;
		this.metadata = (metadata == null ?
				new OperationMetadata((String) null, null) : metadata)
					.withContextName(ctx.getDataContext().getName());
		this.src = src;
	}

	/**
	 * Returns the underlying OpenCL program object.
	 *
	 * @return the OpenCL program
	 */
	public cl_program getProgram() {
		return prog;
	}

	/** Returns the operation metadata for this program. */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Returns the OpenCL source code for this program.
	 *
	 * @return the OpenCL source code
	 */
	public String getSource() {
		return src;
	}

	/**
	 * Compiles the OpenCL program.
	 *
	 * <p>The build runs on a thread of this class's own making rather than on the
	 * caller's, because {@link CL#clBuildProgram} compiles in-process and some
	 * implementations recurse in proportion to the size of the source. A generated
	 * kernel approaching a megabyte of OpenCL C exhausts the 1MB stack that a JVM
	 * on Linux gives a thread by default, and the resulting overflow lands in native
	 * frames, so it arrives as a bare segmentation fault with no {@code hs_err} file
	 * and no indication that OpenCL was involved. Owning the thread makes the stack
	 * available to the compiler a property of this class instead of a property of
	 * whichever {@code -Xss} the surrounding process happens to run with.</p>
	 *
	 * @throws HardwareException if compilation fails, with the source code attached for debugging
	 */
	public void compile() {
		Throwable[] failure = new Throwable[1];

		Thread compilation = new Thread(null, () -> {
			try {
				build();
			} catch (Throwable t) {
				failure[0] = t;
			}
		}, "CLProgram build " + getMetadata().getDisplayName(), COMPILATION_STACK_SIZE);

		compilation.start();

		try {
			compilation.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HardwareException("Interrupted while building CLProgram", e);
		}

		if (failure[0] instanceof RuntimeException) {
			throw (RuntimeException) failure[0];
		} else if (failure[0] instanceof Error) {
			throw (Error) failure[0];
		} else if (failure[0] != null) {
			throw new HardwareException("Error building CLProgram", failure[0]);
		}
	}

	/**
	 * Performs the OpenCL build, translating a failure into a {@link HardwareException}.
	 *
	 * @throws HardwareException if compilation fails, with the source code attached for debugging
	 */
	private void build() {
		try {
			int r = CL.clBuildProgram(getProgram(), 0, null, null, null, null);
			if (r != 0) throw new HardwareException("Error building CLProgram: " + r);
		} catch (CLException e) {
			throw CLExceptionProcessor.process(e, ctx, "Error building CLProgram", src);
		}
	}

	/**
	 * Releases the OpenCL program resources.
	 * After calling this method, the program object should not be used.
	 */
	public void destroy() {
		CL.clReleaseProgram(prog);
		prog = null;
	}

	/**
	 * Returns a human-readable description of this program.
	 *
	 * @return the display name from the operation metadata
	 */
	@Override
	public String describe() {
		return getMetadata().getDisplayName();
	}

	/**
	 * Creates a new CLProgram from OpenCL source code.
	 *
	 * @param ctx      the compute context to create the program in
	 * @param metadata the operation metadata (may be null)
	 * @param src      the OpenCL source code
	 * @return a new uncompiled CLProgram
	 * @throws HardwareException if program creation fails
	 */
	public static CLProgram create(CLComputeContext ctx, OperationMetadata metadata, String src) {
		int[] result = new int[1];
		cl_program prog = CL.clCreateProgramWithSource(ctx.getCLContext(), 1, new String[] { src }, null, result);
		if (result[0] != 0) throw new HardwareException("Error creating HardwareOperatorMap: " + result[0]);

		return new CLProgram(ctx, prog, metadata, src);
	}
}
