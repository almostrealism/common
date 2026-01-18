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
	/**
	 * Creates a compute pipeline state wrapper for a native pointer.
	 *
	 * @param nativePointer Native Metal compute pipeline state pointer
	 */
	public MTLComputePipelineState(long nativePointer) {
		super(nativePointer);
	}

	/**
	 * Returns the maximum total threads allowed per threadgroup for this kernel.
	 *
	 * <p>Hardware-dependent limit, typically 1024 on modern GPUs. Use this to
	 * calculate optimal threadgroup sizes.</p>
	 *
	 * @return Maximum threads per threadgroup
	 * @throws IllegalStateException if pipeline has been released
	 */
	public int maxTotalThreadsPerThreadgroup() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxTotalThreadsPerThreadgroup(getNativePointer());
	}

	/**
	 * Returns the SIMD width (warp size) for this kernel.
	 *
	 * <p>Threads execute in groups of this size (typically 32). For best performance,
	 * threadgroup sizes should be multiples of this value.</p>
	 *
	 * @return SIMD execution width
	 * @throws IllegalStateException if pipeline has been released
	 */
	public int threadExecutionWidth() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.threadExecutionWidth(getNativePointer());
	}

	/**
	 * Releases this pipeline state and frees native Metal resources.
	 */
	@Override
	public void release() {
		if (!isReleased()) {
			MTL.releaseComputePipelineState(getNativePointer());
			super.release();
		}
	}
}
