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
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class MetalMemoryProvider extends HardwareMemoryProvider<MetalMemory> {
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);
	public static int largeAllocationSize = 40 * 1024 * 1024;

	public static DistributionMetric allocationSizes = Hardware.console.distribution("mtlAllocationSizes", 1024 * 1024);
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("mtlDeallocationSizes", 1024 * 1024);

	private final MetalDataContext context;
	private final int numberSize;
	private final long memoryMax;
	private final boolean shared;
	private long memoryUsed;

	private List<NativeRef<MetalMemory>> allocated;
	private List<RAM> deallocating;


	public MetalMemoryProvider(MetalDataContext context, int numberSize, long memoryMax) {
		this(context, numberSize, memoryMax, false);
	}

	protected MetalMemoryProvider(MetalDataContext context, int numberSize, long memoryMax, boolean shared) {
		this.context = context;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;
		this.shared = shared;
		this.allocated = new ArrayList<>();
		this.deallocating = new ArrayList<>();
	}

	@Override
	public String getName() { return context.getName(); }

	@Override
	public int getNumberSize() { return numberSize; }

	public long getAllocatedMemory() { return memoryUsed; }

	public double getMemoryUsed() {
		return getAllocatedMemory() / (double) memoryMax;
	}

	public MetalDataContext getContext() { return context; }

	@Override
	protected MetalMemory fromReference(NativeRef<MetalMemory> reference) {
		MTLBuffer buffer = new MTLBuffer(getContext().getPrecision(), reference.getAddress(), shared);
		return new MetalMemory(this, buffer, reference.getSize());
	}

	@Override
	public MetalMemory allocate(int size) {
		if (enableLargeAllocationLogging && size > largeAllocationSize) {
			log("Allocating " + (numberSize * (long) size) / 1024 / 1024 + "mb");
		}

		MetalMemory mem = new MetalMemory(this, buffer(size), numberSize * (long) size);
		allocated.add(nativeRef(mem));
		allocationSizes.addEntry(numberSize * (long) size);
		return mem;
	}

	@Override
	public void deallocate(int size, MetalMemory mem) {
		synchronized (deallocating) {
			if (deallocating.contains(mem)) return;
			deallocating.add(mem);
		}

		try {
			if (mem.getProvider() != this)
				throw new IllegalArgumentException();

			mem.getMem().release();
			memoryUsed = memoryUsed - (long) size * getNumberSize();

			if (!allocated.removeIf(ref -> ref.getAddress() == mem.getContainerPointer()) && RAM.enableWarnings) {
				warn("Deallocated untracked memory");
			}
		} finally {
			deallocating.remove(mem);
			deallocationSizes.addEntry(numberSize * (long) size);
		}
	}

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

	@Override
	public void destroy() {
		if (allocated != null) {
			allocated.stream()
					.map(this::fromReference)
					.sorted(Comparator.comparing(MetalMemory::getSize).reversed())
					.limit(10)
					.forEach(memory -> {
						warn(memory + " was not deallocated");
						if (memory.getAllocationStackTrace() != null) {
							Stream.of(memory.getAllocationStackTrace())
									.forEach(stack -> warn("\tat " + stack));
						}
					});

			// TODO  Deallocating all of these at once appears to produce SIGSEGV
			// List<MetalMemory> available = new ArrayList<>(allocated);
			// available.forEach(mem -> deallocate(0, mem));
			allocated = null;
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
