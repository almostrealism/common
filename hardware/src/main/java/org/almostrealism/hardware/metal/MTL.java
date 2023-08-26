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
import java.nio.file.Files;
import java.util.ResourceBundle;

public class MTL {
	static {
		// System.loadLibrary("MTL");

		System.getProperty("java.io.tmpdir");
		InputStream is = MTL.class.getClassLoader().getResourceAsStream("libMTL.dylib");

		File tempDir = new File(System.getProperty("java.io.tmpdir"));
		tempDir.mkdir();

		File tempLibFile = new File(tempDir, "libMTL.dylib");
		try {
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
	public static native void setBufferContents16(long buffer, FloatBuffer in, int offset, int length);
	public static native void setBufferContents32(long buffer, FloatBuffer in, int offset, int length);
	public static native void getBufferContents16(long buffer, FloatBuffer out, int offset, int length);
	public static native void getBufferContents32(long buffer, FloatBuffer out, int offset, int length);
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
