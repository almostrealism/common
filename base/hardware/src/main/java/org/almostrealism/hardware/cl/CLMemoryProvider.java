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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;
import org.almostrealism.nio.NativeBuffer;
import org.almostrealism.nio.NativeBufferMemoryProvider;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.cl_command_queue;
import org.jocl.cl_event;
import org.jocl.cl_mem;

/**
 * {@link MemoryProvider} implementation for OpenCL memory management.
 *
 * <p>{@link CLMemoryProvider} allocates and manages {@link CLMemory} backed by OpenCL {@link cl_mem}
 * objects. It extends {@link HardwareMemoryProvider}, so every allocation is tracked by a
 * {@link CLMemoryRef} ({@link java.lang.ref.PhantomReference}) rather than a strong reference,
 * and the underlying {@code cl_mem} is released automatically when the owning {@link CLMemory}
 * is garbage collected — matching the lifecycle used by {@link org.almostrealism.hardware.metal.MetalMemoryProvider}
 * and {@link org.almostrealism.c.NativeMemoryProvider}.</p>
 *
 * <h2>Allocation Strategy</h2>
 *
 * <p>Buffers are allocated directly on the device with {@code CL_MEM_READ_WRITE}. Each
 * {@link CLMemory} owns exactly one {@code cl_mem} (1:1), which is what makes GC-driven
 * release safe: there is no host-pointer aliasing that could cause a shared buffer to be
 * released twice. The host-pinned / heap-shared modes ({@link Location#HOST}, {@link Location#HEAP},
 * {@link Location#DELEGATE}) are no longer honored — a provider configured with any of them
 * allocates device memory and logs a warning. See {@link Location}.</p>
 *
 * <h2>Memory Tracking</h2>
 *
 * <pre>{@code
 * // Current device memory usage in bytes
 * long used = provider.getAllocatedMemory();
 * }</pre>
 *
 * @see CLMemory
 * @see CLMemoryRef
 * @see CLDataContext
 * @see HardwareMemoryProvider
 */
public class CLMemoryProvider extends HardwareMemoryProvider<CLMemory> {
	/**
	 * Enables logging of large memory allocations (greater than 10MB).
	 * Controlled by the {@code AR_HARDWARE_ALLOCATION_LOGGING} system property.
	 */
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);

	/** Distribution metric tracking OpenCL memory allocation sizes in bytes. */
	public static DistributionMetric allocationSizes = Hardware.console.distribution("clAllocationSizes", 1024 * 1024);

	/** Distribution metric tracking OpenCL memory deallocation sizes in bytes. */
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("clDeallocationSizes", 1024 * 1024);

	/** Timing metric tracking OpenCL memory I/O operations (setMem/getMem). */
	public static TimingMetric ioTime = Hardware.console.timing("clIO");

	static {
		NativeBufferMemoryProvider.registerAdapter(CLMemory.class,
				(mem, offset, source, srcOffset, length) -> {
					if (mem.getProvider().getNumberSize() != source.getProvider().getNumberSize()) {
						throw new UnsupportedOperationException();
					}

					Pointer dst = Pointer.to(mem.getBuffer()).withByteOffset(0);
					cl_event event = new cl_event();
					CL.clEnqueueReadBuffer(source.getProvider().queue, source.getMem(),
							CL.CL_TRUE, (long) srcOffset * source.getProvider().getNumberSize(),
							(long) length *  source.getProvider().getNumberSize(), dst, 0,
							null, event);
					processEvent(event);
				});
	}

	/**
	 * Memory allocation location strategies for OpenCL buffers.
	 *
	 * <p>Only {@link #DEVICE} is honored. The remaining values are retained for source
	 * compatibility with existing configuration but now behave as {@link #DEVICE}.</p>
	 */
	public enum Location {
		/**
		 * Deprecated: previously allocated host-pinned memory ({@code CL_MEM_ALLOC_HOST_PTR}).
		 * Now behaves as {@link #DEVICE}.
		 */
		HOST,

		/**
		 * Allocate on device memory only.
		 * Best performance for GPU-only operations but requires explicit transfers.
		 */
		DEVICE,

		/**
		 * Deprecated: previously used Java heap arrays with {@code CL_MEM_USE_HOST_PTR}.
		 * Now behaves as {@link #DEVICE}.
		 */
		HEAP,

		/**
		 * Deprecated: previously delegated allocation to another {@link MemoryProvider}.
		 * Now behaves as {@link #DEVICE}.
		 */
		DELEGATE
	}

	/** The OpenCL data context for buffer creation. */
	private final CLDataContext context;

	/** The command queue for memory transfer operations. */
	private final cl_command_queue queue;

	/** The size in bytes of each numeric element (4 for FP32, 8 for FP64). */
	private final int numberSize;

	/** The maximum total memory that can be allocated in bytes. */
	private final long memoryMax;

	/** The total amount of memory currently allocated in bytes. */
	private long memoryUsed;

	/**
	 * Creates a new OpenCL memory provider.
	 *
	 * @param context    the OpenCL data context for buffer creation
	 * @param queue      the command queue for memory transfer operations
	 * @param numberSize the size in bytes of each numeric element (4 for FP32, 8 for FP64)
	 * @param memoryMax  the maximum total memory that can be allocated in bytes
	 * @param location   the memory allocation strategy; only {@link Location#DEVICE} is honored
	 */
	public CLMemoryProvider(CLDataContext context, cl_command_queue queue,
							int numberSize, long memoryMax, Location location) {
		this.context = context;
		this.queue = queue;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;

		if (location != Location.DEVICE) {
			warn("location=" + location + " is no longer supported; allocating device memory instead");
		}
	}

	/** {@inheritDoc} */
	@Override
	public String getName() { return context.getName(); }

	/** {@inheritDoc} */
	@Override
	public int getNumberSize() { return numberSize; }

	/**
	 * Returns the total amount of memory currently allocated in bytes.
	 *
	 * @return the allocated memory in bytes
	 */
	public long getAllocatedMemory() { return memoryUsed; }

	/**
	 * Returns the OpenCL data context associated with this provider.
	 *
	 * @return the CL data context
	 */
	public CLDataContext getContext() { return context; }

	/** {@inheritDoc} */
	@Override
	public CLMemory allocate(int size) {
		if (enableLargeAllocationLogging && size > (10 * 1024 * 1024)) {
			log("Allocating " + (numberSize * (long) size) / 1024 / 1024 + "mb");
		}

		try {
			long s = numberSize * (long) size;
			CLMemory mem = allocated(new CLMemory(this, buffer(size), s));
			allocationSizes.addEntry(s);
			return mem;
		} catch (CLException e) {
			throw new HardwareException(e, (long) size * getNumberSize());
		}
	}

	/**
	 * Creates a reference for tracking OpenCL memory lifecycle.
	 *
	 * @param ram the {@link CLMemory} to create a reference for
	 * @return new {@link CLMemoryRef} caching the underlying {@code cl_mem} for post-GC release
	 */
	@Override
	protected NativeRef<CLMemory> nativeRef(CLMemory ram) {
		return new CLMemoryRef(ram, getReferenceQueue());
	}

	/**
	 * Releases the OpenCL buffer when its {@link CLMemory} is garbage collected or
	 * explicitly deallocated.
	 *
	 * <p>Called by {@link HardwareMemoryProvider}, which has already claimed the reference
	 * for release (preventing double frees). Releases the underlying {@code cl_mem} and
	 * updates memory usage tracking.</p>
	 *
	 * @param ref the {@link CLMemoryRef} identifying the buffer to release
	 */
	@Override
	protected void deallocate(NativeRef<CLMemory> ref) {
		try {
			CL.clReleaseMemObject(((CLMemoryRef) ref).getMem());
			memoryUsed = memoryUsed - ref.getSize();
		} finally {
			deallocationSizes.addEntry(ref.getSize());
		}
	}

	/**
	 * Creates a device-resident OpenCL buffer.
	 *
	 * @param len the number of elements to allocate
	 * @return the created OpenCL buffer object
	 * @throws IllegalArgumentException if length is not positive
	 * @throws UnsupportedOperationException if the allocation size exceeds {@link Integer#MAX_VALUE} bytes
	 * @throws HardwareException if memory maximum would be exceeded
	 */
	protected cl_mem buffer(int len) {
		if (len <= 0) throw new IllegalArgumentException();

		long sizeOf = (long) len * getNumberSize();
		if (sizeOf > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException("It is not possible to allocate " + sizeOf + " bytes of memory at once");
		}

		if (memoryUsed + sizeOf > memoryMax) {
			throw new HardwareException("Memory Max Reached");
		}

		cl_mem mem = CL.clCreateBuffer(getContext().getClContext(),
				CL.CL_MEM_READ_WRITE, sizeOf, null, null);

		memoryUsed = memoryUsed + sizeOf;
		return mem;
	}

	/** {@inheritDoc} */
	@Override
	public void setMem(CLMemory mem, int offset, float[] source, int srcOffset, int length) {
		long start = System.nanoTime();
		mem.invalidateHostCache();

		try {
			if (context.getPrecision() == Precision.FP64) {
				double d[] = new double[length];
				for (int i = 0; i < d.length; i++) d[i] = source[srcOffset + i];
				Pointer src = Pointer.to(d).withByteOffset(0);
				cl_event event = new cl_event();
				CL.clEnqueueWriteBuffer(queue, mem.getMem(), CL.CL_TRUE,
						(long) offset * getNumberSize(), (long) length * getNumberSize(),
						src, 0, null, event);
				processEvent(event);
			} else {
				Pointer src = Pointer.to(source).withByteOffset(0);
				cl_event event = new cl_event();
				CL.clEnqueueWriteBuffer(queue, mem.getMem(), CL.CL_TRUE,
						(long) offset * getNumberSize(), (long) length * getNumberSize(),
						src, 0, null, event);
				processEvent(event);
			}
		} catch (CLException e) {
			throw CLExceptionProcessor.process(e, this, srcOffset, offset, length);
		} finally {
			ioTime.addEntry("setMem", System.nanoTime() - start);
		}
	}

	/** {@inheritDoc} */
	@Override
	public void setMem(CLMemory mem, int offset, double[] source, int srcOffset, int length) {
		long start = System.nanoTime();
		mem.invalidateHostCache();

		try {
			if (context.getPrecision() == Precision.FP64) {
				Pointer src = Pointer.to(source).withByteOffset((long) srcOffset * getNumberSize());
				cl_event event = new cl_event();
				CL.clEnqueueWriteBuffer(queue, mem.getMem(), CL.CL_TRUE,
						(long) offset * getNumberSize(), (long) length * getNumberSize(),
						src, 0, null, event);
				processEvent(event);
			} else {
				float f[] = new float[length];
				for (int i = 0; i < f.length; i++) f[i] = (float) source[srcOffset + i];
				Pointer src = Pointer.to(f).withByteOffset(0);
				cl_event event = new cl_event();
				CL.clEnqueueWriteBuffer(queue, mem.getMem(), CL.CL_TRUE,
						(long) offset * getNumberSize(), (long) length * getNumberSize(),
						src, 0, null, event);
				processEvent(event);
			}
		} catch (CLException e) {
			throw CLExceptionProcessor.process(e, this, srcOffset, offset, length);
		} finally {
			ioTime.addEntry("setMem", System.nanoTime() - start);
		}
	}

	/** {@inheritDoc} */
	@Override
	public void setMem(CLMemory mem, int offset, Memory srcRam, int srcOffset, int length) {
		long start = System.nanoTime();
		mem.invalidateHostCache();

		if (srcRam instanceof CLMemory) {
			CLMemory src = (CLMemory) srcRam;

			try {
				cl_event event = new cl_event();
				CL.clEnqueueCopyBuffer(queue, src.getMem(), mem.getMem(),
						(long) srcOffset * getNumberSize(),
						(long) offset * getNumberSize(), (long) length * getNumberSize(),
						0, null, event);
				processEvent(event);
			} catch (CLException e) {
				throw CLExceptionProcessor.process(e, this, srcOffset, offset, length);
			} finally {
				ioTime.addEntry("setMem", System.nanoTime() - start);
			}
		} else if (srcRam instanceof NativeBuffer) {
			if (srcRam.getProvider().getNumberSize() != getNumberSize()) {
				warn("Unable to copy memory directly due to precision difference");
				setMem(mem, offset, srcRam.toArray(srcOffset, length), 0, length);
				return;
			}

			try {
				Pointer src = Pointer.to(((NativeBuffer) srcRam).getBuffer()).withByteOffset(0);
				cl_event event = new cl_event();
				CL.clEnqueueWriteBuffer(queue, mem.getMem(), CL.CL_TRUE,
						(long) offset * getNumberSize(), (long) length * getNumberSize(),
						src, 0, null, event);
				processEvent(event);
			} catch (CLException e) {
				throw CLExceptionProcessor.process(e, this, srcOffset, offset, length);
			} finally {
				ioTime.addEntry("setMem", System.nanoTime() - start);
			}
		} else {
			// TODO  There should still be some way to use clEnqueueWriteBuffer for cases
			// TODO  where all we have is the long value returned by RAM::getContentPointer
			setMem(mem, offset, srcRam.toArray(srcOffset, length), 0, length);
		}
	}

	/** {@inheritDoc} */
	@Override
	public void getMem(CLMemory mem, int sOffset, float out[], int oOffset, int length) {
		long start = System.nanoTime();

		try {
			double[] cache = mem.snapshotForRead(length, () -> readWholeBuffer(mem));
			if (cache != null) {
				for (int i = 0; i < length; i++) out[oOffset + i] = (float) cache[sOffset + i];
				return;
			}

			if (getNumberSize() == 8) {
				double d[] = new double[length];
				Pointer dst = Pointer.to(d).withByteOffset(0);
				cl_event event = new cl_event();
				CL.clEnqueueReadBuffer(queue, mem.getMem(),
						CL.CL_TRUE, (long) sOffset * getNumberSize(),
						(long) length * getNumberSize(), dst, 0,
						null, event);
				processEvent(event);
				for (int i = 0; i < d.length; i++) out[oOffset + i] = (float) d[i];
			} else if (getNumberSize() == 4) {
				Pointer dst = Pointer.to(out).withByteOffset((long) oOffset * getNumberSize());
				cl_event event = new cl_event();
				CL.clEnqueueReadBuffer(queue, mem.getMem(),
						CL.CL_TRUE, (long) sOffset * getNumberSize(),
						(long) length * getNumberSize(), dst, 0,
						null, event);
				processEvent(event);
			} else {
				throw new IllegalArgumentException();
			}
		} catch (CLException e) {
			throw CLExceptionProcessor.process(e, this, sOffset, oOffset, length);
		} finally {
			ioTime.addEntry("getMem", System.nanoTime() - start);
		}
	}

	/** {@inheritDoc} */
	@Override
	public void getMem(CLMemory mem, int sOffset, double out[], int oOffset, int length) {
		long start = System.nanoTime();

		try {
			double[] cache = mem.snapshotForRead(length, () -> readWholeBuffer(mem));
			if (cache != null) {
				for (int i = 0; i < length; i++) out[oOffset + i] = cache[sOffset + i];
				return;
			}

			if (getNumberSize() == 8) {
				Pointer dst = Pointer.to(out).withByteOffset((long) oOffset * getNumberSize());
				cl_event event = new cl_event();
				CL.clEnqueueReadBuffer(queue, mem.getMem(),
						CL.CL_TRUE, (long) sOffset * getNumberSize(),
						(long) length * getNumberSize(), dst, 0,
						null, event);
				processEvent(event);
			} else if (getNumberSize() == 4) {
				float f[] = new float[length];
				Pointer dst = Pointer.to(f).withByteOffset(0);
				cl_event event = new cl_event();
				CL.clEnqueueReadBuffer(queue, mem.getMem(),
						CL.CL_TRUE, (long) sOffset * getNumberSize(),
						(long) length * getNumberSize(), dst, 0,
						null, event);
				processEvent(event);
				for (int i = 0; i < f.length; i++) out[oOffset + i] = f[i];
			} else {
				throw new IllegalArgumentException();
			}
		} catch (CLException e) {
			throw CLExceptionProcessor.process(e, this, sOffset, oOffset, length);
		} finally {
			ioTime.addEntry("getMem", System.nanoTime() - start);
		}
	}

	/**
	 * Reads every element of the buffer into a new {@code double[]} with a single transfer.
	 * Used by {@link CLMemory#snapshotForRead(int, java.util.function.Supplier)} to capture a
	 * whole-buffer snapshot that serves repeated per-element reads without a device round-trip
	 * per element.
	 *
	 * @param mem the memory to read
	 * @return a snapshot of all elements as doubles
	 */
	private double[] readWholeBuffer(CLMemory mem) {
		int total = (int) (mem.getSize() / getNumberSize());
		double[] cache = new double[total];
		cl_event event = new cl_event();

		if (getNumberSize() == 8) {
			CL.clEnqueueReadBuffer(queue, mem.getMem(), CL.CL_TRUE, 0,
					(long) total * getNumberSize(), Pointer.to(cache).withByteOffset(0), 0, null, event);
			processEvent(event);
		} else {
			float[] tmp = new float[total];
			CL.clEnqueueReadBuffer(queue, mem.getMem(), CL.CL_TRUE, 0,
					(long) total * getNumberSize(), Pointer.to(tmp).withByteOffset(0), 0, null, event);
			processEvent(event);
			for (int i = 0; i < total; i++) cache[i] = tmp[i];
		}

		return cache;
	}

	/**
	 * Waits for an OpenCL event to complete and releases it.
	 *
	 * @param event the OpenCL event to process
	 */
	private static void processEvent(cl_event event) {
		CL.clWaitForEvents(1, new cl_event[] { event });
		CL.clReleaseEvent(event);
	}
}
