/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.llama2;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.FloatBuffer;

/**
 * Unit tests for {@link Llama2Weights} helper methods.
 *
 * <p>Validates the {@code take} and {@code packComplex} static helpers
 * that convert raw float buffers into {@link PackedCollection} tensors.</p>
 *
 * @author Michael Murray
 */
public class Llama2WeightsTest extends TestSuiteBase {

	/**
	 * Verifies that {@code take} reads the correct number of floats
	 * from the buffer and advances its position.
	 */
	@Test
	public void testTakeReadsCorrectElements() {
		float[] data = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f};
		FloatBuffer buffer = FloatBuffer.wrap(data);

		float[] first = Llama2Weights.take(buffer, 2, 2);
		float[] second = Llama2Weights.take(buffer, 2);

		Assert.assertEquals("First take should have 4 elements", 4, first.length);
		Assert.assertEquals(1.0f, first[0], 0.0f);
		Assert.assertEquals(4.0f, first[3], 0.0f);

		Assert.assertEquals("Second take should have 2 elements", 2, second.length);
		Assert.assertEquals(5.0f, second[0], 0.0f);
		Assert.assertEquals(6.0f, second[1], 0.0f);

		Assert.assertEquals("Buffer should be fully consumed", 6, buffer.position());
	}

	/**
	 * Verifies that {@code take} with a single dimension returns
	 * the correct flat array.
	 */
	@Test
	public void testTakeSingleDimension() {
		float[] data = {10.0f, 20.0f, 30.0f};
		FloatBuffer buffer = FloatBuffer.wrap(data);

		float[] result = Llama2Weights.take(buffer, 3);

		Assert.assertEquals(3, result.length);
		Assert.assertEquals(10.0f, result[0], 0.0f);
		Assert.assertEquals(30.0f, result[2], 0.0f);
	}

	/**
	 * Verifies that {@code packComplex} correctly interleaves real and
	 * imaginary parts into the expected [real, imag, real, imag, ...] layout.
	 */
	@Test
	public void testPackComplexInterleaving() {
		float[] real = {1.0f, 2.0f, 3.0f, 4.0f};
		float[] imag = {0.1f, 0.2f, 0.3f, 0.4f};
		TraversalPolicy shape = new TraversalPolicy(2, 2, 2);

		PackedCollection result = Llama2Weights.packComplex(real, imag, shape);

		Assert.assertEquals("Total size should be 8", 8, result.getMemLength());
		Assert.assertEquals(1.0, result.toDouble(0), 1e-6);
		Assert.assertEquals(0.1, result.toDouble(1), 1e-6);
		Assert.assertEquals(2.0, result.toDouble(2), 1e-6);
		Assert.assertEquals(0.2, result.toDouble(3), 1e-6);
		Assert.assertEquals(3.0, result.toDouble(4), 1e-6);
		Assert.assertEquals(0.3, result.toDouble(5), 1e-6);
		Assert.assertEquals(4.0, result.toDouble(6), 1e-6);
		Assert.assertEquals(0.4, result.toDouble(7), 1e-6);
	}

	/**
	 * Verifies that {@code packComplex} rejects a shape whose last
	 * dimension is not 2.
	 */
	@Test(expected = IllegalArgumentException.class)
	public void testPackComplexRejectsInvalidShape() {
		float[] real = {1.0f, 2.0f, 3.0f};
		float[] imag = {0.1f, 0.2f, 0.3f};
		TraversalPolicy badShape = new TraversalPolicy(3, 1);

		Llama2Weights.packComplex(real, imag, badShape);
	}
}
