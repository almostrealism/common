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

/**
 * A kernel within a {@link CUModule}.
 *
 * <p>A kernel is valid only while its module is loaded, so it holds no native resource of
 * its own; {@link #isReleased()} reflects the module.</p>
 */
public class CUFunction extends CUObject {
	/** The module this kernel belongs to. */
	private final CUModule module;

	/** Wraps a kernel. Obtain instances with {@link CUModule#getFunction(String)}. */
	CUFunction(CUModule module, long nativePointer) {
		super(module.getContext(), nativePointer);
		this.module = module;
	}

	/** Returns the module this kernel belongs to. */
	public CUModule getModule() { return module; }

	@Override
	public boolean isReleased() { return super.isReleased() || module.isReleased(); }

	/**
	 * Returns the maximum block size this kernel can be launched with, which may be lower
	 * than the device maximum when the kernel uses many registers.
	 */
	public int getMaxThreadsPerBlock() {
		return CU.functionAttribute(getContextPointer(), getNativePointer(),
				CU.FUNC_ATTRIBUTE_MAX_THREADS_PER_BLOCK);
	}

	/**
	 * Enqueues this kernel on a stream with a one-dimensional grid.
	 *
	 * <p>The kernel receives, in order, the address of every buffer, the element offset
	 * of every argument, the element count of every argument, the number of work items and
	 * the index of the first work item.</p>
	 *
	 * @param stream       the stream to launch on
	 * @param gridSize     number of blocks
	 * @param blockSize    threads per block
	 * @param buffers      the buffer backing each argument
	 * @param offsets      the element offset of each argument within its buffer
	 * @param sizes        the element count of each argument
	 * @param globalCount  the number of work items
	 * @param globalOffset the index of the first work item
	 */
	public void launch(CUStream stream, int gridSize, int blockSize,
					   CUDeviceBuffer[] buffers, int[] offsets, int[] sizes,
					   long globalCount, long globalOffset) {
		long[] pointers = new long[buffers.length];
		for (int i = 0; i < buffers.length; i++) {
			pointers[i] = buffers[i].getNativePointer();
		}

		CU.launchKernel(getContextPointer(), getNativePointer(), gridSize, blockSize,
				pointers, offsets, sizes, globalCount, globalOffset, stream.getNativePointer());
	}
}
