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
 * Wrapper for Metal {@code id<MTLComputePipelineState>}.
 *
 * <p>Represents a compiled compute kernel ready for execution, providing threadgroup
 * size limits and SIMD execution width.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * MTLComputePipelineState pipeline = device.newComputePipelineState(function);
 *
 * // Query kernel limits
 * int maxThreads = pipeline.maxTotalThreadsPerThreadgroup(); // e.g., 1024
 * int simdWidth = pipeline.threadExecutionWidth();           // e.g., 32
 *
 * // Use in encoder
 * encoder.setComputePipelineState(pipeline);
 * }</pre>
 *
 * @see MTLFunction
 * @see MTLDevice
 * @see MTLComputeCommandEncoder
 */
public class MTLComputePipelineState extends MTLObject {
	public MTLComputePipelineState(long nativePointer) {
		super(nativePointer);
	}

	public int maxTotalThreadsPerThreadgroup() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxTotalThreadsPerThreadgroup(getNativePointer());
	}

	public int threadExecutionWidth() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.threadExecutionWidth(getNativePointer());
	}

	@Override
	public void release() {
		if (!isReleased()) {
			MTL.releaseComputePipelineState(getNativePointer());
			super.release();
		}
	}
}
