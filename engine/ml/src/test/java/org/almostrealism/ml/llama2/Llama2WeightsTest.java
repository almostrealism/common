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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Unit tests for {@link Llama2Weights} helper methods.
 *
 * <p>Validates the {@code take} and {@code stage} helpers that move
 * checkpoint regions into {@link PackedCollection} tensors buffer to
 * buffer, without host arrays.</p>
 *
 * @author Michael Murray
 */
public class Llama2WeightsTest extends TestSuiteBase {

	/**
	 * Builds a little-endian checkpoint-style buffer holding the given values,
	 * as {@link Llama2Weights} receives from a mapped checkpoint file.
	 */
	private ByteBuffer checkpoint(float... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 4)
				.order(ByteOrder.LITTLE_ENDIAN);
		buffer.asFloatBuffer().put(values);
		return buffer;
	}

	/**
	 * Verifies that {@code take} slices regions of the correct extent
	 * off the buffer and advances its position past them.
	 */
	@Test(timeout = 60000)
	public void testTakeReadsCorrectElements() {
		ByteBuffer buffer = checkpoint(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f);

		ByteBuffer first = Llama2Weights.take(buffer, 4);
		ByteBuffer second = Llama2Weights.take(buffer, 2);

		Assert.assertEquals("First take should have 4 elements", 16, first.remaining());
		Assert.assertEquals(1.0f, first.getFloat(0), 0.0f);
		Assert.assertEquals(4.0f, first.getFloat(12), 0.0f);

		Assert.assertEquals("Second take should have 2 elements", 8, second.remaining());
		Assert.assertEquals(5.0f, second.getFloat(0), 0.0f);
		Assert.assertEquals(6.0f, second.getFloat(4), 0.0f);

		Assert.assertEquals("Buffer should be fully consumed", 0, buffer.remaining());
	}

	/**
	 * Verifies that {@code stage} with a single source copies the tensor's
	 * values in order and advances the checkpoint past them.
	 */
	@Test(timeout = 60000)
	public void testStageSingleSource() {
		ByteBuffer buffer = checkpoint(10.0f, 20.0f, 30.0f);

		PackedCollection result = Llama2Weights.stage(new TraversalPolicy(3), buffer);

		Assert.assertEquals(3, result.getMemLength());
		Assert.assertEquals(10.0, result.toDouble(0), 1e-6);
		Assert.assertEquals(30.0, result.toDouble(2), 1e-6);
		Assert.assertEquals("Buffer should be fully consumed", 0, buffer.remaining());
	}

	/**
	 * Verifies that {@code stage} with two sources correctly interleaves real
	 * and imaginary parts into the expected [real, imag, real, imag, ...]
	 * layout, as for the RoPE frequency tensor.
	 */
	@Test(timeout = 60000)
	public void testStageInterleaving() {
		ByteBuffer buffer = checkpoint(
				1.0f, 2.0f, 3.0f, 4.0f, 0.1f, 0.2f, 0.3f, 0.4f);
		TraversalPolicy shape = new TraversalPolicy(2, 2, 2);

		PackedCollection result = Llama2Weights.stage(shape,
				Llama2Weights.take(buffer, 4), Llama2Weights.take(buffer, 4));

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
	 * Verifies that {@code stage} rejects a shape whose element count does
	 * not divide evenly across the given sources.
	 */
	@Test(timeout = 60000, expected = IllegalArgumentException.class)
	public void testStageRejectsInvalidShape() {
		ByteBuffer buffer = checkpoint(1.0f, 2.0f, 3.0f, 0.1f, 0.2f, 0.3f);
		TraversalPolicy badShape = new TraversalPolicy(3, 1);

		Llama2Weights.stage(badShape,
				Llama2Weights.take(buffer, 3), Llama2Weights.take(buffer, 3));
	}
}
