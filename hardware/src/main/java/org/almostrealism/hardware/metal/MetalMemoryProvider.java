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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Memory;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.io.Console;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

/**
 * {@link io.almostrealism.code.MemoryProvider} for Metal GPU memory management.
 *
 * <p>Allocates and manages {@link MetalMemory} backed by {@link MTLBuffer}, supporting
 * shared and managed storage modes, automatic reference counting, and efficient transfers.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * MetalMemoryProvider provider = ...;
 *
 * // Allocate Metal buffer
 * MetalMemory mem = provider.allocate(1024);
 *
 * // Memory transfers
 * float[] data = {1.0f, 2.0f, 3.0f};
 * provider.setMem(mem, 0, data, 0, 3);
 *
 * float[] result = new float[3];
 * provider.getMem(mem, 0, result, 0, 3);
 * }</pre>
 *
 * <h2>Storage Modes</h2>
 *
 * <pre>{@code
 * // Shared mode: CPU/GPU accessible (default)
 * MetalMemoryProvider shared = new MetalMemoryProvider(ctx, 4, maxMem, false);
 *
 * // Managed mode: Explicit synchronization
 * MetalMemoryProvider managed = new MetalMemoryProvider(ctx, 4, maxMem, true);
 * }</pre>
 *
 * @see MetalMemory
 * @see MTLBuffer
 * @see MetalDataContext
 */
public class MetalMemoryProvider extends HardwareMemoryProvider<MetalMemory> {
	/**
	 * Enables logging for large memory allocations when AR_HARDWARE_ALLOCATION_LOGGING is set.
	 */
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);

	/**
	 * Threshold in bytes for what constitutes a "large" allocation (default: 40MB).
	 */
	public static int largeAllocationSize = 40 * 1024 * 1024;

	/**
	 * Metric tracking distribution of Metal buffer allocation sizes.
	 */
	public static DistributionMetric allocationSizes = Hardware.console.distribution("mtlAllocationSizes", 1024 * 1024);

	/**
	 * Metric tracking distribution of Metal buffer deallocation sizes.
	 */
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("mtlDeallocationSizes", 1024 * 1024);

	private final MetalDataContext context;
	private final int numberSize;
	private final long memoryMax;
	private final boolean shared;
	private long memoryUsed;

	/**
	 * Creates a Metal memory provider with shared storage mode.
	 *
	 * <p>Uses shared storage mode for CPU/GPU accessible memory without
	 * explicit synchronization.</p>
	 *
	 * @param context The {@link MetalDataContext} providing device access
	 * @param numberSize Size in bytes of each number (4 for FP32, 8 for FP64)
	 * @param memoryMax Maximum memory in bytes that can be allocated
	 */
	public MetalMemoryProvider(MetalDataContext context, int numberSize, long memoryMax) {
		this(context, numberSize, memoryMax, false);
	}

	/**
	 * Creates a Metal memory provider with configurable storage mode.
	 *
	 * @param context The {@link MetalDataContext} providing device access
	 * @param numberSize Size in bytes of each number (4 for FP32, 8 for FP64)
	 * @param memoryMax Maximum memory in bytes that can be allocated
	 * @param shared If true, uses shared storage mode; if false, uses managed mode
	 */
	protected MetalMemoryProvider(MetalDataContext context, int numberSize, long memoryMax, boolean shared) {
		this.context = context;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;
		this.shared = shared;
	}

	/**
	 * Returns the name of this memory provider.
	 *
	 * @return Provider name from the underlying Metal context
	 */
	@Override
	public String getName() { return context.getName(); }

	/**
	 * Returns the size in bytes of each numeric element.
	 *
	 * @return 4 for FP32, 8 for FP64
	 */
	@Override
	public int getNumberSize() { return numberSize; }

	/**
	 * Returns the total amount of memory currently allocated.
	 *
	 * @return Allocated memory in bytes
	 */
	public long getAllocatedMemory() { return memoryUsed; }

	/**
	 * Returns the fraction of maximum memory currently in use.
	 *
	 * @return Value from 0.0 to 1.0 representing memory usage ratio
	 */
	public double getMemoryUsed() {
		return getAllocatedMemory() / (double) memoryMax;
	}

	/**
	 * Returns the Metal data context for this provider.
	 *
	 * @return The {@link MetalDataContext} providing device access
	 */
	public MetalDataContext getContext() { return context; }

	/**
	 * Creates a native reference for tracking Metal memory lifecycle.
	 *
	 * @param ram The {@link MetalMemory} to create a reference for
	 * @return New {@link MetalMemoryRef} for automatic deallocation
	 */
	@Override
	protected NativeRef<MetalMemory> nativeRef(MetalMemory ram) {
		return new MetalMemoryRef(ram, getReferenceQueue());
	}

	/**
	 * Allocates a new Metal buffer with the specified number of elements.
	 *
	 * <p>Creates an {@link MTLBuffer} with capacity for the requested number of
	 * elements (each {@code numberSize} bytes). Tracks allocation in metrics and
	 * logs large allocations if enabled.</p>
	 *
	 * @param size Number of elements to allocate
	 * @return New {@link MetalMemory} backed by an {@link MTLBuffer}
	 * @throws HardwareException if allocation would exceed memory limit
	 */
	@Override
	public MetalMemory allocate(int size) {
		if (enableLargeAllocationLogging && size > largeAllocationSize) {
			log("Allocating " + (numberSize * (long) size) / 1024 / 1024 + "mb");
		}

		MetalMemory mem = allocated(new MetalMemory(this, buffer(size), numberSize * (long) size));
		allocationSizes.addEntry(numberSize * (long) size);
		return mem;
	}

	/**
	 * Releases Metal buffer resources when the memory is garbage collected.
	 *
	 * <p>Called automatically by the reference queue when the {@link MetalMemory}
	 * becomes unreachable. Releases the underlying {@link MTLBuffer} and updates
	 * memory usage tracking.</p>
	 *
	 * @param ref The {@link MetalMemoryRef} to deallocate
	 */
	@Override
	protected void deallocate(NativeRef<MetalMemory> ref) {
		try {
			MTLBuffer buf = ((MetalMemoryRef) ref).getBuffer();
			if (buf.isReleased()) return;

			synchronized (buf) {
				if (!buf.isReleased()) {
					buf.release();
					memoryUsed = memoryUsed - ref.getSize();
				}
			}
		} finally {
			deallocationSizes.addEntry(ref.getSize());
		}
	}

	/**
	 * Creates a new Metal buffer with the specified element capacity.
	 *
	 * <p>Allocates either shared or managed storage depending on configuration.
	 * Shared buffers use memory-mapped files for CPU/GPU accessibility. Supports
	 * FP16 and FP32 precisions for managed buffers, FP32 only for shared.</p>
	 *
	 * @param len Number of elements (not bytes)
	 * @return New {@link MTLBuffer} with requested capacity
	 * @throws HardwareException if allocation would exceed memory limit
	 * @throws HardwareException if shared mode requested with non-FP32 precision
	 */
	protected MTLBuffer buffer(int len) {
		long sizeOf = (long) len * getNumberSize();

		if (memoryUsed + sizeOf > memoryMax) {
			throw new HardwareException("Memory Max Reached");
		}

		MTLBuffer mem;

		if (shared) {
			if (getContext().getPrecision() != Precision.FP32) {
				throw new HardwareException("Shared memory must be " + Precision.FP32.name());
			}

			mem = getContext().getDevice().newSharedBuffer32(getMemoryName().apply(len), len);
		} else {
			mem = getContext().getPrecision() == Precision.FP16 ?
					getContext().getDevice().newBuffer16(len) :
					getContext().getDevice().newBuffer32(len);
		}

		memoryUsed = memoryUsed + sizeOf;
		return mem;
	}

	/**
	 * Copies float array data into Metal memory.
	 *
	 * <p>Converts data to double precision if provider uses FP64, otherwise
	 * copies as-is for FP32/FP16.</p>
	 *
	 * @param mem The destination {@link MetalMemory}
	 * @param offset Starting offset in destination (in elements)
	 * @param source Source float array
	 * @param srcOffset Starting offset in source array
	 * @param length Number of elements to copy
	 */
	@Override
	public void setMem(MetalMemory mem, int offset, float[] source, int srcOffset, int length) {
		if (getNumberSize() == 8) {
			double d[] = new double[length];
			for (int i = 0; i < length; i++) d[i] = source[srcOffset + i];

			DoubleBuffer buf = doubleBuffer(length);
			buf.put(d);
			mem.getMem().setContents(buf, offset, length);
		} else {
			FloatBuffer buf = floatBuffer(length);
			buf.put(source, srcOffset, length);
			mem.getMem().setContents(buf, offset, length);
		}
	}

	/**
	 * Copies double array data into Metal memory.
	 *
	 * <p>Copies as-is for FP64, converts to float for FP32/FP16.</p>
	 *
	 * @param mem The destination {@link MetalMemory}
	 * @param offset Starting offset in destination (in elements)
	 * @param source Source double array
	 * @param srcOffset Starting offset in source array
	 * @param length Number of elements to copy
	 */
	@Override
	public void setMem(MetalMemory mem, int offset, double[] source, int srcOffset, int length) {
		if (getNumberSize() == 8) {
			DoubleBuffer buf = doubleBuffer(length);
			buf.put(source, srcOffset, length);
			mem.getMem().setContents(buf, offset, length);
		} else {
			float f[] = new float[length];
			for (int i = 0; i < f.length; i++) f[i] = (float) source[srcOffset + i];

			FloatBuffer buf = floatBuffer(length);
			buf.put(f);
			mem.getMem().setContents(buf, offset, length);
		}
	}

	/**
	 * Copies data from another memory region into Metal memory.
	 *
	 * <p>Optimized for Metal-to-Metal transfers when source is also {@link MetalMemory}.
	 * Falls back to array-based transfer for other memory types.</p>
	 *
	 * @param mem The destination {@link MetalMemory}
	 * @param offset Starting offset in destination (in elements)
	 * @param srcRam Source memory region (any {@link Memory} implementation)
	 * @param srcOffset Starting offset in source (in elements)
	 * @param length Number of elements to copy
	 * @throws IllegalArgumentException if length is negative
	 */
	@Override
	public void setMem(MetalMemory mem, int offset, Memory srcRam, int srcOffset, int length) {
		if (length < 0)
			throw new IllegalArgumentException();
		if (!(srcRam instanceof MetalMemory)) {
			// TODO  Native code can be used here, for some types of srcRam
			setMem(mem, offset, srcRam.toArray(srcOffset, length), 0, length);
			return;
		}

		MetalMemory src = (MetalMemory) srcRam;

		if (getNumberSize() == 8) {
			DoubleBuffer buf = doubleBuffer(length);
			src.getMem().getContents(buf, srcOffset, length);
			mem.getMem().setContents(buf, offset, length);
		} else {
			FloatBuffer buf = floatBuffer(length);
			src.getMem().getContents(buf, srcOffset, length);
			mem.getMem().setContents(buf, offset, length);
		}
	}

	/**
	 * Reads data from Metal memory into a float array.
	 *
	 * <p>Converts from double precision if provider uses FP64, otherwise
	 * reads as-is for FP32/FP16.</p>
	 *
	 * @param mem The source {@link MetalMemory}
	 * @param sOffset Starting offset in source (in elements)
	 * @param out Destination float array
	 * @param oOffset Starting offset in destination array
	 * @param length Number of elements to read
	 */
	@Override
	public void getMem(MetalMemory mem, int sOffset, float out[], int oOffset, int length) {
		if (getNumberSize() == 8) {
			double d[] = new double[length];
			DoubleBuffer buf = doubleBuffer(length);
			mem.getMem().getContents(buf, sOffset, length);
			buf.get(d);
			for (int i = 0; i < length; i++) out[oOffset + i] = (float) d[i];
		} else {
			FloatBuffer buf = floatBuffer(length);
			mem.getMem().getContents(buf, sOffset, length);
			buf.get(out, oOffset, length);
		}
	}

	/**
	 * Reads data from Metal memory into a double array.
	 *
	 * <p>Reads as-is for FP64, converts from float for FP32/FP16.</p>
	 *
	 * @param mem The source {@link MetalMemory}
	 * @param sOffset Starting offset in source (in elements)
	 * @param out Destination double array
	 * @param oOffset Starting offset in destination array
	 * @param length Number of elements to read
	 */
	@Override
	public void getMem(MetalMemory mem, int sOffset, double out[], int oOffset, int length) {
		if (getNumberSize() == 8) {
			DoubleBuffer buf = doubleBuffer(length);
			mem.getMem().getContents(buf, sOffset, length);
			buf.get(out, oOffset, length);
		} else {
			float f[] = new float[length];
			FloatBuffer buf = floatBuffer(length);
			mem.getMem().getContents(buf, sOffset, length);
			buf.get(f);
			for (int i = 0; i < length; i++) out[oOffset + i] = f[i];
		}
	}

	private FloatBuffer floatBuffer(int len) {
		ByteBuffer bufferByte = ByteBuffer.allocateDirect(len * 4).order(ByteOrder.nativeOrder());
		return bufferByte.asFloatBuffer();
	}

	private DoubleBuffer doubleBuffer(int len) {
		ByteBuffer bufferByte = ByteBuffer.allocateDirect(len * 8).order(ByteOrder.nativeOrder());
		return bufferByte.asDoubleBuffer();
	}

	/**
	 * Returns the console for logging and metrics output.
	 *
	 * @return The {@link Console} instance for hardware logging
	 */
	@Override
	public Console console() { return Hardware.console; }
}
