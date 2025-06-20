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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.RAM;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

public class CLMemoryProvider implements MemoryProvider<RAM>, ConsoleFeatures {
	public static boolean enableDirectReallocation = true;
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);

	public static DistributionMetric allocationSizes = Hardware.console.distribution("clAllocationSizes", 1024 * 1024);
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("clDeallocationSizes", 1024 * 1024);
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

		NativeBufferMemoryProvider.registerAdapter(CLMemory.class, (mem, offset, length) -> {
			if (mem.getProvider().heap.containsKey(mem.getMem())) {
				Object obj = mem.getProvider().heap.get(mem.getMem()).getObject();

				if (obj instanceof NativeBuffer) {
					NativeBuffer src = (NativeBuffer) obj;
					src.addDeallocationListener(nativeBuffer -> mem.getProvider().heapRemove(mem.getMem()));

					if (src.getSize() == length * mem.getProvider().getNumberSize()) {
						return src;
					} else {
						Hardware.console.warn("Heap item is not the same size as the desired NativeBuffer");
					}
				} else {
					Hardware.console.warn("Heap item " + obj + " is not a NativeBuffer");
				}
			} else {
				Hardware.console.warn("Heap does not contain " + mem.getMem());
			}

			return null;
		});
	}

	public enum Location {
		HOST, DEVICE, HEAP, DELEGATE
	}

	private final Location location;

	private final CLDataContext context;
	private final cl_command_queue queue;
	private final int numberSize;
	private final long memoryMax;
	private long memoryUsed;

	private HashMap<cl_mem, PointerAndObject<?>> heap;
	private HashMap<PointerAndObject<?>, cl_mem> reverseHeap;
	private List<CLMemory> allocated;
	private List<RAM> deallocating;

	public CLMemoryProvider(CLDataContext context, cl_command_queue queue, int numberSize, long memoryMax, Location location) {
		this.context = context;
		this.queue = queue;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;
		this.location = location;
		this.heap = new HashMap<>();
		this.reverseHeap = new HashMap<>();
		this.allocated = new ArrayList<>();
		this.deallocating = new ArrayList<>();
	}

	@Override
	public String getName() { return context.getName(); }

	@Override
	public int getNumberSize() { return numberSize; }

	public long getAllocatedMemory() { return memoryUsed; }

	public CLDataContext getContext() { return context; }

	@Override
	public CLMemory allocate(int size) {
		return allocate(size, null);
	}

	public CLMemory allocate(int size, NativeBuffer src) {
		if (enableLargeAllocationLogging && size > (10 * 1024 * 1024)) {
			log("Allocating " + (numberSize * (long) size) / 1024 / 1024 + "mb");
		}

		try {
			long s = numberSize * (long) size;
			CLMemory mem = new CLMemory(this, buffer(size, src), s);
			allocated.add(mem);
			allocationSizes.addEntry(s);
			return mem;
		} catch (CLException e) {
			throw new HardwareException(e, (long) size * getNumberSize());
		}
	}

	@Override
	public void deallocate(int size, RAM ram) {
		synchronized (deallocating) {
			if (deallocating.contains(ram)) return;
			deallocating.add(ram);
		}

		try {
			if (!(ram instanceof CLMemory)) throw new IllegalArgumentException();
			if (ram.getProvider() != this)
				throw new IllegalArgumentException();
			CLMemory mem = (CLMemory) ram;

			heapRemove(mem.getMem());
			CL.clReleaseMemObject(mem.getMem());
			memoryUsed = memoryUsed - (long) size * getNumberSize();

			if (!allocated.remove(mem) && RAM.enableWarnings) {
				System.out.println("WARN: Deallocated untracked memory");
			}
		} finally {
			deallocating.remove(ram);
			deallocationSizes.addEntry((long) size * getNumberSize());
		}
	}

	@Override
	public RAM reallocate(Memory mem, int offset, int length) {
		if (enableDirectReallocation && mem instanceof NativeBuffer) {
			RAM newMem = allocate(length, (NativeBuffer) mem);
			return newMem;
		} else {
			RAM newMem = allocate(length);
			setMem(newMem, 0, mem, offset, length);
			return newMem;
		}
	}

	protected cl_mem buffer(int len, NativeBuffer src) {
		if (len <= 0) throw new IllegalArgumentException();

		long sizeOf = (long) len * getNumberSize();
		if (sizeOf > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException("It is not possible to allocate " + sizeOf + " bytes of memory at once");
		}

		if (memoryUsed + sizeOf > memoryMax) {
			throw new HardwareException("Memory Max Reached");
		}

		PointerAndObject<?> hostPtr = null;
		long ptrFlag = 0;

		if (src != null) {
			if (src.getProvider().getNumberSize() != getNumberSize()) {
				throw new UnsupportedOperationException();
			}

			hostPtr = PointerAndObject.of(src);
			if (reverseHeap.containsKey(hostPtr)) {
				((NativeBufferMemoryProvider) src.getProvider()).remove(src);
				return reverseHeap.get(hostPtr);
			}

			ptrFlag = CL.CL_MEM_USE_HOST_PTR;
		} else if (location == Location.HEAP && len < Integer.MAX_VALUE / getNumberSize()) {
			hostPtr = PointerAndObject.forLength(getNumberSize(), len);
			ptrFlag = CL.CL_MEM_USE_HOST_PTR;
		} else if (location == Location.DELEGATE) {
			hostPtr = PointerAndObject.of(context.getDelegateMemoryProvider().allocate(len));
			ptrFlag = CL.CL_MEM_USE_HOST_PTR;
		} else if (location == Location.HOST) {
			ptrFlag = CL.CL_MEM_ALLOC_HOST_PTR;
		}

		cl_mem mem = CL.clCreateBuffer(getContext().getClContext(),
				CL.CL_MEM_READ_WRITE + ptrFlag, sizeOf,
				Optional.ofNullable(hostPtr).map(PointerAndObject::getPointer).orElse(null), null);

		memoryUsed = memoryUsed + sizeOf;

		if (hostPtr != null) heapAdd(mem, hostPtr);
		return mem;
	}

	@Override
	public void setMem(RAM ram, int offset, float[] source, int srcOffset, int length) {
		if (!(ram instanceof CLMemory)) throw new IllegalArgumentException();
		CLMemory mem = (CLMemory) ram;

		long start = System.nanoTime();

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

	@Override
	public void setMem(RAM ram, int offset, double[] source, int srcOffset, int length) {
		if (!(ram instanceof CLMemory)) throw new IllegalArgumentException();
		CLMemory mem = (CLMemory) ram;

		long start = System.nanoTime();

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

	@Override
	public void setMem(RAM ram, int offset, Memory srcRam, int srcOffset, int length) {
		if (!(ram instanceof CLMemory)) throw new IllegalArgumentException();

		CLMemory mem = (CLMemory) ram;

		long start = System.nanoTime();

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
				setMem(ram, offset, srcRam.toArray(srcOffset, length), 0, length);
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
			setMem(ram, offset, srcRam.toArray(srcOffset, length), 0, length);
		}
	}

	@Override
	public void getMem(RAM mem, int sOffset, float out[], int oOffset, int length) {
		if (!(mem instanceof CLMemory)) throw new IllegalArgumentException();
		getMem((CLMemory) mem, sOffset, out, oOffset, length, 1);
	}

	private void getMem(CLMemory mem, int sOffset, float out[], int oOffset, int length, int retries) {
		long start = System.nanoTime();

		try {
			IntStream.range(0, retries).mapToObj(r -> getHeapData(mem)).forEach(heapObj -> {
				if (heapObj instanceof float[]) {
					float f[] = (float[]) heapObj;
					// if (length >= 0) System.arraycopy(d, sOffset, out, oOffset, length);
					for (int i = 0; i < length; i++) out[oOffset + i] = f[sOffset + i];
				} else if (heapObj instanceof double[]) {
					double d[] = (double[]) heapObj;
					for (int i = 0; i < length; i++) out[oOffset + i] = (float) d[sOffset + i];
				} else if (getNumberSize() == 8) {
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
			});
		} catch (CLException e) {
			throw CLExceptionProcessor.process(e, this, sOffset, oOffset, length);
		} finally {
			ioTime.addEntry("getMem", System.nanoTime() - start);
		}
	}

	@Override
	public void getMem(RAM mem, int sOffset, double out[], int oOffset, int length) {
		if (!(mem instanceof CLMemory)) throw new IllegalArgumentException();
		getMem((CLMemory) mem, sOffset, out, oOffset, length, 1);
	}

	private void getMem(CLMemory mem, int sOffset, double out[], int oOffset, int length, int retries) {
		long start = System.nanoTime();

		try {
			IntStream.range(0, retries).mapToObj(r -> getHeapData(mem)).forEach(heapObj -> {
				if (heapObj instanceof float[]) {
					float f[] = (float[]) heapObj;
					for (int i = 0; i < length; i++) out[oOffset + i] = f[sOffset + i];
				} else if (heapObj instanceof double[]) {
					double d[] = (double[]) heapObj;
					// if (length >= 0) System.arraycopy(d, sOffset, out, oOffset, length);
					for (int i = 0; i < length; i++) out[oOffset + i] = d[sOffset + i];
				} else if (getNumberSize() == 8) {
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
			});
		} catch (CLException e) {
			throw CLExceptionProcessor.process(e, this, sOffset, oOffset, length);
		} finally {
			ioTime.addEntry("getMem", System.nanoTime() - start);
		}
	}

	private static void processEvent(cl_event event) {
		CL.clWaitForEvents(1, new cl_event[] { event });
		CL.clReleaseEvent(event);
	}

	private void heapAdd(cl_mem mem, PointerAndObject<?> ptr) {
		if (heap == null) heap = new HashMap<>();
		if (reverseHeap == null) reverseHeap = new HashMap<>();
		heap.put(mem, ptr);
		reverseHeap.put(ptr, mem);
	}

	private void heapRemove(PointerAndObject<?> ptr) {
		if (heap == null) return;
		if (reverseHeap == null) return;
		cl_mem mem = reverseHeap.get(ptr);
		if (mem == null) return;
		heap.remove(mem);
		reverseHeap.remove(ptr);
	}

	private void heapRemove(cl_mem mem) {
		if (heap == null) return;
		if (reverseHeap == null) return;
		PointerAndObject<?> ptr = heap.get(mem);
		if (ptr == null) return;
		heap.remove(mem);
		reverseHeap.remove(ptr);
	}

	private Object getHeapData(CLMemory mem) {
		if (heap != null)
			return Optional.ofNullable(heap.get(mem.getMem())).map(PointerAndObject::getObject).orElse(null);
		return null;
	}

	@Override
	public void destroy() {
		// TODO  Deallocating all of these at once appears to produce SIGSEGV
		// List<CLMemory> available = new ArrayList<>(allocated);
		// available.forEach(mem -> deallocate(0, mem));
		allocated = null;
	}

	@Override
	public Console console() { return Hardware.console; }
}
