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
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.ctx.GlobalContextDebugFlags;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MetalProgram implements OperationInfo, ConsoleFeatures {
	public static boolean enableLargeProgramMonitoring = true;

	private static int monitorOutputCount;

	private final OperationMetadata metadata;
	private final MTLDevice device;
	private final String func, src;

	private MTLFunction function;

	public MetalProgram(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		this.device = ctx.getMtlDevice();
		this.metadata = (metadata == null ?
				new OperationMetadata(null, null) : metadata)
				.withContextName(ctx.getDataContext().getName());
		this.func = func;
		this.src = src;
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	public MTLDevice getDevice() { return device; }
	public MTLFunction getFunction() { return function; }

	public void compile() {
		if (enableLargeProgramMonitoring && src.length() > 5000) {
			String name = "large_mtl_instruction_set_" + (monitorOutputCount++) + ".c";

			try {
				Files.writeString(Path.of("results/" + name), src);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}

			log("Wrote " + name);
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

	@Override
	public Console console() { return Hardware.console; }

	public static MetalProgram create(MetalComputeContext ctx, OperationMetadata metadata, String func, String src) {
		return new MetalProgram(ctx, metadata, func, src);
	}
}
