/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;
import io.almostrealism.lang.ScopeEncoder;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jni.NativeInstructionSet;

/**
 * {@link io.almostrealism.code.ComputeContext} that compiles to native C code with OpenCL memory operations.
 *
 * <p>Generates JNI C code that uses OpenCL APIs for memory transfers (clEnqueueReadBuffer, clEnqueueWriteBuffer)
 * while executing computation logic in compiled native code. Provides CPU execution with OpenCL memory.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * NativeCompiler compiler = NativeCompiler.factory(Precision.FP32, true).construct();
 * CLNativeComputeContext context = new CLNativeComputeContext(dataContext, compiler);
 *
 * // Compile scope to native code
 * Scope<T> scope = computation.getScope(context);
 * NativeInstructionSet instructions = (NativeInstructionSet) context.deliver(scope);
 * }</pre>
 *
 * <h2>Generated Code</h2>
 *
 * <p>Uses {@link CLJNIPrintWriter} to generate JNI code with OpenCL memory operations:</p>
 *
 * <pre>{@code
 * // Generated JNI function:
 * JNIEXPORT void JNICALL Java_..._execute(...) {
 *     // Read from OpenCL memory
 *     clEnqueueReadBuffer(queue, clMem, ...);
 *
 *     // Execute native computation
 *     compute(data, ...);
 *
 *     // Write back to OpenCL memory
 *     clEnqueueWriteBuffer(queue, clMem, ...);
 * }
 * }</pre>
 *
 * @see CLJNIPrintWriter
 * @see CLJNILanguageOperations
 * @see NativeCompiler
 */
public class CLNativeComputeContext extends AbstractComputeContext {
	/** The native compiler for C code compilation and JNI library generation. */
	private NativeCompiler compiler;

	/**
	 * Creates a new native compute context that compiles to C code with OpenCL memory operations.
	 *
	 * @param dc        the data context providing memory management and precision settings
	 * @param compiler  the native compiler for C code compilation and JNI library generation
	 */
	public CLNativeComputeContext(CLDataContext dc, NativeCompiler compiler) {
		super(dc);
		this.compiler = compiler;
	}

	/**
	 * Returns the language operations for OpenCL JNI code generation.
	 *
	 * @return a {@link CLJNILanguageOperations} configured with the data context precision
	 */
	@Override
	public LanguageOperations getLanguage() {
		return new CLJNILanguageOperations(getDataContext().getPrecision());
	}

	/**
	 * Returns the native compiler used for C code compilation.
	 *
	 * @return the native compiler
	 */
	public NativeCompiler getNativeCompiler() {
		return compiler;
	}

	/**
	 * Compiles a scope to native C code with OpenCL memory operations.
	 * Uses {@link CLJNIPrintWriter} to generate JNI code that interfaces with OpenCL
	 * memory buffers for data transfer.
	 *
	 * @param scope  the scope containing the computation graph to compile
	 * @return a {@link NativeInstructionSet} containing the compiled native library
	 */
	@Override
	public InstructionSet deliver(Scope scope) {
		NativeInstructionSet target = getNativeCompiler().reserveLibraryTarget();
		target.setComputeContext(this);
		target.setMetadata(scope.getMetadata().withContextName(getDataContext().getName()));
		target.setParallelism(KernelPreferences.getCpuParallelism());

		long start = System.nanoTime();
		StringBuffer buf = new StringBuffer();

		try {
			buf.append(new ScopeEncoder(pw ->
					new CLJNIPrintWriter(pw, target.getFunctionName(), target.getParallelism(),
							getLanguage()), Accessibility.EXTERNAL).apply(scope));
			getNativeCompiler().compile(target, buf.toString());
			return target;
		} finally {
			recordCompilation(scope, buf::toString, System.nanoTime() - start);
		}
	}

	/**
	 * Returns {@code true} since this context always executes on the CPU.
	 *
	 * @return always {@code true}
	 */
	@Override
	public boolean isCPU() { return true; }

	/**
	 * Releases resources held by this context. Currently a no-op as resources
	 * are managed by the native compiler and data context.
	 */
	@Override
	public void destroy() { }
}
