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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Standalone tests for the {@code DynamicTanh} (DyT) normalization layer
 * ({@link org.almostrealism.layers.NormalizationLayerFeatures#dynamicTanh}).
 *
 * <p>DyT computes {@code y = weight ⊙ tanh(alpha · x) + bias} element-wise with no reduction
 * statistics. These tests build the layer into a {@link Model}, compile it, and run a forward
 * pass — proving the layer participates in the compiled computation graph rather than being
 * host-evaluated — then compare the output against a direct reference computation of the DyT
 * formula. The cases cover the identity reduction to {@code tanh}, a scalar {@code alpha} with
 * non-trivial affine parameters, and a per-channel {@code alpha}. No Stable Audio 3 weights are
 * involved.</p>
 */
public class DynamicTanhTest extends TestSuiteBase implements LayerFeatures {

	/**
	 * With {@code alpha = 1}, {@code weight = 1} and {@code bias = 0} the layer must reduce to a
	 * plain element-wise {@code tanh}.
	 */
	@Test(timeout = 120000)
	public void identityWeightsReduceToTanh() {
		int batch = 4;
		int features = 8;

		PackedCollection alpha = new PackedCollection(shape(1)).fill(pos -> 1.0);
		PackedCollection weight = new PackedCollection(shape(features)).fill(pos -> 1.0);
		PackedCollection bias = new PackedCollection(shape(features)).fill(pos -> 0.0);

		verifyDynamicTanh(batch, features, alpha, weight, bias);
	}

	/**
	 * A scalar {@code alpha} with non-trivial per-channel {@code weight} and {@code bias}.
	 */
	@Test(timeout = 120000)
	public void scalarAlphaWithAffine() {
		int batch = 3;
		int features = 16;

		PackedCollection alpha = new PackedCollection(shape(1)).fill(pos -> 1.7);
		PackedCollection weight = new PackedCollection(shape(features)).randnFill();
		PackedCollection bias = new PackedCollection(shape(features)).randnFill();

		verifyDynamicTanh(batch, features, alpha, weight, bias);
	}

	/**
	 * A per-channel {@code alpha} (one learnable value per feature) with affine parameters.
	 */
	@Test(timeout = 120000)
	public void perChannelAlpha() {
		int batch = 2;
		int features = 12;

		PackedCollection alpha = new PackedCollection(shape(features)).randnFill();
		PackedCollection weight = new PackedCollection(shape(features)).randnFill();
		PackedCollection bias = new PackedCollection(shape(features)).randnFill();

		verifyDynamicTanh(batch, features, alpha, weight, bias);
	}

	/**
	 * Verifies that the layer is a {@link CellularLayer} (a {@code Block}, not a host-evaluated
	 * collection) and that it preserves the input shape when composed into a model.
	 */
	@Test(timeout = 120000)
	public void preservesShapeAndComposes() {
		int batch = 2;
		int features = 8;
		TraversalPolicy shape = shape(batch, features);

		PackedCollection alpha = new PackedCollection(shape(1)).fill(pos -> 1.0);
		PackedCollection weight = new PackedCollection(shape(features)).fill(pos -> 1.0);
		PackedCollection bias = new PackedCollection(shape(features)).fill(pos -> 0.0);

		CellularLayer dyt = dynamicTanh(shape, alpha, weight, bias);
		assertEquals(shape.getTotalSize(), dyt.getOutputShape().getTotalSize());

		Model model = new Model(shape);
		model.sequential().add(dyt);
		CompiledModel compiled = model.compile(false);

		PackedCollection output = compiled.forward(new PackedCollection(shape).randnFill());
		assertEquals(shape.getTotalSize(), output.getShape().getTotalSize());
	}

	/**
	 * Builds the DyT layer for the given parameters, runs a forward pass through a compiled model,
	 * and asserts every output element matches {@code weight ⊙ tanh(alpha · x) + bias}.
	 *
	 * @param batch    batch dimension
	 * @param features feature (last-axis) dimension
	 * @param alpha    learnable scale inside {@code tanh}; scalar ({@code [1]}) or per-feature
	 * @param weight   per-feature output scale
	 * @param bias     per-feature output shift
	 */
	protected void verifyDynamicTanh(int batch, int features, PackedCollection alpha,
									 PackedCollection weight, PackedCollection bias) {
		TraversalPolicy shape = shape(batch, features);
		PackedCollection input = new PackedCollection(shape).randnFill();

		CellularLayer dyt = dynamicTanh(shape, alpha, weight, bias);
		Model model = new Model(shape);
		model.sequential().add(dyt);
		CompiledModel compiled = model.compile(false);

		PackedCollection output = compiled.forward(input);
		assertEquals(shape.getTotalSize(), output.getShape().getTotalSize());

		boolean scalarAlpha = alpha.getShape().getTotalSize() == 1;

		for (int b = 0; b < batch; b++) {
			for (int f = 0; f < features; f++) {
				double a = scalarAlpha ? alpha.valueAt(0) : alpha.valueAt(f);
				double w = weight.valueAt(f);
				double bi = bias.valueAt(f);
				double expected = w * Math.tanh(a * input.valueAt(b, f)) + bi;

				// TODO(review): Consider adding a floating-point tolerance (e.g. 1e-5) here;
				// compiled kernels may use float32 internally, causing minor rounding vs the
				// double-precision reference. Existing AttentionTests uses exact assertEquals
				// so deferring to a downstream pass to decide the project convention.
				assertEquals(expected, output.valueAt(b, f));
			}
		}
	}
}
