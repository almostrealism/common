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

package org.almostrealism.c;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;

/**
 * {@link io.almostrealism.code.MemoryProvider} implementation for JNI-based native memory management.
 *
 * <p>{@link NativeMemoryProvider} allocates and manages native memory via JNI, supporting
 * CPU-based computation through malloc/free and direct memory access.</p>
 *
 * <h2>Memory Operations</h2>
 *
 * <p>All memory operations use JNI functions:</p>
 * <ul>
 *   <li>{@link Malloc}: Allocates native memory blocks</li>
 *   <li>{@link Free}: Deallocates native memory blocks</li>
 *   <li>{@link NativeRead}: Reads data from native memory to Java arrays</li>
 *   <li>{@link NativeWrite}: Writes data from Java arrays to native memory</li>
 * </ul>
 *
 * @see NativeMemory
 * @see NativeCompiler
 */
public class NativeMemoryProvider extends HardwareMemoryProvider<NativeMemory> {
	/**
	 * Enables logging of large memory allocations (greater than 20MB).
	 * Controlled by the {@code AR_HARDWARE_ALLOCATION_LOGGING} system property.
	 */
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);

	/** Timing metric tracking native memory I/O operations. */
	public static TimingMetric ioTime = Hardware.console.timing("nativeIO");

	/** Distribution metric tracking native memory allocation sizes in bytes. */
	public static DistributionMetric allocationSizes = Hardware.console.distribution("nativeAllocationSizes", 1024 * 1024);

	/** Distribution metric tracking native memory deallocation sizes in bytes. */
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("nativeDeallocationSizes", 1024 * 1024);

	/** The native compiler for JNI code generation. */
	private final NativeCompiler compiler;

	/** JNI wrapper for malloc operations. */
	private Malloc malloc;

	/** JNI wrapper for free operations. */
	private Free free;

	/** JNI wrapper for reading from native memory. */
	private NativeRead read;

	/** JNI wrapper for writing to native memory. */
	private NativeWrite write;

	/** JNI wrapper for viewing native memory as a direct buffer. */
	private NativeBufferView bufferView;

	/** The maximum total memory that can be allocated in bytes. */
	private final long memoryMax;

	/** The total amount of memory currently allocated in bytes. */
	private long memoryUsed;

	/**
	 * Creates a new native memory provider.
	 *
	 * @param compiler  the native compiler for JNI code generation
	 * @param memoryMax the maximum total memory that can be allocated in bytes
	 */
	public NativeMemoryProvider(NativeCompiler compiler, long memoryMax) {
		this.compiler = compiler;
		this.memoryMax = memoryMax;
	}

	/** {@inheritDoc} */
	@Override
	public String getName() { return "JNI"; }

	/** {@inheritDoc} */
	@Override
	public int getNumberSize() { return compiler.getPrecision().bytes(); }

	/** Returns the native compiler used by this provider. */
	public NativeCompiler getNativeCompiler() { return compiler; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Allocates native memory via JNI malloc.</p>
	 *
	 * @throws HardwareException if memory maximum would be exceeded
	 */
	@Override
	public synchronized NativeMemory allocate(int size) {
		if (enableLargeAllocationLogging && size > (20 * 1024 * 1024)) {
			log("Allocating " + (getNumberSize() * (long) size) / 1024 / 1024 + "mb");
		}

		if (malloc == null) malloc = new Malloc(getNativeCompiler());

		if (memoryUsed + (long) getNumberSize() * size > memoryMax) {
			throw new HardwareException("Memory max reached");
		} else {
			memoryUsed += (long) getNumberSize() * size;
			NativeMemory mem = allocated(new NativeMemory(this,
					malloc.apply(getNumberSize() * size),
					getNumberSize() * (long) size));
			allocationSizes.addEntry(getNumberSize() * (long) size);
			return mem;
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Deallocates native memory via JNI free.</p>
	 */
	@Override
	public synchronized void deallocate(NativeRef<NativeMemory> ref) {
		if (free == null) free = new Free(getNativeCompiler());

		free.apply(ref.getAddress());
		memoryUsed -= ref.getSize();
		deallocationSizes.addEntry(ref.getSize());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The returned view wraps the allocation directly via {@code NewDirectByteBuffer};
	 * no data is copied. It remains valid only while {@code mem} is allocated.</p>
	 *
	 * @throws IllegalArgumentException if the requested range extends beyond the allocation
	 */
	@Override
	public synchronized ByteBuffer getHostBuffer(NativeMemory mem, int offset, int length) {
		if (bufferView == null) bufferView = new NativeBufferView(getNativeCompiler());

		long bytes = getNumberSize();
		if (mem.getSize() < (offset + (long) length) * bytes) {
			throw new IllegalArgumentException("Attempt to view memory beyond the size of " + mem);
		}

		return bufferView.apply(mem.getContentPointer(), offset * bytes, length * bytes);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>When the source provider shares this provider's element size and supports bulk
	 * host transfers, the data is read directly into this allocation through a
	 * {@link #getHostBuffer} view, avoiding array mediation entirely. Otherwise the
	 * copy falls back to reading the source into a double array.</p>
	 */
	@Override
	public synchronized void setMem(NativeMemory mem, int offset, Memory source, int srcOffset, int length) {
		MemoryProvider provider = source.getProvider();

		if (provider != null && provider != this && provider.getNumberSize() == getNumberSize()) {
			try {
				ByteBuffer view = getHostBuffer(mem, offset, length);
				if (view != null && provider.getMem(source, srcOffset, view, length)) return;
			} finally {
				Reference.reachabilityFence(mem);
				Reference.reachabilityFence(source);
			}
		}

		double[] value = new double[length];
		source.getProvider().getMem(source, srcOffset, value, 0, length);
		setMem(mem, offset, value, 0, length);
	}

	/** {@inheritDoc} */
	@Override
	public synchronized void setMem(NativeMemory mem, int offset, double[] source, int srcOffset, int length) {
		if (write == null) write = new NativeWrite(getNativeCompiler());

		long start = System.nanoTime();
		try {
			write.apply(mem, offset, source, srcOffset, length);
		} finally {
			ioTime.addEntry("setMem", System.nanoTime() - start);
		}
	}

	/** {@inheritDoc} */
	@Override
	public synchronized void getMem(NativeMemory mem, int sOffset, double[] out, int oOffset, int length) {
		if (read == null) read = new NativeRead(getNativeCompiler());

		long start = System.nanoTime();

		try {
			read.apply(mem, sOffset, out, oOffset, length);
		} finally {
			ioTime.addEntry("getMem", System.nanoTime() - start);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Deallocates all allocated memory and resets memory tracking.</p>
	 */
	@Override
	public synchronized void destroy() {
		if (free == null) free = new Free(getNativeCompiler());
		getAllocated().forEach(this::deallocate);
		memoryUsed = 0;

		super.destroy();
	}
}
