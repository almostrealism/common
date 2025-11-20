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

	public static native long createSystemDefaultDevice();
	public static native int maxThreadgroupWidth(long device);
	public static native int maxThreadgroupHeight(long device);
	public static native int maxThreadgroupDepth(long device);

	public static native long createFunction(long device, String func, String source);
	public static native long createComputePipelineState(long device, long function);
	public static native int maxTotalThreadsPerThreadgroup(long pipelineState);
	public static native int threadExecutionWidth(long pipelineState);

	public static native long createCommandQueue(long device);
	public static native long commandBuffer(long commandQueue);
	public static native long computeCommandEncoder(long commandBuffer);

	public static long createIntBuffer32(long device, int[] data) {
		return createIntBuffer32(device, data, data.length);
	}

	public static long createIntBuffer32(long device, long len) {
		return createIntBuffer32(device, null, len);
	}

	public static native long createIntBuffer32(long device, int[] data, long len);

	public static long createBuffer16(long device, long len) {
		return createBuffer16(device, null, len);
	}
	public static long createBuffer16(long device, float[] data) {
		return createBuffer16(device, data, data.length);
	}
	public static long createBuffer32(long device, long len) {
		return createBuffer32(device, null, len);
	}
	public static long createBuffer32(long device, float[] data) {
		return createBuffer32(device, data, data.length);
	}
	public static native long createBuffer16(long device, float[] data, long len);
	public static native long createBuffer32(long device, float[] data, long len);

	public static long createSharedBuffer32(long device, String filePath, float[] data) {
		return createSharedBuffer32(device, filePath, data, data.length);
	}
	public static long createSharedBuffer32(long device, String filePath, int len) {
		return createSharedBuffer32(device, filePath, null, len);
	}
	public static native long createSharedBuffer32(long device, String filePath, float[] data, int len);

	public static native long getContentPointer(long buffer);
	public static native void setBufferContents16(long buffer, FloatBuffer in, int offset, int length);

	public static void setBufferContents32(long buffer, FloatBuffer in, int offset, int length) {
		setBufferContents32(buffer, in, offset, length, false);
	}
	public static native void setBufferContents32(long buffer, FloatBuffer in, int offset, int length, boolean sync);

	public static native void setIntBufferContents32(long buffer, IntBuffer in, int offset, int length);
	public static native void getBufferContents16(long buffer, FloatBuffer out, int offset, int length);
	public static native void getBufferContents32(long buffer, FloatBuffer out, int offset, int length);
	public static native void getIntBufferContents32(long buffer, IntBuffer out, int offset, int length);
	public static native long bufferLength(long buffer);

	public static native void setComputePipelineState(long commandEncoder, long pipeline);
	public static native void setBuffer(long commandEncoder, int index, long buffer);

	public static native void dispatchThreads(long commandEncoder,
												   int groupWidth, int groupHeight, int groupDepth,
												   int gridWidth, int gridHeight, int gridDepth);

	public static native void dispatchThreadgroups(long commandEncoder,
												   int groupWidth, int groupHeight, int groupDepth,
												   int gridWidth, int gridHeight, int gridDepth);

	public static native void endEncoding(long commandEncoder);
	public static native void commitCommandBuffer(long commandBuffer);
	public static native void waitUntilCompleted(long commandBuffer);

	public static native void releaseBuffer(long buffer);
	public static native void releaseComputePipelineState(long pipeline);
	public static native void releaseCommandQueue(long commandQueue);
	public static native void releaseDevice(long device);
}
