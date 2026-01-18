/*
 * Copyright 2025 Michael Murray
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
 * Wrapper for Metal {@code id<MTLCommandQueue>}.
 *
 * <p>Creates {@link MTLCommandBuffer} instances for submitting GPU work.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * MTLCommandQueue queue = device.newCommandQueue();
 * MTLCommandBuffer cmdBuf = queue.commandBuffer();
 * // Encode commands...
 * cmdBuf.commit();
 * }</pre>
 *
 * @see MTLDevice
 * @see MTLCommandBuffer
 */
public class MTLCommandQueue extends MTLObject {
	private MTLDevice device;

	/**
	 * Creates a command queue wrapper for a native pointer.
	 *
	 * @param device The {@link MTLDevice} owning this queue
	 * @param nativePointer Native Metal command queue pointer
	 */
	public MTLCommandQueue(MTLDevice device, long nativePointer) {
		super(nativePointer);
		this.device = device;
	}

	/**
	 * Returns the Metal device that owns this queue.
	 *
	 * @return The {@link MTLDevice} instance
	 */
	public MTLDevice getDevice() { return device; }

	/**
	 * Creates a new command buffer for encoding GPU commands.
	 *
	 * <p>Command buffers are one-time-use and must be committed after encoding.</p>
	 *
	 * @return New {@link MTLCommandBuffer} instance
	 * @throws IllegalStateException if queue has been released
	 */
	public MTLCommandBuffer commandBuffer() {
		if (isReleased()) throw new IllegalStateException();

		return new MTLCommandBuffer(MTL.commandBuffer(getNativePointer()));
	}

	/**
	 * Releases this command queue and frees native Metal resources.
	 */
	@Override
	public void release() {
		if (!isReleased()) {
			MTL.releaseCommandQueue(getNativePointer());
			super.release();
		}
	}
}
