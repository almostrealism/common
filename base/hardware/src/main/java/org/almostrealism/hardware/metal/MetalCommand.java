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
 * Functional interface for encoding a Metal compute command into a command buffer.
 *
 * <p>Implementations <em>encode only</em> — they create a {@link MTLComputeCommandEncoder}
 * on the supplied {@link MTLCommandBuffer}, set the pipeline/buffers, dispatch, and
 * {@code endEncoding()}. They must <strong>not</strong> commit or wait: the
 * {@link MetalCommandRunner} owns the command buffer lifecycle so that many commands can be
 * batched into a single buffer (one encoder each, so Metal's cross-encoder hazard tracking
 * orders dependent kernels) and committed once.</p>
 *
 * <h2>Typical Usage</h2>
 *
 * <pre>{@code
 * MetalCommand cmd = cmdBuf -> {
 *     MTLComputeCommandEncoder encoder = cmdBuf.encoder();
 *     encoder.setComputePipelineState(kernel);
 *     encoder.setBuffer(0, dataBuffer);
 *     encoder.setBytes(1, offsets);  // inline, captured at encode time
 *     encoder.setBytes(2, sizes);    // inline, captured at encode time
 *     encoder.dispatchThreads(...);
 *     encoder.endEncoding();
 *     // No commit / waitUntilCompleted here — the runner commits the (possibly batched) buffer.
 * };
 * }</pre>
 *
 * @see MetalCommandRunner
 * @see MetalOperator
 */
@FunctionalInterface
public interface MetalCommand {
	/**
	 * Encodes this Metal compute command into the supplied command buffer. Per-dispatch scalar
	 * arguments (offsets, sizes) are bound inline via
	 * {@link MTLComputeCommandEncoder#setBytes(int, int[])} so that batched commands do not share
	 * mutable argument buffers.
	 *
	 * @param cmdBuf The command buffer to encode into (committed later by the runner)
	 */
	void encode(MTLCommandBuffer cmdBuf);
}
