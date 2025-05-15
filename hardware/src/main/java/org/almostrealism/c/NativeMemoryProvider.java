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

package org.almostrealism.c;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.util.ArrayList;
import java.util.List;

public class NativeMemoryProvider implements MemoryProvider<RAM>, ConsoleFeatures {
	public static boolean enableLargeAllocationLogging =
			SystemUtils.isEnabled("AR_HARDWARE_ALLOCATION_LOGGING").orElse(false);

	public static TimingMetric ioTime = Hardware.console.timing("nativeIO");

	public static DistributionMetric allocationSizes = Hardware.console.distribution("nativeAllocationSizes", 1024 * 1024);
	public static DistributionMetric deallocationSizes = Hardware.console.distribution("nativeDeallocationSizes", 1024 * 1024);


	private NativeCompiler compiler;

	private Malloc malloc;
	private Free free;
	private NativeRead read;
	private NativeWrite write;

	private final long memoryMax;
	private long memoryUsed;

	private List<NativeMemory> allocated;

	public NativeMemoryProvider(NativeCompiler compiler, long memoryMax) {
		this.compiler = compiler;
		this.memoryMax = memoryMax;
		this.allocated = new ArrayList<>();
	}

	@Override
	public String getName() { return "JNI"; }

	@Override
	public int getNumberSize() { return compiler.getPrecision().bytes(); }

	public NativeCompiler getNativeCompiler() { return compiler; }

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
			NativeMemory mem = new NativeMemory(this, malloc.apply(getNumberSize() * size), getNumberSize() * (long) size);
			allocated.add(mem);
			allocationSizes.addEntry(getNumberSize() * (long) size);
			return mem;
		}
	}

	@Override
	public synchronized void deallocate(int size, RAM mem) {
		if (!allocated.contains(mem)) return;

		if (free == null) free = new Free(getNativeCompiler());

		free.apply(mem.getContentPointer());
		memoryUsed -= (long) size * getNumberSize();
		allocated.remove(mem);
		deallocationSizes.addEntry((long) getNumberSize() * size);
	}

	@Override
	public synchronized void setMem(RAM mem, int offset, Memory source, int srcOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");
		double value[] = new double[length];
		source.getProvider().getMem(source, srcOffset, value, 0, length);
		setMem(mem, offset, value, 0, length);
	}

	@Override
	public synchronized void setMem(RAM mem, int offset, double[] source, int srcOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");
		if (write == null) write = new NativeWrite(getNativeCompiler());

		long start = System.nanoTime();
		try {
			write.apply((NativeMemory) mem, offset, source, srcOffset, length);
		} finally {
			ioTime.addEntry("setMem", System.nanoTime() - start);
		}
	}

	@Override
	public synchronized void getMem(RAM mem, int sOffset, double[] out, int oOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");
		if (read == null) read = new NativeRead(getNativeCompiler());

		long start = System.nanoTime();

		try {
			read.apply((NativeMemory) mem, sOffset, out, oOffset, length);
		} finally {
			ioTime.addEntry("getMem", System.nanoTime() - start);
		}
	}

	@Override
	public synchronized void destroy() {
		if (free == null) free = new Free(getNativeCompiler());
		allocated.stream().mapToLong(NativeMemory::getContentPointer).forEach(free::apply);
		allocated.clear();
		memoryUsed = 0;
	}

	@Override
	public Console console() { return Hardware.console; }
}
