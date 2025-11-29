/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.external;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.ScopeEncoder;
import io.almostrealism.scope.Scope;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.almostrealism.hardware.jni.NativeInstructionSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * {@link io.almostrealism.code.ComputeContext} for external process execution via compiled standalone executables.
 *
 * <p>Experimental backend that compiles {@link Scope} to C code wrapped in a standalone executable.
 * Data is transferred to/from the process using file I/O via {@link LocalExternalMemoryProvider}.</p>
 *
 * <h2>Execution Model</h2>
 *
 * <ol>
 *   <li>Compile {@link Scope} to C code with {@code external-wrapper.c} template</li>
 *   <li>Produce standalone executable using {@link NativeCompiler}</li>
 *   <li>Launch executable as separate process, passing data directory path</li>
 *   <li>Process reads input data from binary files, writes results back</li>
 *   <li>Java reads results from files after process exits</li>
 * </ol>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Development:</strong> Debug compiled code in isolation without JNI complexity</li>
 *   <li><strong>Portability:</strong> Ship executables instead of JNI libraries</li>
 *   <li><strong>External Tools:</strong> Integrate with existing C toolchains</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 *   <li><strong>Performance:</strong> File I/O overhead (10-100x slower than JNI)</li>
 *   <li><strong>Process Overhead:</strong> Spawning new process per operation</li>
 *   <li><strong>No GPU:</strong> CPU-only execution</li>
 *   <li><strong>Experimental:</strong> Not recommended for production use</li>
 * </ul>
 *
 * @see ExternalInstructionSet
 * @see LocalExternalMemoryProvider
 * @see NativeCompiler
 */
public class ExternalComputeContext extends AbstractComputeContext {
	/** C wrapper template loaded from external-wrapper.c resource for file-based argument passing. */
	private static final String externalWrapper;

	static {
		StringBuffer buf = new StringBuffer();

		try (BufferedReader in =
					 new BufferedReader(new InputStreamReader(
							 ExternalComputeContext.class.getClassLoader().getResourceAsStream("external-wrapper.c")))) {
			String line;
			while ((line = in.readLine()) != null) {
				buf.append(line);
				buf.append("\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		externalWrapper = buf.toString();
	}

	/** The native compiler for generating standalone executables. */
	private NativeCompiler compiler;

	/**
	 * Creates an external compute context for process-based execution.
	 *
	 * @param dc Data context providing memory management
	 * @param compiler Native compiler for generating standalone executables
	 */
	public ExternalComputeContext(NativeDataContext dc, NativeCompiler compiler) {
		super(dc);
		this.compiler = compiler;
	}

	/**
	 * Returns the language operations for C code generation.
	 *
	 * @return C language operations configured for external accessibility
	 */
	@Override
	public LanguageOperations getLanguage() {
		return new CLanguageOperations(getDataContext().getPrecision(), true, false);
	}

	/**
	 * Returns the native compiler used to generate executable binaries.
	 *
	 * @return The {@link NativeCompiler} instance
	 */
	public NativeCompiler getNativeCompiler() { return compiler; }

	/**
	 * Compiles a {@link Scope} to a standalone executable wrapped with file I/O.
	 *
	 * <p>Generates C code from the scope, wraps it with the external-wrapper.c template
	 * for file-based argument passing, compiles to a native executable, and returns
	 * an {@link ExternalInstructionSet} that manages process execution.</p>
	 *
	 * @param scope The computation scope to compile
	 * @return Instruction set that executes the compiled external process
	 */
	@Override
	public InstructionSet deliver(Scope scope) {
		NativeInstructionSet inst = getNativeCompiler().reserveLibraryTarget();
		inst.setComputeContext(this);
		inst.setMetadata(scope.getMetadata().withContextName(getDataContext().getName()));

		long start = System.nanoTime();
		StringBuffer buf = new StringBuffer();

		try {
			buf.append(new ScopeEncoder(pw -> new CPrintWriter(pw, "apply", getLanguage().getPrecision(), true), Accessibility.EXTERNAL).apply(scope));
			buf.append("\n");
			buf.append(externalWrapper);
			String executable = getNativeCompiler().getLibraryDirectory() + "/" + getNativeCompiler().compile(inst.getClass().getName(), buf.toString(), false);
			return new ExternalInstructionSet(executable, getNativeCompiler()::reserveDataDirectory);
		} finally {
			recordCompilation(scope, buf::toString, System.nanoTime() - start);
		}
	}

	/**
	 * Returns {@code true} since external process execution is CPU-only.
	 *
	 * @return always {@code true}
	 */
	@Override
	public boolean isCPU() { return true; }

	/**
	 * Releases resources held by this compute context.
	 * Currently a no-op as executables are not cached.
	 */
	@Override
	public void destroy() { }
}
