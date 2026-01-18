/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.jni;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.HardwareDataContext;
import org.almostrealism.hardware.external.ExternalComputeContext;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@link HardwareDataContext} implementation for native JNI-based hardware acceleration on the CPU.
 *
 * <p>{@link NativeDataContext} is the default data context for Almost Realism's native execution backend.
 * It provides memory management and compute context orchestration for operations that compile to native
 * C code and execute via JNI:</p>
 * <ul>
 *   <li><strong>Memory management:</strong> Creates and manages {@link NativeMemoryProvider} instances</li>
 *   <li><strong>Compute coordination:</strong> Provides {@link NativeComputeContext} for kernel compilation</li>
 *   <li><strong>Precision control:</strong> Configures float/double precision for all operations</li>
 *   <li><strong>Optional delegation:</strong> Can delegate to alternate data contexts (e.g., for CL memory)</li>
 * </ul>
 *
 * <h2>Initialization Pattern</h2>
 *
 * <p>The data context uses lazy initialization via {@link #init()}:</p>
 * <pre>{@code
 * // Create context (not yet initialized)
 * NativeDataContext dataContext = new NativeDataContext(
 *     "native-cpu",
 *     Precision.FP64,
 *     1024L * 1024 * 1024  // 1GB max reservation
 * );
 *
 * // Initialize (creates compiler and memory provider)
 * dataContext.init();
 *
 * // Now ready for use
 * ComputeContext computeContext = dataContext.getComputeContexts().get(0);
 * }</pre>
 *
 * <h2>Memory Management</h2>
 *
 * <p>By default, creates a {@link NativeMemoryProvider} with the specified max reservation:</p>
 * <pre>{@code
 * // Default: uses NativeMemoryProvider
 * NativeDataContext dc = new NativeDataContext("ctx", Precision.FP64, maxBytes);
 * dc.init();
 * MemoryProvider provider = dc.getMemoryProvider();  // NativeMemoryProvider
 *
 * // Custom: inject a different memory provider
 * MetalMemoryProvider metalProvider = new MetalMemoryProvider(...);
 * dc.setMemoryProvider(metalProvider);
 * dc.init();  // Will use metalProvider instead
 * }</pre>
 *
 * <h2>Execution Modes</h2>
 *
 * <p>Supports two execution modes, controlled by {@code AR_HARDWARE_NATIVE_EXECUTION}:</p>
 *
 * <h3>1. Native JNI Mode (default)</h3>
 * <pre>
 * AR_HARDWARE_NATIVE_EXECUTION=native (or unset)
 * Creates: NativeComputeContext
 * Execution: Compiled C code loaded via JNI and executed in-process
 * </pre>
 *
 * <h3>2. External Process Mode</h3>
 * <pre>
 * AR_HARDWARE_NATIVE_EXECUTION=external
 * Creates: ExternalComputeContext
 * Execution: Compiled C code launched as separate process, IPC via files
 * </pre>
 *
 * <p>Example:</p>
 * <pre>{@code
 * // Set environment variable before creating context
 * System.setProperty("AR_HARDWARE_NATIVE_EXECUTION", "external");
 *
 * NativeDataContext dc = new NativeDataContext("external-ctx", Precision.FP32, maxBytes);
 * dc.init();
 * // Uses ExternalComputeContext instead of NativeComputeContext
 * }</pre>
 *
 * <h2>OpenCL Memory Compatibility</h2>
 *
 * <p>The {@code clMemory} flag enables compatibility with OpenCL memory buffers:</p>
 * <pre>{@code
 * NativeDataContext dc = new NativeDataContext(
 *     "cl-compatible",
 *     Precision.FP32,
 *     maxBytes,
 *     true  // clMemory = true
 * );
 * // NativeCompiler will generate CL-compatible memory access patterns
 * }</pre>
 *
 * <h2>Delegation Pattern</h2>
 *
 * <p>Can delegate {@link #getMemoryProvider(int)} calls to another data context:</p>
 * <pre>{@code
 * NativeDataContext primary = new NativeDataContext(...);
 * OpenCLDataContext clContext = new OpenCLDataContext(...);
 *
 * // Delegate memory provider selection to CL context
 * primary.setDelegate(clContext);
 *
 * // Now getMemoryProvider(size) uses clContext's logic
 * MemoryProvider provider = primary.getMemoryProvider(1024);
 * }</pre>
 *
 * <h2>Integration with Hardware</h2>
 *
 * <p>When {@code AR_HARDWARE_DRIVER=native}, {@link Hardware} creates this data context:</p>
 * <pre>{@code
 * // In Hardware.init():
 * NativeDataContext dataContext = new NativeDataContext(
 *     "native",
 *     Precision.FP64,
 *     HardwareOperator.getMaximumReservation()
 * );
 * dataContext.init();
 * }</pre>
 *
 * <h2>Precision Configuration</h2>
 *
 * <p>The precision affects:</p>
 * <ul>
 *   <li><strong>Code generation:</strong> C types (float vs double)</li>
 *   <li><strong>Memory layout:</strong> Bytes per element (4 vs 8)</li>
 *   <li><strong>Max reservation:</strong> Total bytes = maxElements * precision.bytes()</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #destroy()} cleans up the memory provider (if not externally provided) and
 * should be called when shutting down:</p>
 * <pre>{@code
 * NativeDataContext dc = new NativeDataContext(...);
 * dc.init();
 * try {
 *     // Use data context
 * } finally {
 *     dc.destroy();  // Frees memory provider
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Safe for concurrent use after initialization. Multiple threads can request compute contexts
 * and memory providers without additional synchronization.</p>
 *
 * @see HardwareDataContext
 * @see NativeComputeContext
 * @see NativeMemoryProvider
 * @see NativeCompiler
 */
public class NativeDataContext extends HardwareDataContext {
	private static boolean external = SystemUtils.getProperty("AR_HARDWARE_NATIVE_EXECUTION", "").equalsIgnoreCase("external");

	private final boolean isExternal, isClMemory;

	private NativeCompiler compiler;
	private DataContext<MemoryData> delegate;
	private MemoryProvider<? extends Memory> ram;
	private boolean providedRam = false;

	private Precision precision;
	private ComputeContext<MemoryData> context;

	public NativeDataContext(String name, Precision precision, long maxReservation) {
		this(name, precision, maxReservation, false);
	}

	public NativeDataContext(String name, Precision precision, long maxReservation, boolean clMemory) {
		super(name, maxReservation);
		this.precision = precision;
		this.isExternal = external;
		this.isClMemory = clMemory;
	}

	@Override
	public void init() {
		if (context != null) return;
		compiler = NativeCompiler.factory(getPrecision(), isClMemory).construct();

		if (ram == null) {
			ram = new NativeMemoryProvider(compiler, getMaxReservation() * getPrecision().bytes());
		}

		context = isExternal ? new ExternalComputeContext(this, compiler) : new NativeComputeContext(this, compiler);
	}

	@Override
	public Precision getPrecision() { return precision; }

	public NativeCompiler getNativeCompiler() { return compiler; }

	public void setDelegate(DataContext<MemoryData> ctx) {
		this.delegate = ctx;
	}

	public void setMemoryProvider(MemoryProvider<? extends Memory> ram) {
		if (getPrecision().bytes() != ram.getNumberSize()) {
			throw new UnsupportedOperationException();
		}

		this.ram = ram;
		providedRam = true;
	}

	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(ram);
	}

	public MemoryProvider<? extends Memory> getMemoryProvider() { return ram; }

	@Override
	public MemoryProvider<? extends Memory> getMemoryProvider(int size) {
		return delegate == null ? getMemoryProvider() : delegate.getMemoryProvider(size);
	}

	@Override
	public MemoryProvider<? extends Memory> getKernelMemoryProvider() { return getMemoryProvider(); }

	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		if (context == null) {
			if (Hardware.enableVerbose) System.out.println("INFO: No explicit ComputeContext for " + Thread.currentThread().getName());
			context = new NativeComputeContext(this, getNativeCompiler());
		}

		return List.of(context);
	}

	@Override
	public <T> T deviceMemory(Callable<T> exec) {
		try {
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		try {
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void destroy() {
		// TODO  Destroy all compute contexts
		if (!providedRam) ram.destroy();
	}
}
