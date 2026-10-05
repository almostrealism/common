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
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records an operation profile of the first Oobleck decoder block (a Snake activation, a 16x
 * weight-normalized transposed convolution from 2048 to 1024 channels, and three residual units
 * of kernel-7 and pointwise weight-normalized convolutions) while it is compiled and run, so
 * the time its forward pass takes can be attributed to the operations that spend it.
 *
 * <p>The first forward pass compiles every kernel and the second only runs them; both are
 * timed separately, and the profile is written to {@code results/oobleck_decoder_block1.xml}
 * for inspection with the profile analyzer.</p>
 */
public class OobleckDecoderBlockProfileTest extends TestSuiteBase {
	/** Input channels of the first decoder block. */
	private static final int IN_CHANNELS = 2048;

	/** Output channels of the first decoder block. */
	private static final int OUT_CHANNELS = 1024;

	/** Input length of the first decoder block. */
	private static final int LENGTH = 2;

	/** Upsampling stride of the first decoder block. */
	private static final int STRIDE = 16;

	// TODO(review): diagnostic-only test (asserts output size only) runs in every CI shard; guard with skipLongTests/@TestDepth or fold into OobleckComponentTests
	/**
	 * Builds the first decoder block with random weights, compiles and runs it twice with a
	 * profile attached, and saves the profile.
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 10 * 60000)
	public void profileDecoderBlock1() throws IOException {
		int padding = (STRIDE - 1) / 2;
		int outLength = (LENGTH - 1) * STRIDE - 2 * padding + STRIDE + (STRIDE - 1);

		OperationProfileNode profile = new OperationProfileNode("oobleck_decoder_block1");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			SequentialBlock block = new SequentialBlock(shape(1, IN_CHANNELS, LENGTH));
			block.add(snake(shape(1, IN_CHANNELS, LENGTH), weights(IN_CHANNELS), weights(IN_CHANNELS)));
			block.add(wnConvTranspose1d(1, IN_CHANNELS, OUT_CHANNELS, LENGTH, STRIDE, STRIDE,
					padding, STRIDE - 1, weights(IN_CHANNELS, 1, 1),
					weights(IN_CHANNELS, OUT_CHANNELS, STRIDE), weights(OUT_CHANNELS)));
			for (int i = 0; i < 3; i++) {
				block.add(residualUnit(OUT_CHANNELS, outLength));
			}

			Model model = new Model(shape(1, IN_CHANNELS, LENGTH));
			model.add(block);
			CompiledModel compiled = model.compile(false, profile);

			PackedCollection input = new PackedCollection(1, IN_CHANNELS, LENGTH).randFill();

			long start = System.nanoTime();
			PackedCollection first = compiled.forward(input);
			long firstMs = (System.nanoTime() - start) / 1000000;

			start = System.nanoTime();
			compiled.forward(input);
			long secondMs = (System.nanoTime() - start) / 1000000;

			log("firstForwardMs=" + firstMs + " secondForwardMs=" + secondMs);
			Assert.assertEquals((long) OUT_CHANNELS * outLength, first.getShape().getTotalSizeLong());
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}

		Files.createDirectories(Path.of("results"));
		profile.save("results/oobleck_decoder_block1.xml");
	}

	/**
	 * Builds a residual unit: Snake, kernel-7 weight-normalized convolution, Snake and pointwise
	 * weight-normalized convolution, added to the skip connection.
	 */
	private Block residualUnit(int channels, int length) {
		TraversalPolicy shape = shape(1, channels, length);
		SequentialBlock main = new SequentialBlock(shape);
		main.add(snake(shape, weights(channels), weights(channels)));
		main.add(wnConv1d(1, channels, channels, length, 7, 1, 3,
				weights(channels, 1, 1), weights(channels, channels, 7), weights(channels)));
		main.add(snake(shape, weights(channels), weights(channels)));
		main.add(wnConv1d(1, channels, channels, length, 1, 1, 0,
				weights(channels, 1, 1), weights(channels, channels, 1), weights(channels)));
		return residual(main);
	}

	/** Returns a tensor of uniform random values in {@code [0, 1)}, generated on the device. */
	private PackedCollection weights(int... dims) {
		return new PackedCollection(dims).randFill();
	}
}
