/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.metal;

/**
 * Wrapper for Metal {@code id<MTLComputeCommandEncoder>}.
 *
 * <p>Encodes compute kernel dispatch commands into a {@link MTLCommandBuffer}.</p>
 *
 * <h2>Encoding Workflow</h2>
 *
 * <pre>{@code
 * MTLComputeCommandEncoder encoder = cmdBuf.encoder();
 *
 * // Set pipeline and buffers
 * encoder.setComputePipelineState(pipeline);
 * encoder.setBuffer(0, inputBuffer);
 * encoder.setBuffer(1, outputBuffer);
 *
 * // Dispatch threadgroups
 * encoder.dispatchThreadgroups(
 *     256, 1, 1,  // Threadgroup size
 *     4, 1, 1     // Grid size (4 threadgroups)
 * );
 *
 * encoder.endEncoding();
 * }</pre>
 *
 * <h2>Dispatch Methods</h2>
 *
 * <pre>{@code
 * // dispatchThreads: Specify total thread count, Metal calculates threadgroups
 * encoder.dispatchThreads(groupW, groupH, groupD, totalW, totalH, totalD);
 *
 * // dispatchThreadgroups: Specify threadgroup count explicitly
 * encoder.dispatchThreadgroups(groupW, groupH, groupD, gridW, gridH, gridD);
 * }</pre>
 *
 * @see MTLCommandBuffer
 * @see MTLComputePipelineState
 */
public class MTLComputeCommandEncoder extends MTLObject {
	/**
	 * Creates an MTLComputeCommandEncoder wrapper for a native encoder pointer.
	 *
	 * @param nativePointer Native Metal compute command encoder pointer
	 */
	public MTLComputeCommandEncoder(long nativePointer) {
		super(nativePointer);
	}

	/**
	 * Sets the compute pipeline state for subsequent dispatch commands.
	 *
	 * <p>Specifies which compiled kernel to execute when dispatching work.</p>
	 *
	 * @param pipeline The {@link MTLComputePipelineState} containing the compiled kernel
	 */
	public void setComputePipelineState(MTLComputePipelineState pipeline) {
		MTL.setComputePipelineState(getNativePointer(), pipeline.getNativePointer());
	}

	/**
	 * Binds a buffer to a kernel argument slot.
	 *
	 * <p>The buffer will be accessible to the kernel at the specified argument index.</p>
	 *
	 * @param index Kernel argument index (0-based)
	 * @param buffer The {@link MTLBuffer} to bind
	 */
	public void setBuffer(int index, MTLBuffer buffer) {
		MTL.setBuffer(getNativePointer(), index, buffer.getNativePointer());
	}

	/**
	 * Dispatches a 1D grid of threadgroups.
	 *
	 * <p>Convenience method that sets height and depth to 1.</p>
	 *
	 * @param groupWidth Threads per threadgroup in X dimension
	 * @param gridWidth Number of threadgroups in X dimension
	 */
	public void dispatchThreadgroups(int groupWidth, int gridWidth) {
		dispatchThreadgroups(groupWidth, 1, 1, gridWidth, 1, 1);
	}

	/**
	 * Dispatches a 2D grid of threadgroups.
	 *
	 * <p>Convenience method that sets depth to 1.</p>
	 *
	 * @param groupWidth Threads per threadgroup in X dimension
	 * @param groupHeight Threads per threadgroup in Y dimension
	 * @param gridWidth Number of threadgroups in X dimension
	 * @param gridHeight Number of threadgroups in Y dimension
	 */
	public void dispatchThreadgroups(int groupWidth, int groupHeight,
									 int gridWidth, int gridHeight) {
		dispatchThreadgroups(groupWidth, groupHeight, 1, gridWidth, gridHeight, 1);
	}

	/**
	 * Dispatches a 3D grid of threadgroups.
	 *
	 * <p>Explicitly specifies the number of threadgroups to execute. Total thread
	 * count is {@code (groupWidth x groupHeight x groupDepth) x (gridWidth x gridHeight x gridDepth)}.</p>
	 *
	 * @param groupWidth Threads per threadgroup in X dimension
	 * @param groupHeight Threads per threadgroup in Y dimension
	 * @param groupDepth Threads per threadgroup in Z dimension
	 * @param gridWidth Number of threadgroups in X dimension
	 * @param gridHeight Number of threadgroups in Y dimension
	 * @param gridDepth Number of threadgroups in Z dimension
	 */
	public void dispatchThreadgroups(int groupWidth, int groupHeight, int groupDepth,
									int gridWidth, int gridHeight, int gridDepth) {
		MTL.dispatchThreadgroups(getNativePointer(), groupWidth, groupHeight, groupDepth,
								 gridWidth, gridHeight, gridDepth);
	}

	/**
	 * Dispatches threads with Metal automatically calculating threadgroup count.
	 *
	 * <p>Specifies total thread count ({@code gridWidth x gridHeight x gridDepth}) and
	 * threadgroup size. Metal divides the work into appropriate threadgroups.</p>
	 *
	 * @param groupWidth Threads per threadgroup in X dimension
	 * @param groupHeight Threads per threadgroup in Y dimension
	 * @param groupDepth Threads per threadgroup in Z dimension
	 * @param gridWidth Total threads in X dimension
	 * @param gridHeight Total threads in Y dimension
	 * @param gridDepth Total threads in Z dimension
	 */
	public void dispatchThreads(int groupWidth, int groupHeight, int groupDepth,
									 int gridWidth, int gridHeight, int gridDepth) {
		MTL.dispatchThreads(getNativePointer(), groupWidth, groupHeight, groupDepth,
				gridWidth, gridHeight, gridDepth);
	}

	/**
	 * Finalizes encoding and prepares the command buffer for execution.
	 *
	 * <p>Must be called after all dispatch and buffer binding commands. After calling
	 * endEncoding, this encoder cannot be used for further commands.</p>
	 */
	public void endEncoding() {
		MTL.endEncoding(getNativePointer());
	}
}
