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
 * Standalone tests for {@link SoftNormBottleneck}.
 *
 * <p>SoftNorm applies a learned per-channel affine transform to the encoder output:
 * {@code latent = (x * scalingFactor + bias) / runningStd} over a {@code (batch, dim, length)}
 * tensor. These tests build the bottleneck into a {@link Model}, compile it, and run a forward pass
 * &mdash; proving the bottleneck participates in the compiled computation graph rather than being
 * host-evaluated &mdash; then compare every output element against a direct double-precision
 * reference computed from the same input. Cases cover the affine without rescaling, the affine with
 * a scalar standard-deviation divisor (auto-scale), the identity configuration, and composition at
 * a realistic latent width. No Stable Audio 3 weights are involved.</p>
 *
 * <p>A small floating-point tolerance is used because the compiled kernel may evaluate in float32
 * while the reference is computed in double precision.</p>
 */
public class SoftNormBottleneckTest extends TestSuiteBase implements LayerFeatures {

	/** Tolerance for float32 kernel output versus the double-precision reference. */
	private static final double TOLERANCE = 1e-4;

	/**
	 * A per-channel affine with no standard-deviation rescaling.
	 */
	@Test(timeout = 120000)
	public void affineWithoutRunningStd() {
		int batch = 2;
		int dim = 4;
		int length = 3;

		PackedCollection scale = new PackedCollection(shape(dim)).randnFill();
		PackedCollection bias = new PackedCollection(shape(dim)).randnFill();

		verifySoftNorm(batch, dim, length, scale, bias, null);
	}

	/**
	 * A per-channel affine followed by a scalar standard-deviation divisor (auto-scale enabled).
	 */
	@Test(timeout = 120000)
	public void affineWithRunningStd() {
		int batch = 2;
		int dim = 5;
		int length = 3;

		PackedCollection scale = new PackedCollection(shape(dim)).randnFill();
		PackedCollection bias = new PackedCollection(shape(dim)).randnFill();
		PackedCollection runningStd = new PackedCollection(shape(1)).fill(pos -> 2.5);

		verifySoftNorm(batch, dim, length, scale, bias, runningStd);
	}

	/**
	 * With {@code scalingFactor = 1}, {@code bias = 0} and no rescaling the latent must equal the
	 * input exactly.
	 */
	@Test(timeout = 120000)
	public void identityConfigurationPreservesInput() {
		int batch = 2;
		int dim = 4;
		int length = 3;

		PackedCollection scale = new PackedCollection(shape(dim)).fill(pos -> 1.0);
		PackedCollection bias = new PackedCollection(shape(dim)).fill(pos -> 0.0);

		verifySoftNorm(batch, dim, length, scale, bias, null);
	}

	/**
	 * Verifies that the bottleneck composes into a model and preserves the latent shape at the
	 * Stable Audio 3 SAME latent width (256 channels). This exercises the per-channel broadcast at a
	 * realistic width without depending on any reference numerics.
	 */
	@Test(timeout = 120000)
	public void composesAtSameLatentWidth() {
		int batch = 1;
		int dim = 256;
		int length = 4;
		TraversalPolicy shape = shape(batch, dim, length);

		PackedCollection scale = new PackedCollection(shape(dim)).randnFill();
		PackedCollection bias = new PackedCollection(shape(dim)).randnFill();

		SoftNormBottleneck bottleneck = new SoftNormBottleneck(dim, scale, bias);
		assertEquals(dim, bottleneck.getInputDim());
		assertEquals(dim, bottleneck.getOutputDim());

		Block block = bottleneck.bottleneck(batch, length);

		Model model = new Model(shape);
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);

		PackedCollection output = compiled.forward(new PackedCollection(shape).randnFill());
		assertEquals(shape.getTotalSize(), output.getShape().getTotalSize());
	}

	/**
	 * Builds the SoftNorm bottleneck for the given parameters, runs a forward pass through a
	 * compiled model, and asserts every output element matches
	 * {@code (x * scalingFactor + bias) / runningStd}.
	 *
	 * @param batch      batch dimension
	 * @param dim        latent channel dimension
	 * @param length     latent sequence length
	 * @param scale      per-channel multiplicative scale, shape {@code (dim)}
	 * @param bias       per-channel additive shift, shape {@code (dim)}
	 * @param runningStd optional scalar divisor ({@code (1)}), or {@code null} for no rescaling
	 */
	protected void verifySoftNorm(int batch, int dim, int length, PackedCollection scale,
								  PackedCollection bias, PackedCollection runningStd) {
		TraversalPolicy shape = shape(batch, dim, length);
		PackedCollection input = new PackedCollection(shape).randnFill();

		SoftNormBottleneck bottleneck = runningStd == null
				? new SoftNormBottleneck(dim, scale, bias)
				: new SoftNormBottleneck(dim, scale, bias, runningStd);
		assertEquals(dim, bottleneck.getInputDim());
		assertEquals(dim, bottleneck.getOutputDim());

		Block block = bottleneck.bottleneck(batch, length);
		Model model = new Model(shape);
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);

		PackedCollection output = compiled.forward(input);
		assertEquals(shape.getTotalSize(), output.getShape().getTotalSize());

		double std = runningStd == null ? 1.0 : runningStd.valueAt(0);

		for (int b = 0; b < batch; b++) {
			for (int c = 0; c < dim; c++) {
				for (int l = 0; l < length; l++) {
					double expected = (input.valueAt(b, c, l) * scale.valueAt(c) + bias.valueAt(c)) / std;
					assertEquals(expected, output.valueAt(b, c, l), TOLERANCE);
				}
			}
		}
	}
}
