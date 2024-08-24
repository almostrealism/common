/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.RAM;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public class MetalMemoryProvider implements MemoryProvider<RAM>, ConsoleFeatures {
	public static boolean enableLargeAllocationLogging = false;
	public static int largeAllocationSize = 20 * 1024 * 1024 + 2;
	public static boolean enableWarnings = SystemUtils.isEnabled("AR_HARDWARE_MEMORY_WARNINGS").orElse(true);

	public static DistributionMetric allocationSizes = Hardware.console.distribution("mtlAllocationSizes", 1024 * 1024);
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("mtlDeallocationSizes", 1024 * 1024);

	private final MetalDataContext context;
	private final int numberSize;
	private final long memoryMax;
	private long memoryUsed;

	private List<MetalMemory> allocated;
	private List<RAM> deallocating;

	public MetalMemoryProvider(MetalDataContext context, int numberSize, long memoryMax) {
		this.context = context;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;
		this.allocated = new ArrayList<>();
		this.deallocating = new ArrayList<>();
	}

	@Override
	public String getName() { return context.getName(); }

	@Override
	public int getNumberSize() { return numberSize; }

	public long getAllocatedMemory() { return memoryUsed; }

	public MetalDataContext getContext() { return context; }

	@Override
	public MetalMemory allocate(int size) {
		if (enableLargeAllocationLogging && size >  largeAllocationSize) {
			log("Allocating " + (numberSize * (long) size) / 1024 / 1024 + "mb");
		}

		MetalMemory mem = new MetalMemory(this, buffer(size), numberSize * (long) size);
		allocated.add(mem);
		allocationSizes.addEntry(numberSize * (long) size);
		return mem;
	}

	@Override
	public void deallocate(int size, RAM ram) {
		synchronized (deallocating) {
			if (deallocating.contains(ram)) return;
			deallocating.add(ram);
		}

		try {
			if (!(ram instanceof MetalMemory)) throw new IllegalArgumentException();
			if (ram.getProvider() != this)
				throw new IllegalArgumentException();
			MetalMemory mem = (MetalMemory) ram;

			mem.getMem().release();
			memoryUsed = memoryUsed - (long) size * getNumberSize();

			if (!allocated.remove(mem) && enableWarnings) {
				warn("Deallocated untracked memory");
			}
		} finally {
			deallocating.remove(ram);
			deallocationSizes.addEntry(numberSize * (long) size);
		}
	}

	protected MTLBuffer buffer(int len) {
		long sizeOf = (long) len * getNumberSize();

		if (memoryUsed + sizeOf > memoryMax) {
			throw new HardwareException("Memory Max Reached");
		}

		MTLBuffer mem = getContext().getPrecision() == Precision.FP16 ?
				getContext().getDevice().newBuffer16(len) :
				getContext().getDevice().newBuffer32(len);
		memoryUsed = memoryUsed + sizeOf;
		return mem;
	}

	@Override
	public void setMem(RAM ram, int offset, float[] source, int srcOffset, int length) {
		if (!(ram instanceof MetalMemory)) throw new IllegalArgumentException();
		MetalMemory mem = (MetalMemory) ram;

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
	public void setMem(RAM ram, int offset, double[] source, int srcOffset, int length) {
		if (!(ram instanceof MetalMemory)) throw new IllegalArgumentException();
		MetalMemory mem = (MetalMemory) ram;

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
	public void setMem(RAM ram, int offset, Memory srcRam, int srcOffset, int length) {
		if (!(ram instanceof MetalMemory) || length < 0)
			throw new IllegalArgumentException();
		if (!(srcRam instanceof MetalMemory)) {
			// TODO  Native code can be used here, for some types of srcRam
			setMem(ram, offset, srcRam.toArray(srcOffset, length), 0, length);
			return;
		}

		MetalMemory mem = (MetalMemory) ram;
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
	public void getMem(RAM mem, int sOffset, float out[], int oOffset, int length) {
		if (!(mem instanceof MetalMemory)) throw new IllegalArgumentException();
		MetalMemory m = (MetalMemory) mem;

		if (getNumberSize() == 8) {
			double d[] = new double[length];
			DoubleBuffer buf = doubleBuffer(length);
			m.getMem().getContents(buf, sOffset, length);
			buf.get(d);
			for (int i = 0; i < length; i++) out[oOffset + i] = (float) d[i];
		} else {
			FloatBuffer buf = floatBuffer(length);
			m.getMem().getContents(buf, sOffset, length);
			buf.get(out, oOffset, length);
		}
	}

	@Override
	public void getMem(RAM mem, int sOffset, double out[], int oOffset, int length) {
		if (!(mem instanceof MetalMemory)) throw new IllegalArgumentException();
		MetalMemory m = (MetalMemory) mem;

		if (getNumberSize() == 8) {
			DoubleBuffer buf = doubleBuffer(length);
			m.getMem().getContents(buf, sOffset, length);
			buf.get(out, oOffset, length);
		} else {
			float f[] = new float[length];
			FloatBuffer buf = floatBuffer(length);
			m.getMem().getContents(buf, sOffset, length);
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
		// TODO  Deallocating all of these at once appears to produce SIGSEGV
		// List<CLMemory> available = new ArrayList<>(allocated);
		// available.forEach(mem -> deallocate(0, mem));
		allocated = null;
	}

	@Override
	public Console console() { return Hardware.console; }
}
