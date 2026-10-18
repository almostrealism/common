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
	 * <p>The launch geometry and every argument range are validated before crossing JNI:
	 * the generated kernel addresses each buffer by its offset and size, so an argument that
	 * does not lie within its allocation would let the kernel access memory outside it.</p>
	 *
	 * @param stream       the stream to launch on
	 * @param gridSize     number of blocks
	 * @param blockSize    threads per block
	 * @param buffers      the buffer backing each argument
	 * @param offsets      the element offset of each argument within its buffer
	 * @param sizes        the element count of each argument
	 * @param elementBytes the size in bytes of one element of every argument
	 * @param globalCount  the number of work items
	 * @param globalOffset the index of the first work item
	 * @throws IllegalArgumentException  if the geometry, the element size or the argument
	 *                                   arrays are invalid, or if {@code globalOffset + globalCount}
	 *                                   would overflow {@code long}
	 * @throws IndexOutOfBoundsException if an argument range falls outside its buffer
	 */
	public void launch(CUStream stream, int gridSize, int blockSize,
					   CUDeviceBuffer[] buffers, int[] offsets, int[] sizes, int elementBytes,
					   long globalCount, long globalOffset) {
		if (gridSize <= 0 || blockSize <= 0 || globalCount < 0 || globalOffset < 0) {
			throw new IllegalArgumentException("Invalid launch geometry: grid " + gridSize +
					", block " + blockSize + ", " + globalCount + " work items from " + globalOffset);
		}

		if (globalCount > Long.MAX_VALUE - globalOffset) {
			throw new IllegalArgumentException("Work range overflows: " + globalCount +
					" work items from " + globalOffset);
		}

		if (elementBytes <= 0 || offsets.length != buffers.length || sizes.length != buffers.length) {
			throw new IllegalArgumentException("Launch requires a positive element size and one " +
					"offset and one size per buffer");
		}

		long[] pointers = new long[buffers.length];
		for (int i = 0; i < buffers.length; i++) {
			buffers[i].checkRange((long) offsets[i] * elementBytes, (long) sizes[i] * elementBytes);
			pointers[i] = buffers[i].getNativePointer();
		}

		CU.launchKernel(getContextPointer(), getNativePointer(), gridSize, blockSize,
				pointers, offsets, sizes, globalCount, globalOffset, stream.getNativePointer());
	}

	/**
	 * Returns the number of blocks of {@code blockSize} threads needed to cover
	 * {@code workItems} work items. The ceiling is computed without forming
	 * {@code workItems + blockSize - 1}, which overflows for a work size near
	 * {@link Long#MAX_VALUE} and would yield a negative grid.
	 *
	 * @throws IllegalArgumentException if {@code workItems} is negative or {@code blockSize}
	 *                                  is not positive
	 */
	public static long gridSize(long workItems, int blockSize) {
		if (workItems < 0 || blockSize <= 0) {
			throw new IllegalArgumentException("Cannot cover " + workItems +
					" work items with blocks of " + blockSize);
		}

		return workItems / blockSize + (workItems % blockSize == 0 ? 0 : 1);
	}
}
