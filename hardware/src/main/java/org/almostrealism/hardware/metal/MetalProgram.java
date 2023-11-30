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

import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.ctx.GlobalContextDebugFlags;

public class MetalProgram implements OperationInfo {
	private final OperationMetadata metadata;
	private final MTLDevice device;
	private final String func, src;

	private MTLFunction function;

	public MetalProgram(MTLDevice device, OperationMetadata metadata, String func, String src) {
		this.device = device;
		this.metadata = metadata;
		this.func = func;
		this.src = src;
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	public MTLDevice getDevice() { return device; }
	public MTLFunction getFunction() { return function; }

	public void compile() {
		if (GlobalContextDebugFlags.gate) {
			System.out.println("!");
		}

		function = device.newFunction(func, src);
		if (function.getNativePointer() == 0)
			throw new HardwareException("Failed to compile " + func);
	}

	public MTLComputePipelineState newComputePipelineState() {
		return device.newComputePipelineState(function);
	}

	public void destroy() {
		function.release();
		function = null;
	}

	public static MetalProgram create(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		return new MetalProgram(ctx.getMtlDevice(), metadata, func, src);
	}
}
