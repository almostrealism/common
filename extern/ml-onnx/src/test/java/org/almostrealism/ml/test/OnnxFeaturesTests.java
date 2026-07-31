/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.test;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.OnnxFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link OnnxFeatures#pack(OnnxTensor)}: the resulting collection
 * reads from the tensor's buffer copy, survives the tensor being closed,
 * works as a kernel argument, and rejects writes while buffer-backed.
 */
public class OnnxFeaturesTests extends TestSuiteBase implements OnnxFeatures {

	/** Number of elements in the test tensors. */
	private static final int SIZE = 60;

	/** The value at each position of the test tensor. */
	private static float valueAt(int index) { return 0.25f * index - 3.0f; }

	/**
	 * Creates an ONNX tensor of shape (4, 15) with values following
	 * {@link #valueAt}, packs it, and closes the tensor before returning.
	 */
	private PackedCollection packedFromClosedTensor() throws OrtException {
		float[] data = new float[SIZE];
		for (int i = 0; i < SIZE; i++) {
			data[i] = valueAt(i);
		}

		OrtEnvironment env = OrtEnvironment.getEnvironment();
		OnnxTensor tensor = packOnnx(env, new TraversalPolicy(4, 15), data);

		try {
			return pack(tensor);
		} finally {
			tensor.close();
		}
	}

	/**
	 * The packed collection preserves the tensor's shape and values, and is
	 * independent of the tensor's lifetime — the tensor is closed before any
	 * value is read.
	 */
	@Test(timeout = 120000)
	public void packSurvivesTensorClose() throws OrtException {
		PackedCollection packed = packedFromClosedTensor();

		Assert.assertEquals("NIO",
				packed.getRootDelegate().getMem().getProvider().getName());
		Assert.assertEquals(2, packed.getShape().getDimensions());
		Assert.assertEquals(4, packed.getShape().length(0));
		Assert.assertEquals(15, packed.getShape().length(1));

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), packed.toDouble(i), 1e-6);
		}
	}

	/**
	 * The packed collection works as a kernel argument after the tensor is
	 * closed, computing from the buffer copy.
	 */
	@Test(timeout = 120000)
	public void packedTensorComputesAsKernelArgument() throws OrtException {
		PackedCollection packed = packedFromClosedTensor();

		PackedCollection doubled = cp(packed).multiply(2.0).evaluate();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					2.0 * valueAt(i), doubled.toDouble(i), 1e-6);
		}
	}

	/**
	 * Packed tensors are read-only sources: writes are rejected rather than
	 * silently lost, since migration is one-way.
	 */
	@Test(timeout = 120000)
	public void packedTensorRejectsWrites() throws OrtException {
		PackedCollection packed = packedFromClosedTensor();

		try {
			packed.setMem(0, 1.0);
			Assert.fail("Write into tensor-backed memory should be rejected");
		} catch (UnsupportedOperationException e) {
			// Expected: the provider is a read-only source
		}
	}
}
