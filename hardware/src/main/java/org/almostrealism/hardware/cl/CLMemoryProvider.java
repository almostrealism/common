/*
 * Copyright 2021 Michael Murray
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
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.cl_context;
import org.jocl.cl_mem;

import java.util.HashMap;

public class CLMemoryProvider implements MemoryProvider<CLMemory> {
	private final boolean enableHeapStorage;

	private final cl_context context;
	private final int numberSize;
	private final long memoryMax;
	private long memoryUsed;

	private HashMap<cl_mem, byte[]> heap;

	public CLMemoryProvider(cl_context context, int numberSize, long memoryMax, boolean enableHeap) {
		this.context = context;
		this.numberSize = numberSize;
		this.memoryMax = memoryMax;
		this.enableHeapStorage = enableHeap;
		if (enableHeapStorage) heap = new HashMap<>();
	}

	public int getNumberSize() { return numberSize; }

	public long getAllocatedMemory() { return memoryUsed; }

	public cl_context getContext() { return context; }

	@Override
	public CLMemory allocate(int size) {
		long sizeOf = (long) size * getNumberSize();

		if (memoryUsed + sizeOf > memoryMax) {
			throw new RuntimeException("Hardware: Memory Max Reached");
		}

		CLMemory mem = new CLMemory(buffer(sizeOf));
		memoryUsed = memoryUsed + sizeOf;
		return mem;
	}

	@Override
	public void deallocate(int size, CLMemory mem) {
		heap.remove(mem.getMem());
		CL.clReleaseMemObject(mem.getMem());
		memoryUsed = memoryUsed - (long) size * getNumberSize();
	}

	protected cl_mem buffer(long sizeOf) {
		byte heapData[] = null;
		Pointer hostPtr = null;
		long ptrFlag = 0;

		if (enableHeapStorage && sizeOf < Integer.MAX_VALUE) {
			heapData = new byte[(int) sizeOf];
			hostPtr = Pointer.to(heapData);
			ptrFlag = CL.CL_MEM_USE_HOST_PTR;
		}

		cl_mem mem = CL.clCreateBuffer(getContext(),
				CL.CL_MEM_READ_WRITE + ptrFlag, sizeOf,
				hostPtr, null);

		if (heapData != null) this.heap.put(mem, heapData);
		return mem;
	}
}
