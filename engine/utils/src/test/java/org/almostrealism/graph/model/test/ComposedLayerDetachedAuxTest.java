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

package org.almostrealism.graph.model.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies the forward pass of a layer built with {@link LayerRoutingFeatures}{@code .compose}
 * when its auxiliary branch belongs to a separately compiled model that is compiled after the
 * model holding the composed layer.
 *
 * <p>The auxiliary model projects its input {@code c} with a dense weight {@code W}; the main model
 * computes {@code out = x * (W c)}. Because the main model is compiled first, the composed layer has
 * no captured auxiliary output to read and must read the buffer the auxiliary branch records into
 * when its own model runs.</p>
 */
public class ComposedLayerDetachedAuxTest extends TestSuiteBase {
	/** Size of the main input, the auxiliary input and the projection. */
	private static final int SIZE = 6;

	/** Tolerance on each element of the output. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The composed layer reads the auxiliary output produced by the most recent run of the
	 * auxiliary model, so a second auxiliary run with a different input changes the main output
	 * accordingly rather than leaving it at the first run's value.
	 */
	@Test(timeout = 5 * 60000)
	public void readsMostRecentAuxiliaryOutput() {
		TraversalPolicy shape = shape(SIZE);
		Random random = new Random(41);
		PackedCollection weights = randn(shape(SIZE, SIZE), 0.0, 1.0, random).evaluate();

		Model contextModel = new Model(shape);
		SequentialBlock contextBlock = contextModel.sequential();
		contextBlock.add(dense(weights));

		Model mainModel = new Model(shape);
		mainModel.sequential().add(compose("context", contextBlock,
				(in, other) -> multiply(in, other)));

		CompiledModel mainCompiled = mainModel.compile(false);
		CompiledModel contextCompiled = contextModel.compile(false);

		try {
			PackedCollection x = randn(shape, 0.0, 1.0, random).evaluate();
			PackedCollection first = randn(shape, 0.0, 1.0, random).evaluate();
			PackedCollection second = randn(shape, 0.0, 1.0, random).evaluate();

			contextCompiled.forward(first);
			assertComposedOutput(weights, x, first, mainCompiled.forward(x));

			contextCompiled.forward(second);
			assertComposedOutput(weights, x, second, mainCompiled.forward(x));
		} finally {
			mainCompiled.destroy();
			contextCompiled.destroy();
		}
	}

	/**
	 * Asserts that {@code actual} equals {@code x * (weights c)} element by element, and that the
	 * expected value is not trivially zero.
	 *
	 * @param weights the {@code (SIZE, SIZE)} projection of the auxiliary model
	 * @param x       the main input
	 * @param c       the auxiliary input most recently run
	 * @param actual  the output of the main model
	 */
	private void assertComposedOutput(PackedCollection weights, PackedCollection x,
									  PackedCollection c, PackedCollection actual) {
		Assert.assertEquals(SIZE, actual.getShape().getTotalSize());

		double maxAbs = 0.0;
		for (int i = 0; i < SIZE; i++) {
			double projection = 0.0;
			for (int j = 0; j < SIZE; j++) {
				projection += weights.toDouble(i * SIZE + j) * c.toDouble(j);
			}

			double expected = x.toDouble(i) * projection;
			maxAbs = Math.max(maxAbs, Math.abs(expected));
			Assert.assertEquals("out[" + i + "]", expected, actual.toDouble(i), TOLERANCE);
		}

		Assert.assertTrue("output is trivially zero", maxAbs > 1e-3);
	}
}
