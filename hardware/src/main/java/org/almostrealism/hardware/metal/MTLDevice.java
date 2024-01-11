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

import io.almostrealism.code.Precision;

public class MTLDevice extends MTLObject {
	public MTLDevice(long nativePointer) {
		super(nativePointer);
	}

	public int maxThreadgroupWidth() {
		return MTL.maxThreadgroupWidth(getNativePointer());
	}

	public int maxThreadgroupHeight() {
		return MTL.maxThreadgroupHeight(getNativePointer());
	}

	public int maxThreadgroupDepth() {
		return MTL.maxThreadgroupDepth(getNativePointer());
	}

	public MTLCommandQueue newCommandQueue() {
		return new MTLCommandQueue(MTL.createCommandQueue(getNativePointer()));
	}

	public MTLComputePipelineState newComputePipelineState(MTLFunction function) {
		return new MTLComputePipelineState(MTL.createComputePipelineState(getNativePointer(), function.getNativePointer()));
	}

	public MTLFunction newFunction(String func, String src) {
		return new MTLFunction(MTL.createFunction(getNativePointer(), func, src));
	}

	public MTLBuffer newIntBuffer32(long len) {
		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createIntBuffer32(getNativePointer(), len));
		} finally {
			MTLBuffer.ioTime.addEntry("newIntBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newIntBuffer32(int values[]) {
		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createIntBuffer32(getNativePointer(), values));
		} finally {
			MTLBuffer.ioTime.addEntry("newIntBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer16(long len) {
		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP16, MTL.createBuffer16(getNativePointer(), len));
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer16", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer16(float[] data) {
		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP16, MTL.createBuffer16(getNativePointer(), data));
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer16", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer32(long len) {
		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createBuffer32(getNativePointer(), len));
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer32(float[] data) {
		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createBuffer32(getNativePointer(), data));
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer32", System.nanoTime() - start);
		}
	}

	public void release() {
		MTL.releaseDevice(getNativePointer());
	}

	public static MTLDevice createSystemDefaultDevice() {
		return new MTLDevice(MTL.createSystemDefaultDevice());
	}
}
