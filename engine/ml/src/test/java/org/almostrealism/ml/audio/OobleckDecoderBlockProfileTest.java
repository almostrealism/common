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
import java.util.ArrayList;
import java.util.List;

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

	/**
	 * Weight tensors allocated by {@link #weights(int...)}, tracked so they can be released
	 * after the profile is taken. Weight normalization allocates new tensors rather than
	 * transferring ownership of its source g/v tensors to the model, so destroying the model
	 * does not release them; the test releases every tensor it allocated itself.
	 */
	private final List<PackedCollection> allocated = new ArrayList<>();

	/**
	 * Builds the first decoder block with random weights, compiles and runs it twice with a
	 * profile attached, and saves the profile. The block holds over 58 million weight elements,
	 * so the test is skipped in quick runs and releases the model, the compiled operations and
	 * every weight tensor it allocated once the profile has been taken.
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 10 * 60000)
	public void profileDecoderBlock1() throws IOException {
		if (skipLongTests) return;

		int padding = (STRIDE - 1) / 2;
		int outLength = (LENGTH - 1) * STRIDE - 2 * padding + STRIDE + (STRIDE - 1);

		OperationProfileNode profile = new OperationProfileNode("oobleck_decoder_block1");
		Hardware.getLocalHardware().assignProfile(profile);

		Model model = null;
		CompiledModel compiled = null;
		PackedCollection input = null;

		try {
			SequentialBlock block = new SequentialBlock(shape(1, IN_CHANNELS, LENGTH));
			block.add(snake(shape(1, IN_CHANNELS, LENGTH), weights(IN_CHANNELS), weights(IN_CHANNELS)));
			block.add(wnConvTranspose1d(1, IN_CHANNELS, OUT_CHANNELS, LENGTH, STRIDE, STRIDE,
					padding, STRIDE - 1, weights(IN_CHANNELS, 1, 1),
					weights(IN_CHANNELS, OUT_CHANNELS, STRIDE), weights(OUT_CHANNELS)));
			for (int i = 0; i < 3; i++) {
				block.add(residualUnit(OUT_CHANNELS, outLength));
			}

			model = new Model(shape(1, IN_CHANNELS, LENGTH));
			model.add(block);
			compiled = model.compile(false, profile);

			input = new PackedCollection(1, IN_CHANNELS, LENGTH).randFill();

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
			if (compiled != null) compiled.destroy();
			if (model != null) model.destroy();
			if (input != null) input.destroy();
			allocated.forEach(PackedCollection::destroy);
			allocated.clear();
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

	/**
	 * Returns a tensor of uniform random values in {@code [0, 1)}, generated on the device, and
	 * tracks it so it can be released once the profile has been taken.
	 */
	private PackedCollection weights(int... dims) {
		PackedCollection weights = new PackedCollection(dims).randFill();
		allocated.add(weights);
		return weights;
	}
}
