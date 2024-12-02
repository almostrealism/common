package org.almostrealism.hardware.metal.test;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MTL;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MetalJNI {
	@Test
	public void run() throws IOException {
		if (!(Hardware.getLocalHardware().getComputeContext() instanceof MetalComputeContext)) return;

		float[] vector = { 1.0f, 2.0f, 3.0f, 4.0f };
		float[] matrix = { 1.0f, 2.0f, 3.0f, 4.0f,
						   5.0f, 6.0f, 7.0f, 8.0f,
						   9.0f, 10.0f, 11.0f, 12.0f,
						   13.0f, 14.0f, 15.0f, 16.0f };
		float[] result = new float[4];

		multiplyVectorWithMatrix(vector, matrix, result, 4);

		System.out.println("Result: " + result[0] + ", " + result[1] + ", " + result[2] + ", " + result[3]);
	}

	public static void multiplyVectorWithMatrix(float[] vector, float[] matrix, float[] result, int numElements) throws IOException {
		StringBuilder functionSource = new StringBuilder();

		try (InputStream in = MetalJNI.class.getClassLoader().getResourceAsStream("FunctionTest.metal");
			 BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
			String line;
			while ((line = reader.readLine()) != null) {
				functionSource.append(line).append("\n");
			}
		}


		long device = MTL.createSystemDefaultDevice();

		System.out.println("Loaded device: " + device);

		long function = MTL.createFunction(device, "vectorMatrixMultiply", functionSource.toString());

		// Create a compute pipeline
		long pipeline = MTL.createComputePipelineState(device, function);
		System.out.println("Created pipeline: " + pipeline);

		long commandQueue = MTL.createCommandQueue(device);
		long commandBuffer;
		long commandEncoder;

		// Create buffers for the input and output data
		long vectorBuffer = MTL.createBuffer32(device, vector);
		long matrixBuffer = MTL.createBuffer32(device, matrix);
		long resultBuffer = MTL.createBuffer32(device, result.length);

		for (int i = 0; i < 2; i++) {
			long start = System.nanoTime();
			commandBuffer = MTL.commandBuffer(commandQueue);
			commandEncoder = MTL.computeCommandEncoder(commandBuffer);

			MTL.setComputePipelineState(commandEncoder, pipeline);
			MTL.setBuffer(commandEncoder, 0, vectorBuffer);
			MTL.setBuffer(commandEncoder, 1, matrixBuffer);
			MTL.setBuffer(commandEncoder, 2, resultBuffer);

			MTL.dispatchThreadgroups(commandEncoder, 1, 1, 1, numElements, 1, 1);
			MTL.endEncoding(commandEncoder);

			MTL.commitCommandBuffer(commandBuffer);
			MTL.waitUntilCompleted(commandBuffer);
			System.out.println("Time: " + (System.nanoTime() - start));

			ByteBuffer resultBufferByte = ByteBuffer.allocateDirect(result.length * 4).order(ByteOrder.nativeOrder());
			FloatBuffer resultBufferFloat = resultBufferByte.asFloatBuffer();
			MTL.getBufferContents32(resultBuffer, resultBufferFloat, 0, result.length);

			resultBufferFloat.get(result);
		}

		MTL.releaseBuffer(vectorBuffer);
		MTL.releaseBuffer(matrixBuffer);
		MTL.releaseBuffer(resultBuffer);
		MTL.releaseComputePipelineState(pipeline);
		MTL.releaseCommandQueue(commandQueue);
		MTL.releaseDevice(device);
	}
}
