/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.cuda;

import io.almostrealism.code.Memory;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.AllocationCache;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.io.Console;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;

/**
 * Allocates {@link CudaMemory} in the context of a {@link CudaDataContext}.
 *
 * <p>Allocations are either managed (addressable from host and device) or device-only, as
 * decided by the data context from the device's capabilities. Host reads and writes go
 * through the driver's copy functions in both cases, which are correct for either kind.</p>
 *
 * <p>Allocation is bounded by a maximum reservation; exceeding it throws a
 * {@link HardwareException} with the message {@code "Memory Max Reached"}, as the other
 * hardware memory providers do.</p>
 */
public class CudaMemoryProvider extends HardwareMemoryProvider<CudaMemory> {
	/** Whether to log allocations larger than {@link #largeAllocationSize}. */
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);

	/** The size in bytes above which an allocation is considered large. */
	public static int largeAllocationSize = 40 * 1024 * 1024;

	/** The largest released buffer, in bytes, kept for reuse rather than freed. */
	public static long maxCachedAllocation = 16L * 1024 * 1024;

	/** Sizes of allocations made by all CUDA memory providers. */
	public static DistributionMetric allocationSizes = Hardware.console.distribution("cudaAllocationSizes", 1024 * 1024);

	/** Sizes of deallocations made by all CUDA memory providers. */
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("cudaDeallocationSizes", 1024 * 1024);

	/** The data context whose CUDA context owns the allocations. */
	private final CudaDataContext context;

	/** The size in bytes of one number. */
	private final int numberSize;

	/** The maximum number of bytes this provider may have allocated at once. */
	private final long memoryMax;

	/** Whether allocations are managed rather than device-only. */
	private final boolean managed;

	/** The number of bytes currently allocated and in use. */
	private long memoryUsed;

	/**
	 * Released buffers kept for reuse. Allocating a buffer and freeing one are each expensive
	 * (freeing synchronizes the device), and a workload such as a training loop allocates the
	 * same sizes over and over. Held buffers count toward the reservation, and are freed when an
	 * allocation would otherwise exceed it.
	 */
	private final AllocationCache<CUDeviceBuffer> cache;

	/**
	 * Creates a provider.
	 *
	 * @param context    the data context whose CUDA context owns the allocations
	 * @param numberSize the size in bytes of one number
	 * @param memoryMax  the maximum number of bytes allocated at once
	 * @param managed    whether to allocate managed rather than device-only memory
	 */
	public CudaMemoryProvider(CudaDataContext context, int numberSize, long memoryMax, boolean managed) {
		this.context = context;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;
		this.managed = managed;
		this.cache = new AllocationCache<>(memoryMax / 8, maxCachedAllocation);
	}

	@Override
	public String getName() { return context.getName(); }

	@Override
	public int getNumberSize() { return numberSize; }

	/** Returns whether allocations are managed, and therefore addressable from the host. */
	public boolean isManaged() { return managed; }

	/** Returns the number of bytes currently allocated. */
	public synchronized long getAllocatedMemory() { return memoryUsed; }

	/** Returns the fraction of the maximum reservation currently allocated. */
	public double getMemoryUsed() {
		return getAllocatedMemory() / (double) memoryMax;
	}

	@Override
	protected NativeRef<CudaMemory> nativeRef(CudaMemory ram) {
		return new CudaMemoryRef(ram, getReferenceQueue());
	}

	@Override
	public CudaMemory allocate(int size) {
		long bytes = numberSize * (long) size;

		if (enableLargeAllocationLogging && bytes > largeAllocationSize) {
			log("Allocating " + bytes / 1024 / 1024 + "mb");
		}

		beginAllocation();

		try {
			CudaMemory mem = allocated(new CudaMemory(this, buffer(bytes)));
			allocationSizes.addEntry(bytes);
			return mem;
		} finally {
			endAllocation();
		}
	}

	/**
	 * Allocates a buffer of the given size, enforcing the maximum reservation. The driver rejects
	 * an allocation of zero bytes, so an empty region is given a one-byte buffer; the reservation
	 * accounts for the bytes actually allocated, which is also what is released when the buffer is.
	 *
	 * @throws HardwareException if the allocation would exceed the maximum reservation
	 */
	private CUDeviceBuffer buffer(long requested) {
		long bytes = Math.max(requested, 1);

		CUDeviceBuffer reused = cache.take(bytes);
		if (reused != null) {
			synchronized (this) {
				memoryUsed += bytes;
			}

			return reused;
		}

		if (getAllocatedMemory() + cache.getHeldBytes() + bytes > memoryMax) {
			cache.flush(CUDeviceBuffer::release);
		}

		synchronized (this) {
			if (memoryUsed + bytes > memoryMax) {
				throw new HardwareException("Memory Max Reached");
			}

			memoryUsed += bytes;
		}

		try {
			CUContext ctx = context.getCudaContext();
			return managed ? ctx.allocateManaged(bytes) : ctx.allocate(bytes);
		} catch (RuntimeException e) {
			synchronized (this) {
				memoryUsed -= bytes;
			}

			throw e;
		}
	}

	/**
	 * Releases the buffer behind a freed allocation: it is kept for reuse if the cache has room
	 * for it, and freed otherwise.
	 *
	 * @param ref the freed allocation
	 */
	@Override
	protected void deallocate(NativeRef<CudaMemory> ref) {
		try {
			CUDeviceBuffer buf = ((CudaMemoryRef) ref).getBuffer();

			if (buf.isReleased()) return;

			synchronized (this) {
				memoryUsed -= ref.getSize();
			}

			if (!cache.offer(ref.getSize(), buf)) {
				buf.release();
			}
		} finally {
			deallocationSizes.addEntry(ref.getSize());
		}
	}

	/**
	 * Frees the buffers kept for reuse, so that nothing remains allocated once the context is
	 * released, and then destroys the provider. A buffer released afterwards is freed at once.
	 */
	@Override
	public void destroy() {
		cache.close(CUDeviceBuffer::release);
		super.destroy();
	}

	@Override
	public void setMem(CudaMemory mem, int offset, float[] source, int srcOffset, int length) {
		if (numberSize == Double.BYTES) {
			double[] values = new double[length];
			for (int i = 0; i < length; i++) values[i] = source[srcOffset + i];
			mem.getBuffer().setContents(values, 0, offset, length);
		} else {
			mem.getBuffer().setContents(source, srcOffset, offset, length);
		}
	}

	@Override
	public void setMem(CudaMemory mem, int offset, double[] source, int srcOffset, int length) {
		if (numberSize == Double.BYTES) {
			mem.getBuffer().setContents(source, srcOffset, offset, length);
		} else {
			float[] values = new float[length];
			for (int i = 0; i < length; i++) values[i] = (float) source[srcOffset + i];
			mem.getBuffer().setContents(values, 0, offset, length);
		}
	}

	@Override
	public void setMem(CudaMemory mem, int offset, Memory srcRam, int srcOffset, int length) {
		if (length < 0) throw new IllegalArgumentException();

		if (srcRam instanceof CudaMemory && srcRam.getProvider().getNumberSize() == numberSize) {
			CudaMemory src = (CudaMemory) srcRam;
			mem.getBuffer().copyFrom(src.getBuffer(), (long) srcOffset * numberSize,
					(long) offset * numberSize, (long) length * numberSize);
			return;
		}

		setMem(mem, offset, srcRam.toArray(srcOffset, length), 0, length);
	}

	@Override
	public void getMem(CudaMemory mem, int sOffset, float[] out, int oOffset, int length) {
		if (numberSize == Double.BYTES) {
			double[] values = new double[length];
			mem.getBuffer().getContents(values, 0, sOffset, length);
			for (int i = 0; i < length; i++) out[oOffset + i] = (float) values[i];
		} else {
			mem.getBuffer().getContents(out, oOffset, sOffset, length);
		}
	}

	@Override
	public void getMem(CudaMemory mem, int sOffset, double[] out, int oOffset, int length) {
		if (numberSize == Double.BYTES) {
			mem.getBuffer().getContents(out, oOffset, sOffset, length);
		} else {
			float[] values = new float[length];
			mem.getBuffer().getContents(values, 0, sOffset, length);
			for (int i = 0; i < length; i++) out[oOffset + i] = values[i];
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
