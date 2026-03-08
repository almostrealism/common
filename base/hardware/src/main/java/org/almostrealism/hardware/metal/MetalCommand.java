/*
 * Copyright 2025 Michael Murray
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

/**
 * Functional interface for executing Metal compute commands.
 *
 * <p>Provides access to pre-allocated offset/size buffers and the Metal command queue
 * for encoding and submitting GPU work.</p>
 *
 * <h2>Typical Usage</h2>
 *
 * <pre>{@code
 * MetalCommand cmd = (offset, size, queue) -> {
 *     MTLCommandBuffer cmdBuf = queue.commandBuffer();
 *     MTLComputeCommandEncoder encoder = cmdBuf.encoder();
 *
 *     // Set pipeline, buffers, and dispatch
 *     encoder.setComputePipelineState(kernel);
 *     encoder.setBuffer(0, dataBuffer);
 *     encoder.setBuffer(1, offset);  // Pre-allocated
 *     encoder.setBuffer(2, size);    // Pre-allocated
 *     encoder.dispatchThreads(...);
 *
 *     encoder.endEncoding();
 *     cmdBuf.commit();
 *     cmdBuf.waitUntilCompleted();
 * };
 * }</pre>
 *
 * @see MetalCommandRunner
 * @see MetalOperator
 */
@FunctionalInterface
public interface MetalCommand {
	void run(MTLBuffer offset, MTLBuffer size, MTLCommandQueue queue);
}
