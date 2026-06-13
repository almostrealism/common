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
 * Tests for the {@link Bottleneck} abstraction and the retrofit of {@link VAEBottleneck} onto it.
 *
 * <p>The retrofit must not change {@link VAEBottleneck} behavior: existing autoencoder paths obtain
 * the transform via {@link VAEBottleneck#getBottleneck()} and expect the deterministic 128 to 64
 * mean split (the first 64 channels of the encoder output). {@link #vaeRetrofitPreservesMeanSplit()}
 * is the regression guard for that path; {@link #vaeInterfaceMethodMatchesMeanSplit()} checks the
 * newly added {@link Bottleneck#bottleneck(int, int)} method produces the same transform. The
 * remaining cases confirm both concrete bottlenecks are usable through the {@link Bottleneck} type.</p>
 */
public class BottleneckInterfaceTest extends TestSuiteBase implements LayerFeatures {

	/** Encoder output channels for the VAE bottleneck. */
	private static final int VAE_INPUT_DIM = 128;

	/** Latent channels for the VAE bottleneck (mean component). */
	private static final int VAE_OUTPUT_DIM = 64;

	/**
	 * Regression guard for existing autoencoder paths: the block returned by
	 * {@link VAEBottleneck#getBottleneck()} (built at construction, as the autoencoder uses it) must
	 * still extract the first {@value #VAE_OUTPUT_DIM} channels of the encoder output unchanged.
	 */
	@Test(timeout = 120000)
	public void vaeRetrofitPreservesMeanSplit() {
		int batch = 1;
		int length = 4;

		VAEBottleneck vae = new VAEBottleneck(batch, length);
		assertEquals(VAE_INPUT_DIM, vae.getInputDim());
		assertEquals(VAE_OUTPUT_DIM, vae.getOutputDim());

		assertMeanSplit(batch, length, vae.getBottleneck());
	}

	/**
	 * The {@link Bottleneck#bottleneck(int, int)} method added by the retrofit must build a block
	 * with the same 128 to 64 mean-split transform as the construction-time block.
	 */
	@Test(timeout = 120000)
	public void vaeInterfaceMethodMatchesMeanSplit() {
		int batch = 1;
		int length = 4;

		Bottleneck vae = new VAEBottleneck(batch, length);
		assertEquals(VAE_INPUT_DIM, vae.getInputDim());
		assertEquals(VAE_OUTPUT_DIM, vae.getOutputDim());

		assertMeanSplit(batch, length, vae.bottleneck(batch, length));
	}

	/**
	 * Both concrete bottlenecks are usable through the {@link Bottleneck} type and report the channel
	 * dimensions expected of each.
	 */
	@Test(timeout = 120000)
	public void concreteBottlenecksImplementInterface() {
		Bottleneck vae = new VAEBottleneck(1, 4);
		assertEquals(VAE_INPUT_DIM, vae.getInputDim());
		assertEquals(VAE_OUTPUT_DIM, vae.getOutputDim());

		int dim = 8;
		PackedCollection scale = new PackedCollection(shape(dim)).fill(pos -> 1.0);
		PackedCollection bias = new PackedCollection(shape(dim)).fill(pos -> 0.0);
		Bottleneck softNorm = new SoftNormBottleneck(dim, scale, bias);
		assertEquals(dim, softNorm.getInputDim());
		assertEquals(dim, softNorm.getOutputDim());
	}

	/**
	 * Compiles the given bottleneck block, runs a known {@value #VAE_INPUT_DIM}-channel input
	 * through it, and asserts the output equals the first {@value #VAE_OUTPUT_DIM} input channels.
	 *
	 * @param batch  batch dimension
	 * @param length latent sequence length
	 * @param block  the bottleneck block under test
	 */
	protected void assertMeanSplit(int batch, int length, Block block) {
		TraversalPolicy inputShape = shape(batch, VAE_INPUT_DIM, length);
		TraversalPolicy outputShape = shape(batch, VAE_OUTPUT_DIM, length);

		Model model = new Model(inputShape);
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);

		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection output = compiled.forward(input);
		assertEquals(outputShape.getTotalSize(), output.getShape().getTotalSize());

		for (int b = 0; b < batch; b++) {
			for (int c = 0; c < VAE_OUTPUT_DIM; c++) {
				for (int l = 0; l < length; l++) {
					assertEquals(input.valueAt(b, c, l), output.valueAt(b, c, l), 1e-5);
				}
			}
		}
	}
}
