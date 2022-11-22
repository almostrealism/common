/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.RAM;
import org.almostrealism.io.SystemUtils;
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

public class CLMemoryProvider implements MemoryProvider<RAM> {
	public static boolean enableWarnings = SystemUtils.isEnabled("AR_HARDWARE_MEMORY_WARNINGS").orElse(true);

	public enum Location {
		HOST, DEVICE, HEAP
	}

	private final Location location;

	private final CLDataContext context;
	private final cl_command_queue queue;
	private final int numberSize;
	private final long memoryMax;
	private long memoryUsed;

	private HashMap<cl_mem, PointerAndObject<?>> heap;
	private List<CLMemory> allocated;
	private List<RAM> deallocating;

	public CLMemoryProvider(CLDataContext context, cl_command_queue queue, int numberSize, long memoryMax, Location location) {
		this.context = context;
		this.queue = queue;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;
		this.location = location;
		if (location == Location.HEAP) heap = new HashMap<>();
		this.allocated = new ArrayList<>();
		this.deallocating = new ArrayList<>();
	}

	public int getNumberSize() { return numberSize; }

	public long getAllocatedMemory() { return memoryUsed; }

	public CLDataContext getContext() { return context; }

	@Override
	public CLMemory allocate(int size) {
		try {
			CLMemory mem = new CLMemory(this, buffer(size), numberSize * (long) size);
			allocated.add(mem);
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

			if (heap != null) heap.remove(mem.getMem());
			CL.clReleaseMemObject(mem.getMem());
			memoryUsed = memoryUsed - (long) size * getNumberSize();

			if (!allocated.remove(mem) && enableWarnings) {
				System.out.println("WARN: Deallocated untracked memory");
			}
		} finally {
			deallocating.remove(ram);
		}
	}

	protected cl_mem buffer(int len) {
		long sizeOf = (long) len * getNumberSize();

		if (memoryUsed + sizeOf > memoryMax) {
			throw new HardwareException("Memory Max Reached");
		}

		PointerAndObject<?> hostPtr = null;
		long ptrFlag = 0;

		if (location == Location.HEAP && len < Integer.MAX_VALUE / getNumberSize()) {
			hostPtr = PointerAndObject.forLength(getNumberSize(), len);
			ptrFlag = CL.CL_MEM_USE_HOST_PTR;
		} else if (location == Location.HOST) {
			ptrFlag = CL.CL_MEM_ALLOC_HOST_PTR;
		}

		cl_mem mem = CL.clCreateBuffer(getContext().getClContext(),
				CL.CL_MEM_READ_WRITE + ptrFlag, sizeOf,
				Optional.ofNullable(hostPtr).map(PointerAndObject::getPointer).orElse(null), null);

		memoryUsed = memoryUsed + sizeOf;

		if (hostPtr != null) this.heap.put(mem, hostPtr);
		return mem;
	}

	@Override
	public void setMem(RAM ram, int offset, double[] source, int srcOffset, int length) {
		if (!(ram instanceof CLMemory)) throw new IllegalArgumentException();
		CLMemory mem = (CLMemory) ram;

		try {
			if (Hardware.getLocalHardware().isDoublePrecision()) {
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
		}
	}

	@Override
	public void setMem(RAM ram, int offset, RAM srcRam, int srcOffset, int length) {
		if (!(ram instanceof CLMemory)) throw new IllegalArgumentException();
		if (!(srcRam instanceof CLMemory)) throw new IllegalArgumentException();

		CLMemory mem = (CLMemory) ram;
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
		}
	}

	@Override
	public void getMem(RAM mem, int sOffset, double out[], int oOffset, int length) {
		if (!(mem instanceof CLMemory)) throw new IllegalArgumentException();
		getMem((CLMemory) mem, sOffset, out, oOffset, length, 1);
	}

	private void getMem(CLMemory mem, int sOffset, double out[], int oOffset, int length, int retries) {
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
		}
	}

	private void processEvent(cl_event event) {
		CL.clWaitForEvents(1, new cl_event[] { event });
		CL.clReleaseEvent(event);
	}

	private Object getHeapData(CLMemory mem) {
		if (heap != null) return heap.get(mem.getMem()).getObject();
		return null;
	}

	@Override
	public void destroy() {
		// TODO  Deallocating all of these at once appears to produce SIGSEGV
		// List<CLMemory> available = new ArrayList<>(allocated);
		// available.forEach(mem -> deallocate(0, mem));
		allocated = null;
	}
}
