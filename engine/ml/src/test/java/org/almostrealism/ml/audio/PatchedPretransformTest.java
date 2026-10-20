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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies {@link PatchedPretransform}: the encode fold places input channel {@code c} at sample
 * {@code l * patchSize + h} into output channel {@code c * patchSize + h} at frame {@code l}, and
 * decode is its exact inverse.
 */
public class PatchedPretransformTest extends TestSuiteBase implements LayerFeatures {

	/**
	 * Encoding a stereo signal with patch size 4 produces 8 channels laid out channel-major,
	 * patch-offset-minor, matching the reference rearrangement.
	 */
	@Test(timeout = 120000)
	public void encodeFoldsSamplesIntoChannels() {
		int batch = 2;
		int channels = 2;
		int patchSize = 4;
		int length = 12;
		PatchedPretransform pretransform = new PatchedPretransform(channels, patchSize);
		assertEquals(8, pretransform.getEncodedChannels());
		assertEquals(patchSize, pretransform.getDownsamplingRatio());

		PackedCollection input = new PackedCollection(shape(batch, channels, length)).randnFill();
		PackedCollection encoded = run(pretransform.encode(batch, length), input);
		assertEquals(shape(batch, channels * patchSize, length / patchSize).getTotalSize(),
				encoded.getShape().getTotalSize());

		int frames = length / patchSize;
		for (int b = 0; b < batch; b++) {
			for (int c = 0; c < channels; c++) {
				for (int l = 0; l < frames; l++) {
					for (int h = 0; h < patchSize; h++) {
						assertEquals(input.valueAt(b, c, l * patchSize + h),
								encoded.valueAt(b, c * patchSize + h, l), 1e-6);
					}
				}
			}
		}
	}

	/**
	 * Decoding an encoded signal recovers the original exactly.
	 */
	@Test(timeout = 120000)
	public void decodeInvertsEncode() {
		int batch = 1;
		int channels = 2;
		int patchSize = 256;
		int length = 1024;
		PatchedPretransform pretransform = new PatchedPretransform(channels, patchSize);

		PackedCollection input = new PackedCollection(shape(batch, channels, length)).randnFill();
		PackedCollection encoded = run(pretransform.encode(batch, length), input);
		PackedCollection decoded = run(pretransform.decode(batch, length / patchSize), encoded);

		assertEquals(input.getShape().getTotalSize(), decoded.getShape().getTotalSize());
		for (int c = 0; c < channels; c++) {
			for (int i = 0; i < length; i++) {
				assertEquals(input.valueAt(0, c, i), decoded.valueAt(0, c, i), 1e-6);
			}
		}
	}

	/**
	 * Lengths that are not a whole number of patches are rejected by encode and rounded up by
	 * {@link PatchedPretransform#paddedLength(int)}.
	 */
	@Test(timeout = 60000)
	public void lengthMustBeWholePatches() {
		PatchedPretransform pretransform = new PatchedPretransform(2, 256);
		assertEquals(512, pretransform.paddedLength(300));
		assertEquals(256, pretransform.paddedLength(256));

		try {
			pretransform.encode(1, 300);
			throw new AssertionError("encode must reject a length that is not a multiple of the patch size");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("patch size"));
		}
	}

	/**
	 * Decoding places folded channel {@code c * patchSize + h} at frame {@code l} back at input
	 * channel {@code c}, sample {@code l * patchSize + h} — the exact inverse of the encode fold,
	 * pinned at the element level independently of {@link #decodeInvertsEncode}. Uses more than one
	 * batch entry and channel so the fold's channel-major, patch-offset-minor layout is exercised.
	 */
	@Test(timeout = 120000)
	public void decodeUnfoldsChannelsIntoSamples() {
		int batch = 2;
		int channels = 3;
		int patchSize = 4;
		int frames = 5;
		PatchedPretransform pretransform = new PatchedPretransform(channels, patchSize);

		PackedCollection input = new PackedCollection(shape(batch, channels * patchSize, frames)).randnFill();
		PackedCollection decoded = run(pretransform.decode(batch, frames), input);
		assertEquals(shape(batch, channels, frames * patchSize).getTotalSize(),
				decoded.getShape().getTotalSize());

		for (int b = 0; b < batch; b++) {
			for (int c = 0; c < channels; c++) {
				for (int l = 0; l < frames; l++) {
					for (int h = 0; h < patchSize; h++) {
						assertEquals(input.valueAt(b, c * patchSize + h, l),
								decoded.valueAt(b, c, l * patchSize + h), 1e-6);
					}
				}
			}
		}
	}

	/**
	 * A multi-channel, multi-batch signal survives an encode followed by a decode unchanged,
	 * complementing the single-batch {@link #decodeInvertsEncode} round trip.
	 */
	@Test(timeout = 120000)
	public void roundTripPreservesMultiChannelBatch() {
		int batch = 2;
		int channels = 3;
		int patchSize = 4;
		int length = 20;
		PatchedPretransform pretransform = new PatchedPretransform(channels, patchSize);

		PackedCollection input = new PackedCollection(shape(batch, channels, length)).randnFill();
		PackedCollection encoded = run(pretransform.encode(batch, length), input);
		PackedCollection decoded = run(pretransform.decode(batch, length / patchSize), encoded);

		assertEquals(input.getShape().getTotalSize(), decoded.getShape().getTotalSize());
		for (int b = 0; b < batch; b++) {
			for (int c = 0; c < channels; c++) {
				for (int i = 0; i < length; i++) {
					assertEquals(input.valueAt(b, c, i), decoded.valueAt(b, c, i), 1e-6);
				}
			}
		}
	}

	/**
	 * Compiles a single block into a model and runs it on the given input.
	 *
	 * @param block the block to evaluate
	 * @param input the model input
	 * @return the model output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		TraversalPolicy inShape = block.getInputShape();
		Model model = new Model(inShape);
		model.add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}
}
