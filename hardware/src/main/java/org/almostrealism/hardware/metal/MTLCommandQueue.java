/*
 * Copyright 2024 Michael Murray
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

public class MTLCommandQueue extends MTLObject {
	private MTLDevice device;

	public MTLCommandQueue(MTLDevice device, long nativePointer) {
		super(nativePointer);
		this.device = device;
	}

	public MTLDevice getDevice() { return device; }

	public MTLCommandBuffer commandBuffer() {
		return new MTLCommandBuffer(MTL.commandBuffer(getNativePointer()));
	}

	public void release() {
		MTL.releaseCommandQueue(getNativePointer());
	}
}
