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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Unit tests for {@link Llama2Config} checkpoint header parsing.
 *
 * <p>Validates that the seven-integer header is read correctly, including
 * the sign convention for vocabSize (negative means unshared weights)
 * and the derived headSize field.</p>
 *
 * @author Michael Murray
 */
public class Llama2ConfigTest extends TestSuiteBase {

	/**
	 * Verifies that a standard checkpoint header with shared weights
	 * (positive vocabSize) is parsed correctly.
	 */
	@Test
	public void testParseSharedWeightsHeader() {
		ByteBuffer buffer = createHeader(768, 2048, 12, 12, 12, 32000, 1024);

		Llama2Config config = new Llama2Config(buffer);

		Assert.assertEquals("dim", 768, config.dim);
		Assert.assertEquals("hiddenDim", 2048, config.hiddenDim);
		Assert.assertEquals("layerCount", 12, config.layerCount);
		Assert.assertEquals("headCount", 12, config.headCount);
		Assert.assertEquals("kvHeadCount", 12, config.kvHeadCount);
		Assert.assertEquals("vocabSize", 32000, config.vocabSize);
		Assert.assertEquals("seqLen", 1024, config.seqLen);
		Assert.assertTrue("sharedWeights should be true for positive vocabSize",
				config.sharedWeights);
		Assert.assertEquals("headSize should be dim / headCount",
				64, config.headSize);
	}

	/**
	 * Verifies that a negative vocabSize in the header indicates
	 * non-shared weights and that vocabSize is stored as a positive value.
	 */
	@Test
	public void testParseNonSharedWeightsHeader() {
		ByteBuffer buffer = createHeader(512, 1024, 6, 8, 8, -32000, 512);

		Llama2Config config = new Llama2Config(buffer);

		Assert.assertEquals("vocabSize should be absolute value", 32000, config.vocabSize);
		Assert.assertFalse("sharedWeights should be false for negative vocabSize",
				config.sharedWeights);
		Assert.assertEquals("headSize should be dim / headCount",
				64, config.headSize);
	}

	/**
	 * Verifies that grouped-query attention (kvHeadCount &lt; headCount)
	 * is parsed correctly.
	 */
	@Test
	public void testGroupedQueryAttentionConfig() {
		ByteBuffer buffer = createHeader(4096, 11008, 32, 32, 8, 32000, 2048);

		Llama2Config config = new Llama2Config(buffer);

		Assert.assertEquals("headCount", 32, config.headCount);
		Assert.assertEquals("kvHeadCount", 8, config.kvHeadCount);
		Assert.assertEquals("headSize should be dim / headCount",
				128, config.headSize);
	}

	/**
	 * Creates a 7-integer little-endian ByteBuffer simulating a checkpoint header.
	 */
	private static ByteBuffer createHeader(int dim, int hiddenDim, int layerCount,
			int headCount, int kvHeadCount, int vocabSize, int seqLen) {
		ByteBuffer buffer = ByteBuffer.allocate(7 * Integer.BYTES);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(dim);
		buffer.putInt(hiddenDim);
		buffer.putInt(layerCount);
		buffer.putInt(headCount);
		buffer.putInt(kvHeadCount);
		buffer.putInt(vocabSize);
		buffer.putInt(seqLen);
		buffer.flip();
		return buffer;
	}
}
