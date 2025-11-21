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
 * Wrapper for Metal {@code id<MTLFunction>}.
 *
 * <p>Represents a compiled Metal Shading Language (MSL) kernel function.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * MTLFunction func = device.newFunction("myKernel", mslSource);
 * MTLComputePipelineState pipeline = device.newComputePipelineState(func);
 * }</pre>
 *
 * @see MTLDevice
 * @see MTLComputePipelineState
 * @see MetalProgram
 */
public class MTLFunction extends MTLObject {
	/**
	 * Creates a Metal function wrapper for a native pointer.
	 *
	 * @param nativePointer Native Metal function pointer
	 */
	public MTLFunction(long nativePointer) {
		super(nativePointer);
	}
}
