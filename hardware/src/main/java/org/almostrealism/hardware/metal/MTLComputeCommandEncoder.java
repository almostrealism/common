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

public class MTLComputeCommandEncoder extends MTLObject {
	public MTLComputeCommandEncoder(long nativePointer) {
		super(nativePointer);
	}

	public void setComputePipelineState(MTLComputePipelineState pipeline) {
		MTL.setComputePipelineState(getNativePointer(), pipeline.getNativePointer());
	}

	public void setBuffer(int index, MTLBuffer buffer) {
		MTL.setBuffer(getNativePointer(), index, buffer.getNativePointer());
	}

	public void dispatchThreadgroups(int groupWidth, int gridWidth) {
		dispatchThreadgroups(groupWidth, 1, 1, gridWidth, 1, 1);
	}

	public void dispatchThreadgroups(int groupWidth, int groupHeight,
									 int gridWidth, int gridHeight) {
		dispatchThreadgroups(groupWidth, groupHeight, 1, gridWidth, gridHeight, 1);
	}

	public void dispatchThreadgroups(int groupWidth, int groupHeight, int groupDepth,
									int gridWidth, int gridHeight, int gridDepth) {
		MTL.dispatchThreadgroups(getNativePointer(), groupWidth, groupHeight, groupDepth,
								 gridWidth, gridHeight, gridDepth);

	}

	public void endEncoding() {
		MTL.endEncoding(getNativePointer());
	}
}
