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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.ScopeEncoder;
import io.almostrealism.scope.Scope;
import org.almostrealism.c.CJNILanguageOperations;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.metal.MetalJNIMemoryAccessor;
import org.almostrealism.hardware.metal.MetalMemoryProvider;

/**
 * {@link AbstractComputeContext} implementation that compiles computations to native C code and executes
 * them via JNI on the CPU.
 *
 * <p>{@link NativeComputeContext} is the default hardware acceleration backend for Almost Realism. It provides
 * high-performance CPU execution by:
 * <ul>
 *   <li><strong>Generating C code:</strong> Converts {@link Scope} objects to optimized C implementations</li>
 *   <li><strong>Runtime compilation:</strong> Compiles C code to native shared libraries (.so/.dylib)</li>
 *   <li><strong>JNI invocation:</strong> Loads and calls compiled functions via Java Native Interface</li>
 *   <li><strong>Memory integration:</strong> Supports both standard RAM and Metal unified memory</li>
 * </ul>
 *
 * <h2>Compilation Pipeline</h2>
 *
 * <p>When a {@link Scope} is delivered for execution, the following steps occur:</p>
 * <pre>
 * 1. Reserve library target        (NativeInstructionSet)
 * 2. Encode Scope to C code        (CJNIPrintWriter, ScopeEncoder)
 * 3. Compile C to shared library   (NativeCompiler)
 * 4. Load library via JNI          (NativeInstructionSet.load())
 * 5. Execute via native call       (NativeInstructionSet.execute())
 * </pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create native compute context
 * NativeDataContext dataContext = new NativeDataContext();
 * NativeCompiler compiler = new NativeCompiler();
 * NativeComputeContext computeContext = new NativeComputeContext(dataContext, compiler);
 *
 * // Deliver a computation scope
 * Scope<PackedCollection> scope = computation.getScope();
 * InstructionSet instructions = computeContext.deliver(scope);
 *
 * // Execute compiled code
 * instructions.get().run();
 * }</pre>
 *
 * <h2>Memory Accessor Selection</h2>
 *
 * <p>The context automatically selects the appropriate {@link JNIMemoryAccessor} based on
 * the data context's memory provider:</p>
 * <ul>
 *   <li><strong>MetalMemoryProvider:</strong> Uses {@link MetalJNIMemoryAccessor} for unified memory access</li>
 *   <li><strong>Standard RAM:</strong> Uses {@link DefaultJNIMemoryAccessor} for heap memory access</li>
 * </ul>
 *
 * <h2>Language Operations</h2>
 *
 * <p>Uses {@link CJNILanguageOperations} to generate C code with JNI-compatible function signatures
 * and memory access patterns. The precision (float/double) is determined by the data context.</p>
 *
 * <h2>Parallelism</h2>
 *
 * <p>Native instructions are configured with CPU parallelism from {@link KernelPreferences#getCpuParallelism()}.
 * This enables multi-threaded execution of kernels across CPU cores.</p>
 *
 * <h2>Compilation Tracking</h2>
 *
 * <p>Compilation times and generated code are recorded via {@link AbstractComputeContext#recordCompilation}
 * for profiling and debugging. Enable {@link #enableVerbose} for detailed logging.</p>
 *
 * <h2>Integration with Hardware.getLocalHardware()</h2>
 *
 * <p>When {@code AR_HARDWARE_DRIVER=native}, {@link org.almostrealism.hardware.Hardware} creates
 * this compute context as the default backend:</p>
 * <pre>{@code
 * // In Hardware initialization:
 * NativeComputer computer = new NativeComputer();
 * computer.setComputeContext(new NativeComputeContext(...));
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #destroy()} cleans up the {@link NativeCompiler} and any temporary compilation artifacts.
 * Always call destroy() when shutting down to free native resources.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The context itself is thread-safe for delivering scopes. However, the underlying
 * {@link NativeCompiler} may serialize compilation if multiple threads request it concurrently.</p>
 *
 * @see AbstractComputeContext
 * @see NativeDataContext
 * @see NativeCompiler
 * @see NativeInstructionSet
 * @see CJNIPrintWriter
 */
public class NativeComputeContext extends AbstractComputeContext<NativeDataContext> {
	public static boolean enableVerbose = false;
	protected static long totalInvocations = 0;

	private NativeCompiler compiler;

	public NativeComputeContext(NativeDataContext dc, NativeCompiler compiler) {
		super(dc);
		this.compiler = compiler;
	}

	@Override
	public LanguageOperations getLanguage() {
		return new CJNILanguageOperations(getDataContext().getPrecision());
	}

	public NativeCompiler getNativeCompiler() { return compiler; }

	@Override
	public InstructionSet deliver(Scope scope) {
		NativeInstructionSet target = getNativeCompiler().reserveLibraryTarget();
		target.setComputeContext(this);
		target.setMetadata(scope.getMetadata().withContextName(getDataContext().getName()));
		target.setParallelism(KernelPreferences.getCpuParallelism());

		JNIMemoryAccessor accessor;

		if (getDataContext().getMemoryProvider() instanceof MetalMemoryProvider) {
			accessor = new MetalJNIMemoryAccessor();
		} else {
			accessor = new DefaultJNIMemoryAccessor();
		}

		long start = System.nanoTime();
		StringBuffer buf = new StringBuffer();

		try {
			buf.append(new ScopeEncoder(pw ->
					new CJNIPrintWriter(pw, target.getFunctionName(), target.getParallelism(),
							getLanguage(), accessor), Accessibility.EXTERNAL).apply(scope));

			getNativeCompiler().compile(target, buf.toString());
			return target;
		} finally {
			recordCompilation(scope, buf::toString, System.nanoTime() - start);
		}
	}

	@Override
	public boolean isCPU() { return true; }

	@Override
	public void destroy() {
		if (compiler != null) {
			compiler.destroy();
			compiler = null;
		}
	}
}
