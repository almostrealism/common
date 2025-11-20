/*
 * Copyright 2023 Michael Murray
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.util.ResourceBundle;

/**
 * JNI bridge to native Metal framework (libMTL.dylib).
 *
 * <p>Provides static native methods for Metal device, buffer, command queue, and kernel operations.
 * Loads the native library from classpath resources on initialization.</p>
 *
 * <h2>Library Loading</h2>
 *
 * <pre>{@code
 * // Automatically extracts libMTL.dylib to temp directory and loads it
 * // No manual initialization required
 * }</pre>
 *
 * <h2>Device Operations</h2>
 *
 * <pre>{@code
 * long device = MTL.createSystemDefaultDevice();
 * int maxWidth = MTL.maxThreadgroupWidth(device);
 * }</pre>
 *
 * <h2>Buffer Operations</h2>
 *
 * <pre>{@code
 * long buffer = MTL.createBuffer32(device, 1024);
 * MTL.setBufferContents32(buffer, floatBuffer, 0, length);
 * MTL.getBufferContents32(buffer, outputBuffer, 0, length);
 * MTL.releaseBuffer(buffer);
 * }</pre>
 *
 * @see MTLDevice
 * @see MTLBuffer
 * @see MTLCommandQueue
 */
public class MTL {
	static {
		System.getProperty("java.io.tmpdir");
		InputStream is = MTL.class.getClassLoader().getResourceAsStream("libMTL.dylib");

		File tempDir = new File(System.getProperty("java.io.tmpdir"));
		tempDir.mkdir();

		File tempLibFile = new File(tempDir, "libMTL.dylib");
		try {
			if (tempLibFile.exists()) Files.delete(tempLibFile.toPath());
			Files.copy(is, tempLibFile.toPath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		System.load(tempLibFile.getAbsolutePath());
	}

	/**
	 * Creates the system's default Metal device.
	 *
	 * @return Native pointer to the default Metal device
	 */
	public static native long createSystemDefaultDevice();

	/**
	 * Returns the maximum threadgroup width for the device.
	 *
	 * @param device Native device pointer
	 * @return Maximum width dimension for threadgroups
	 */
	public static native int maxThreadgroupWidth(long device);

	/**
	 * Returns the maximum threadgroup height for the device.
	 *
	 * @param device Native device pointer
	 * @return Maximum height dimension for threadgroups
	 */
	public static native int maxThreadgroupHeight(long device);

	/**
	 * Returns the maximum threadgroup depth for the device.
	 *
	 * @param device Native device pointer
	 * @return Maximum depth dimension for threadgroups
	 */
	public static native int maxThreadgroupDepth(long device);

	/**
	 * Creates a Metal function from Metal Shading Language source code.
	 *
	 * @param device Native device pointer
	 * @param func Function name in the source code
	 * @param source Metal Shading Language source code
	 * @return Native pointer to the compiled function
	 */
	public static native long createFunction(long device, String func, String source);

	/**
	 * Creates a compute pipeline state from a Metal function.
	 *
	 * @param device Native device pointer
	 * @param function Native function pointer
	 * @return Native pointer to the pipeline state
	 */
	public static native long createComputePipelineState(long device, long function);

	/**
	 * Returns the maximum total threads per threadgroup for the pipeline.
	 *
	 * @param pipelineState Native pipeline state pointer
	 * @return Maximum total threads per threadgroup
	 */
	public static native int maxTotalThreadsPerThreadgroup(long pipelineState);

	/**
	 * Returns the thread execution width for the pipeline.
	 *
	 * @param pipelineState Native pipeline state pointer
	 * @return Thread execution width (SIMD width)
	 */
	public static native int threadExecutionWidth(long pipelineState);

	/**
	 * Creates a command queue for the device.
	 *
	 * @param device Native device pointer
	 * @return Native pointer to the command queue
	 */
	public static native long createCommandQueue(long device);

	/**
	 * Creates a command buffer from the command queue.
	 *
	 * @param commandQueue Native command queue pointer
	 * @return Native pointer to the command buffer
	 */
	public static native long commandBuffer(long commandQueue);

	/**
	 * Creates a compute command encoder from the command buffer.
	 *
	 * @param commandBuffer Native command buffer pointer
	 * @return Native pointer to the compute command encoder
	 */
	public static native long computeCommandEncoder(long commandBuffer);

	/**
	 * Creates an integer buffer (32-bit) from array data.
	 *
	 * @param device Native device pointer
	 * @param data Integer array data to copy into buffer
	 * @return Native pointer to the Metal buffer
	 */
	public static long createIntBuffer32(long device, int[] data) {
		return createIntBuffer32(device, data, data.length);
	}

	/**
	 * Creates an empty integer buffer (32-bit) with specified length.
	 *
	 * @param device Native device pointer
	 * @param len Buffer length in elements
	 * @return Native pointer to the Metal buffer
	 */
	public static long createIntBuffer32(long device, long len) {
		return createIntBuffer32(device, null, len);
	}

	/**
	 * Creates an integer buffer (32-bit) with optional initial data.
	 *
	 * @param device Native device pointer
	 * @param data Integer array data to copy (null for empty buffer)
	 * @param len Buffer length in elements
	 * @return Native pointer to the Metal buffer
	 */
	public static native long createIntBuffer32(long device, int[] data, long len);

	/**
	 * Creates an empty float buffer (16-bit/half precision) with specified length.
	 *
	 * @param device Native device pointer
	 * @param len Buffer length in elements
	 * @return Native pointer to the Metal buffer
	 */
	public static long createBuffer16(long device, long len) {
		return createBuffer16(device, null, len);
	}

	/**
	 * Creates a float buffer (16-bit/half precision) from array data.
	 *
	 * @param device Native device pointer
	 * @param data Float array data to copy and convert to half precision
	 * @return Native pointer to the Metal buffer
	 */
	public static long createBuffer16(long device, float[] data) {
		return createBuffer16(device, data, data.length);
	}

	/**
	 * Creates an empty float buffer (32-bit/single precision) with specified length.
	 *
	 * @param device Native device pointer
	 * @param len Buffer length in elements
	 * @return Native pointer to the Metal buffer
	 */
	public static long createBuffer32(long device, long len) {
		return createBuffer32(device, null, len);
	}

	/**
	 * Creates a float buffer (32-bit/single precision) from array data.
	 *
	 * @param device Native device pointer
	 * @param data Float array data to copy into buffer
	 * @return Native pointer to the Metal buffer
	 */
	public static long createBuffer32(long device, float[] data) {
		return createBuffer32(device, data, data.length);
	}

	/**
	 * Creates a float buffer (16-bit/half precision) with optional initial data.
	 *
	 * @param device Native device pointer
	 * @param data Float array data to copy (null for empty buffer)
	 * @param len Buffer length in elements
	 * @return Native pointer to the Metal buffer
	 */
	public static native long createBuffer16(long device, float[] data, long len);

	/**
	 * Creates a float buffer (32-bit/single precision) with optional initial data.
	 *
	 * @param device Native device pointer
	 * @param data Float array data to copy (null for empty buffer)
	 * @param len Buffer length in elements
	 * @return Native pointer to the Metal buffer
	 */
	public static native long createBuffer32(long device, float[] data, long len);

	/**
	 * Creates a shared float buffer (32-bit) backed by a memory-mapped file.
	 *
	 * @param device Native device pointer
	 * @param filePath Path to the backing file
	 * @param data Initial float array data to write
	 * @return Native pointer to the shared Metal buffer
	 */
	public static long createSharedBuffer32(long device, String filePath, float[] data) {
		return createSharedBuffer32(device, filePath, data, data.length);
	}

	/**
	 * Creates an empty shared float buffer (32-bit) backed by a memory-mapped file.
	 *
	 * @param device Native device pointer
	 * @param filePath Path to the backing file
	 * @param len Buffer length in elements
	 * @return Native pointer to the shared Metal buffer
	 */
	public static long createSharedBuffer32(long device, String filePath, int len) {
		return createSharedBuffer32(device, filePath, null, len);
	}

	/**
	 * Creates a shared float buffer (32-bit) backed by a memory-mapped file with optional initial data.
	 *
	 * @param device Native device pointer
	 * @param filePath Path to the backing file
	 * @param data Initial float array data to write (null for empty buffer)
	 * @param len Buffer length in elements
	 * @return Native pointer to the shared Metal buffer
	 */
	public static native long createSharedBuffer32(long device, String filePath, float[] data, int len);

	/**
	 * Returns the native memory pointer for the buffer's contents.
	 *
	 * @param buffer Native buffer pointer
	 * @return Native content pointer
	 */
	public static native long getContentPointer(long buffer);

	/**
	 * Sets buffer contents from a FloatBuffer (16-bit/half precision).
	 *
	 * @param buffer Native buffer pointer
	 * @param in Source FloatBuffer (converted to half precision)
	 * @param offset Starting offset in elements
	 * @param length Number of elements to copy
	 */
	public static native void setBufferContents16(long buffer, FloatBuffer in, int offset, int length);

	/**
	 * Sets buffer contents from a FloatBuffer (32-bit/single precision) asynchronously.
	 *
	 * @param buffer Native buffer pointer
	 * @param in Source FloatBuffer
	 * @param offset Starting offset in elements
	 * @param length Number of elements to copy
	 */
	public static void setBufferContents32(long buffer, FloatBuffer in, int offset, int length) {
		setBufferContents32(buffer, in, offset, length, false);
	}

	/**
	 * Sets buffer contents from a FloatBuffer (32-bit/single precision).
	 *
	 * @param buffer Native buffer pointer
	 * @param in Source FloatBuffer
	 * @param offset Starting offset in elements
	 * @param length Number of elements to copy
	 * @param sync If true, synchronizes before returning
	 */
	public static native void setBufferContents32(long buffer, FloatBuffer in, int offset, int length, boolean sync);

	/**
	 * Sets integer buffer contents from an IntBuffer.
	 *
	 * @param buffer Native buffer pointer
	 * @param in Source IntBuffer
	 * @param offset Starting offset in elements
	 * @param length Number of elements to copy
	 */
	public static native void setIntBufferContents32(long buffer, IntBuffer in, int offset, int length);

	/**
	 * Gets buffer contents into a FloatBuffer (16-bit/half precision).
	 *
	 * @param buffer Native buffer pointer
	 * @param out Destination FloatBuffer (converted from half precision)
	 * @param offset Starting offset in elements
	 * @param length Number of elements to copy
	 */
	public static native void getBufferContents16(long buffer, FloatBuffer out, int offset, int length);

	/**
	 * Gets buffer contents into a FloatBuffer (32-bit/single precision).
	 *
	 * @param buffer Native buffer pointer
	 * @param out Destination FloatBuffer
	 * @param offset Starting offset in elements
	 * @param length Number of elements to copy
	 */
	public static native void getBufferContents32(long buffer, FloatBuffer out, int offset, int length);

	/**
	 * Gets integer buffer contents into an IntBuffer.
	 *
	 * @param buffer Native buffer pointer
	 * @param out Destination IntBuffer
	 * @param offset Starting offset in elements
	 * @param length Number of elements to copy
	 */
	public static native void getIntBufferContents32(long buffer, IntBuffer out, int offset, int length);

	/**
	 * Returns the length of the buffer in bytes.
	 *
	 * @param buffer Native buffer pointer
	 * @return Buffer length in bytes
	 */
	public static native long bufferLength(long buffer);

	/**
	 * Sets the compute pipeline state for the command encoder.
	 *
	 * @param commandEncoder Native command encoder pointer
	 * @param pipeline Native pipeline state pointer
	 */
	public static native void setComputePipelineState(long commandEncoder, long pipeline);

	/**
	 * Binds a buffer to the command encoder at the specified index.
	 *
	 * @param commandEncoder Native command encoder pointer
	 * @param index Buffer binding index
	 * @param buffer Native buffer pointer
	 */
	public static native void setBuffer(long commandEncoder, int index, long buffer);

	/**
	 * Dispatches compute threads with explicit threadgroup and grid dimensions.
	 *
	 * @param commandEncoder Native command encoder pointer
	 * @param groupWidth Threadgroup width
	 * @param groupHeight Threadgroup height
	 * @param groupDepth Threadgroup depth
	 * @param gridWidth Grid width
	 * @param gridHeight Grid height
	 * @param gridDepth Grid depth
	 */
	public static native void dispatchThreads(long commandEncoder,
												   int groupWidth, int groupHeight, int groupDepth,
												   int gridWidth, int gridHeight, int gridDepth);

	/**
	 * Dispatches compute threadgroups with explicit threadgroup and grid dimensions.
	 *
	 * @param commandEncoder Native command encoder pointer
	 * @param groupWidth Threads per threadgroup width
	 * @param groupHeight Threads per threadgroup height
	 * @param groupDepth Threads per threadgroup depth
	 * @param gridWidth Number of threadgroups width
	 * @param gridHeight Number of threadgroups height
	 * @param gridDepth Number of threadgroups depth
	 */
	public static native void dispatchThreadgroups(long commandEncoder,
												   int groupWidth, int groupHeight, int groupDepth,
												   int gridWidth, int gridHeight, int gridDepth);

	/**
	 * Ends encoding for the command encoder.
	 *
	 * @param commandEncoder Native command encoder pointer
	 */
	public static native void endEncoding(long commandEncoder);

	/**
	 * Commits the command buffer for execution.
	 *
	 * @param commandBuffer Native command buffer pointer
	 */
	public static native void commitCommandBuffer(long commandBuffer);

	/**
	 * Waits until the command buffer completes execution (synchronous).
	 *
	 * @param commandBuffer Native command buffer pointer
	 */
	public static native void waitUntilCompleted(long commandBuffer);

	/**
	 * Releases a Metal buffer and frees its resources.
	 *
	 * @param buffer Native buffer pointer
	 */
	public static native void releaseBuffer(long buffer);

	/**
	 * Releases a compute pipeline state and frees its resources.
	 *
	 * @param pipeline Native pipeline state pointer
	 */
	public static native void releaseComputePipelineState(long pipeline);

	/**
	 * Releases a command queue and frees its resources.
	 *
	 * @param commandQueue Native command queue pointer
	 */
	public static native void releaseCommandQueue(long commandQueue);

	/**
	 * Releases a Metal device and frees its resources.
	 *
	 * @param device Native device pointer
	 */
	public static native void releaseDevice(long device);
}
