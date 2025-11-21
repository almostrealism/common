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
	/**
	 * Creates an MTLDevice wrapper for a native Metal device pointer.
	 *
	 * @param nativePointer Native Metal device pointer
	 */
	public MTLDevice(long nativePointer) {
		super(nativePointer);
	}

	/**
	 * Returns the maximum threadgroup width for this device.
	 *
	 * @return Maximum width dimension for threadgroups
	 * @throws IllegalStateException if device has been released
	 */
	public int maxThreadgroupWidth() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxThreadgroupWidth(getNativePointer());
	}

	/**
	 * Returns the maximum threadgroup height for this device.
	 *
	 * @return Maximum height dimension for threadgroups
	 * @throws IllegalStateException if device has been released
	 */
	public int maxThreadgroupHeight() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxThreadgroupHeight(getNativePointer());
	}

	/**
	 * Returns the maximum threadgroup depth for this device.
	 *
	 * @return Maximum depth dimension for threadgroups
	 * @throws IllegalStateException if device has been released
	 */
	public int maxThreadgroupDepth() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.maxThreadgroupDepth(getNativePointer());
	}

	/**
	 * Creates a new command queue for submitting work to this device.
	 *
	 * @return New {@link MTLCommandQueue}
	 * @throws IllegalStateException if device has been released
	 */
	public MTLCommandQueue newCommandQueue() {
		if (isReleased()) throw new IllegalStateException();
		return new MTLCommandQueue(this, MTL.createCommandQueue(getNativePointer()));
	}

	/**
	 * Creates a compute pipeline state from a Metal function.
	 *
	 * @param function The Metal function to create pipeline from
	 * @return New {@link MTLComputePipelineState}
	 * @throws IllegalStateException if device has been released
	 */
	public MTLComputePipelineState newComputePipelineState(MTLFunction function) {
		if (isReleased()) throw new IllegalStateException();
		return new MTLComputePipelineState(MTL.createComputePipelineState(getNativePointer(), function.getNativePointer()));
	}

	/**
	 * Creates a Metal function from Metal Shading Language source code.
	 *
	 * @param func Function name in the source code
	 * @param src Metal Shading Language source code
	 * @return New {@link MTLFunction}
	 * @throws IllegalStateException if device has been released
	 */
	public MTLFunction newFunction(String func, String src) {
		if (isReleased()) throw new IllegalStateException();
		return new MTLFunction(MTL.createFunction(getNativePointer(), func, src));
	}

	/**
	 * Creates an empty 32-bit integer buffer with the specified length.
	 *
	 * @param len Buffer length in elements
	 * @return New {@link MTLBuffer} for integers
	 * @throws IllegalStateException if device has been released
	 */
	public MTLBuffer newIntBuffer32(long len) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createIntBuffer32(getNativePointer(), len), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newIntBuffer32", System.nanoTime() - start);
		}
	}

	/**
	 * Creates a 32-bit integer buffer initialized with the provided values.
	 *
	 * @param values Integer array data to copy into buffer
	 * @return New {@link MTLBuffer} for integers
	 * @throws IllegalStateException if device has been released
	 */
	public MTLBuffer newIntBuffer32(int values[]) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createIntBuffer32(getNativePointer(), values), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newIntBuffer32", System.nanoTime() - start);
		}
	}

	/**
	 * Creates an empty 16-bit float (half precision) buffer with the specified length.
	 *
	 * @param len Buffer length in elements
	 * @return New {@link MTLBuffer} for half-precision floats
	 * @throws IllegalStateException if device has been released
	 */
	public MTLBuffer newBuffer16(long len) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP16, MTL.createBuffer16(getNativePointer(), len), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer16", System.nanoTime() - start);
		}
	}

	/**
	 * Creates a 16-bit float (half precision) buffer initialized with the provided values.
	 *
	 * @param data Float array data to copy and convert to half precision
	 * @return New {@link MTLBuffer} for half-precision floats
	 * @throws IllegalStateException if device has been released
	 */
	public MTLBuffer newBuffer16(float[] data) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP16, MTL.createBuffer16(getNativePointer(), data), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer16", System.nanoTime() - start);
		}
	}

	/**
	 * Creates an empty 32-bit float (single precision) buffer with the specified length.
	 *
	 * @param len Buffer length in elements
	 * @return New {@link MTLBuffer} for single-precision floats
	 * @throws IllegalStateException if device has been released
	 */
	public MTLBuffer newBuffer32(long len) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createBuffer32(getNativePointer(), len), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer32", System.nanoTime() - start);
		}
	}

	/**
	 * Creates an empty 32-bit float shared buffer backed by a memory-mapped file.
	 *
	 * @param fileName Path to the backing file
	 * @param len Buffer length in elements
	 * @return New shared {@link MTLBuffer}
	 * @throws IllegalStateException if device has been released
	 */
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

	/**
	 * Creates a 32-bit float shared buffer backed by a memory-mapped file with initial data.
	 *
	 * @param fileName Path to the backing file
	 * @param data Float array data to write to the buffer
	 * @return New shared {@link MTLBuffer}
	 * @throws IllegalStateException if device has been released
	 */
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

	/**
	 * Creates a 32-bit float (single precision) buffer initialized with the provided values.
	 *
	 * @param data Float array data to copy into buffer
	 * @return New {@link MTLBuffer} for single-precision floats
	 * @throws IllegalStateException if device has been released
	 */
	public MTLBuffer newBuffer32(float[] data) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			return new MTLBuffer(Precision.FP32, MTL.createBuffer32(getNativePointer(), data), false);
		} finally {
			MTLBuffer.ioTime.addEntry("newBuffer32", System.nanoTime() - start);
		}
	}

	/**
	 * Releases this device and frees its native resources.
	 */
	@Override
	public void release() {
		if (!isReleased()) {
			MTL.releaseDevice(getNativePointer());
			super.release();
		}
	}

	/**
	 * Creates and returns the system's default Metal device.
	 *
	 * @return New {@link MTLDevice} for the default GPU
	 */
	public static MTLDevice createSystemDefaultDevice() {
		return new MTLDevice(MTL.createSystemDefaultDevice());
	}
}
