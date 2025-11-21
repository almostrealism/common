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
 * Wrapper for Metal {@code id<MTLCommandBuffer>}.
 *
 * <p>Manages command encoding, submission, and synchronization for GPU work.</p>
 *
 * <h2>Typical Workflow</h2>
 *
 * <pre>{@code
 * MTLCommandBuffer cmdBuf = queue.commandBuffer();
 * MTLComputeCommandEncoder encoder = cmdBuf.encoder();
 *
 * // Encode compute commands
 * encoder.setComputePipelineState(pipeline);
 * encoder.setBuffer(0, buffer);
 * encoder.dispatchThreads(...);
 * encoder.endEncoding();
 *
 * // Submit and wait
 * cmdBuf.commit();
 * cmdBuf.waitUntilCompleted();
 * }</pre>
 *
 * @see MTLCommandQueue
 * @see MTLComputeCommandEncoder
 */
public class MTLCommandBuffer extends MTLObject {
	/**
	 * Creates a command buffer wrapper for a native pointer.
	 *
	 * @param nativePointer Native Metal command buffer pointer
	 */
	public MTLCommandBuffer(long nativePointer) {
		super(nativePointer);
	}

	/**
	 * Creates a compute command encoder for encoding GPU compute commands.
	 *
	 * @return New {@link MTLComputeCommandEncoder} instance
	 */
	public MTLComputeCommandEncoder encoder() {
		return new MTLComputeCommandEncoder(MTL.computeCommandEncoder(getNativePointer()));
	}

	/**
	 * Commits this command buffer for execution on the GPU.
	 *
	 * <p>Once committed, the buffer cannot be modified. Use {@link #waitUntilCompleted()}
	 * to block until execution finishes.</p>
	 */
	public void commit() {
		MTL.commitCommandBuffer(getNativePointer());
	}

	/**
	 * Blocks the calling thread until all GPU commands have completed.
	 *
	 * <p>Must be called after {@link #commit()}. Synchronizes CPU with GPU execution.</p>
	 */
	public void waitUntilCompleted() {
		MTL.waitUntilCompleted(getNativePointer());
	}
}
