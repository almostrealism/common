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

import io.almostrealism.code.Precision;

/**
 * Wrapper for Metal {@code id<MTLDevice>}.
 *
 * <p>Provides access to Metal GPU device capabilities and creates command queues,
 * buffers, and compute pipeline states.</p>
 *
 * <h2>Device Creation</h2>
 *
 * <pre>{@code
 * MTLDevice device = MTLDevice.createSystemDefaultDevice();
 * }</pre>
 *
 * <h2>Threadgroup Capabilities</h2>
 *
 * <pre>{@code
 * int maxWidth = device.maxThreadgroupWidth();   // e.g., 1024
 * int maxHeight = device.maxThreadgroupHeight(); // e.g., 1024
 * int maxDepth = device.maxThreadgroupDepth();   // e.g., 64
 * }</pre>
 *
 * <h2>Resource Creation</h2>
 *
 * <pre>{@code
 * MTLCommandQueue queue = device.newCommandQueue();
 * MTLBuffer buffer = device.newBuffer32(1024);
 * MTLFunction func = device.newFunction("myKernel", mslSource);
 * MTLComputePipelineState pipeline = device.newComputePipelineState(func);
 * }</pre>
 *
 * @see MTLCommandQueue
 * @see MTLBuffer
 * @see MTLFunction
 * @see MTLComputePipelineState
 */
public class MTLDevice extends MTLObject {
	public MTLDevice(long nativePointer) {
		super(nativePointer);
	}

	public int maxThreadgroupWidth() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxThreadgroupWidth(getNativePointer());
	}

	public int maxThreadgroupHeight() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxThreadgroupHeight(getNativePointer());
	}

	public int maxThreadgroupDepth() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxThreadgroupDepth(getNativePointer());
	}

	public MTLCommandQueue newCommandQueue() {
		if (isReleased()) throw new IllegalStateException();
		return new MTLCommandQueue(this, MTL.createCommandQueue(getNativePointer()));
	}

	public MTLComputePipelineState newComputePipelineState(MTLFunction function) {
		if (isReleased()) throw new IllegalStateException();
		return new MTLComputePipelineState(MTL.createComputePipelineState(getNativePointer(), function.getNativePointer()));
	}

	public MTLFunction newFunction(String func, String src) {
		if (isReleased()) throw new IllegalStateException();
		return new MTLFunction(MTL.createFunction(getNativePointer(), func, src));
	}

	public MTLBuffer newIntBuffer32(long len) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createIntBuffer32(getNativePointer(), len), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newIntBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newIntBuffer32(int values[]) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createIntBuffer32(getNativePointer(), values), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newIntBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer16(long len) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP16, MTL.createBuffer16(getNativePointer(), len), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer16", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer16(float[] data) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP16, MTL.createBuffer16(getNativePointer(), data), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer16", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer32(long len) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createBuffer32(getNativePointer(), len), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newSharedBuffer32(String fileName, long len) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32,
					MTL.createSharedBuffer32(getNativePointer(), fileName, Math.toIntExact(len)), true);
		} finally {
			MTLBuffer.ioTime.addEntry("newSharedBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newSharedBuffer32(String fileName, float data[]) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32,
					MTL.createSharedBuffer32(getNativePointer(), fileName, data), true);
		} finally {
			MTLBuffer.ioTime.addEntry("newSharedBuffer32", System.nanoTime() - start);
		}
	}

	public MTLBuffer newBuffer32(float[] data) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createBuffer32(getNativePointer(), data), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer32", System.nanoTime() - start);
		}
	}

	@Override
	public void release() {
		if (!isReleased()) {
			MTL.releaseDevice(getNativePointer());
			super.release();
		}
	}

	public static MTLDevice createSystemDefaultDevice() {
		return new MTLDevice(MTL.createSystemDefaultDevice());
	}
}
