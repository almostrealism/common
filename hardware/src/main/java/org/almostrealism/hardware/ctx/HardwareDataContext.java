/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.DataContext;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.concurrent.Callable;
import java.util.function.IntFunction;

/**
 * Base class for hardware-specific data contexts that manage memory providers and shared memory.
 *
 * <p>{@link HardwareDataContext} provides the foundation for backend-specific implementations
 * (OpenCL, Metal, JNI) by managing memory provider selection and shared memory allocation. It
 * supports thread-local memory provider customization and temporary shared memory scopes.</p>
 *
 * <h2>Memory Provider Management</h2>
 *
 * <p>Memory providers can be set on a per-thread basis via the thread-local {@code memoryProvider}
 * field. This allows different threads to use different memory providers for the same context:</p>
 * <pre>{@code
 * // Thread 1: Use GPU memory
 * HardwareDataContext.memoryProvider.set(size -> gpuMemoryProvider);
 * MemoryData gpuData = context.allocate(1024);  // Allocated on GPU
 *
 * // Thread 2: Use CPU memory
 * HardwareDataContext.memoryProvider.set(size -> cpuMemoryProvider);
 * MemoryData cpuData = context.allocate(1024);  // Allocated on CPU
 * }</pre>
 *
 * <h2>Shared Memory Scopes</h2>
 *
 * <p>The {@link #sharedMemory} method enables temporary use of shared memory (typically native
 * memory accessible by all backends) for operations that need cross-backend data transfer:</p>
 * <pre>{@code
 * // Temporarily allocate in shared memory
 * MemoryData sharedData = context.sharedMemory(
 *     size -> "shared-buffer-" + size,
 *     () -> {
 *         // Allocations within this scope use shared memory provider
 *         return context.allocate(1024);
 *     }
 * );
 *
 * // Outside scope: back to context's default provider
 * MemoryData normalData = context.allocate(1024);
 * }</pre>
 *
 * <h2>Subclass Responsibilities</h2>
 *
 * <p>Concrete implementations must provide:</p>
 * <ul>
 *   <li><b>Backend-specific allocation</b>: Override {@code allocate()} methods for the specific
 *       hardware backend (OpenCL buffers, Metal buffers, native memory, etc.)</li>
 *   <li><b>Kernel memory provider</b>: Implement {@code getKernelMemoryProvider()} to return the
 *       provider used for kernel arguments</li>
 *   <li><b>Shared memory provider</b>: Optionally override {@link #getSharedMemoryProvider()} to
 *       customize cross-backend memory</li>
 * </ul>
 *
 * <h2>Integration with Hardware</h2>
 *
 * <p>{@link HardwareDataContext} uses {@link Hardware#getNativeBufferMemoryProvider()} as the
 * default shared memory provider, enabling data transfer between different backend implementations.</p>
 *
 * @see AbstractComputeContext
 * @see Hardware
 * @see HardwareMemoryProvider
 */
public abstract class HardwareDataContext implements DataContext<MemoryData>, ConsoleFeatures {
	private final String name;
	private final long maxReservation;

	private MemoryProvider<? extends RAM> sharedRam;

	/**
	 * Thread-local memory provider supplier for customizing allocation behavior per-thread.
	 *
	 * <p>When set, the function is called with the requested size and should return a
	 * {@link MemoryProvider} to use for allocations on the current thread.</p>
	 */
	protected static ThreadLocal<IntFunction<MemoryProvider<?>>> memoryProvider;

	static {
		memoryProvider = new ThreadLocal<>();
	}

	/**
	 * Constructs a hardware data context with the given name and maximum reservation size.
	 *
	 * @param name Human-readable name for this context (e.g., "OpenCL", "Metal", "JNI")
	 * @param maxReservation Maximum memory reservation size in bytes (for planning/limits)
	 */
	public HardwareDataContext(String name, long maxReservation) {
		this.name = name;
		this.maxReservation = maxReservation;
	}

	/**
	 * Returns the human-readable name of this data context.
	 *
	 * @return Context name (e.g., "OpenCL", "Metal", "JNI")
	 */
	@Override
	public String getName() {
		return name;
	}

	/**
	 * Returns the maximum memory reservation size for this context.
	 *
	 * <p>This value is used for planning and resource management, not enforced as a hard limit.</p>
	 *
	 * @return Maximum reservation in bytes
	 */
	public long getMaxReservation() { return maxReservation; }

	/**
	 * Returns the thread-local memory provider supplier, if set.
	 *
	 * @return Memory provider supplier for the current thread, or null if not set
	 */
	protected IntFunction<MemoryProvider<?>> getMemoryProviderSupply() {
		return memoryProvider.get();
	}

	/**
	 * Returns the memory provider to use for shared memory operations.
	 *
	 * <p>By default, returns {@link Hardware#getNativeBufferMemoryProvider()} which provides
	 * native memory accessible by all backends. Subclasses may override to customize shared
	 * memory behavior.</p>
	 *
	 * @return Memory provider for shared memory
	 */
	protected MemoryProvider getSharedMemoryProvider() {
		return Hardware.getLocalHardware().getNativeBufferMemoryProvider();
	}

	/**
	 * Executes the given operation within a shared memory scope.
	 *
	 * <p>Temporarily sets the thread-local memory provider to use shared memory (native memory),
	 * executes the operation, then restores the original provider. All allocations within the
	 * operation use shared memory.</p>
	 *
	 * <p>This is useful for cross-backend data transfer or when data must be accessible from
	 * multiple hardware contexts.</p>
	 *
	 * <pre>{@code
	 * // Transfer data from GPU to CPU via shared memory
	 * MemoryData sharedBuffer = context.sharedMemory(
	 *     size -> "transfer-buffer",
	 *     () -> {
	 *         MemoryData buffer = context.allocate(1024);
	 *         copyFromGPU(gpuData, buffer);
	 *         return buffer;
	 *     }
	 * );
	 * copyToCPU(sharedBuffer, cpuData);
	 * }</pre>
	 *
	 * @param name Name supplier for the shared memory allocation
	 * @param exec Operation to execute within shared memory scope
	 * @param <T> Return type of the operation
	 * @return Result of executing the operation
	 * @throws RuntimeException If the operation throws any exception
	 */
	@Override
	public <T> T sharedMemory(IntFunction<String> name, Callable<T> exec) {
		if (sharedRam == null) {
			sharedRam = getSharedMemoryProvider();
		}

		IntFunction<MemoryProvider<?>> currentProvider = memoryProvider.get();
		IntFunction<MemoryProvider<?>> nextProvider = s -> sharedRam;

		try {
			memoryProvider.set(nextProvider);
			return ((HardwareMemoryProvider<?>) sharedRam).sharedMemory(name, exec);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			memoryProvider.set(currentProvider);
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
