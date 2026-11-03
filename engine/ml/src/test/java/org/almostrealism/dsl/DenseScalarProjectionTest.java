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

package org.almostrealism.dsl;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Standalone test of the {@code dense} built-in projecting a scalar feature (a {@code [nodes, 1]}
 * weight). The projection of a {@code [batch, 1]} input is the outer product {@code input · weightᵀ},
 * of shape {@code [batch, nodes]}. Before {@link org.almostrealism.layers.LayerFeatures#denseOperator}
 * special-cased a size-one feature, the underlying {@code matmul(weights, input)} read the
 * {@code [batch, 1]} column as a single vector and collapsed the batch, so this projection threw
 * {@code "Cannot reshape (nodes, 1) to (batch, nodes)"} for every batch larger than one; the batch-one
 * case is included as the regression guard that always worked.
 */
public class DenseScalarProjectionTest extends TestSuiteBase implements ModelTestFeatures {

	/** Classpath location of the fixture. */
	private static final String FIXTURE = "/pdsl/test_dense_scalar_primitives.pdsl";

	/** Agreement required with the double-precision host product. */
	private static final double TOLERANCE = 1e-5;

	/** A single-row scalar projection, the case that worked before the size-one path was added. */
	@Test(timeout = 120000)
	public void scalarProjectionMatchesOuterProductBatchOne() {
		assertMatchesOuterProduct(1, 4, new Random(1));
	}

	/**
	 * A multi-row scalar projection: the case the single-vector heuristic used to collapse. Every row
	 * must keep its own {@code input · weightᵀ} outer product.
	 */
	@Test(timeout = 120000)
	public void scalarProjectionMatchesOuterProductBatchThree() {
		assertMatchesOuterProduct(3, 5, new Random(2));
	}

	/**
	 * Builds the {@code project} layer for a {@code [nodes, 1]} weight, forwards a {@code [batch, 1]}
	 * input and compares every output element with the host outer product {@code input[b] · weight[k]}.
	 *
	 * @param batch  the batch size
	 * @param nodes  the number of output nodes
	 * @param random the generator of the weight and input
	 */
	private void assertMatchesOuterProduct(int batch, int nodes, Random random) {
		PackedCollection weight = new PackedCollection(shape(nodes, 1)).randnFill(random);
		PackedCollection input = new PackedCollection(shape(batch, 1)).randnFill(random);

		Model model = new Model(shape(batch, 1));
		model.add(project(batch, weight));
		try (CompiledModel compiled = model.compile(false)) {
			double[] actual = compiled.forward(input).doubleStream().toArray();
			double[] w = weight.doubleStream().toArray();
			double[] x = input.doubleStream().toArray();

			Assert.assertEquals("output size", batch * nodes, actual.length);
			for (int b = 0; b < batch; b++) {
				for (int k = 0; k < nodes; k++) {
					Assert.assertEquals("element [" + b + ", " + k + "]",
							x[b] * w[k], actual[b * nodes + k], TOLERANCE);
				}
			}
		}
	}

	/**
	 * Builds the {@code project} fixture layer for the given weight and batch size.
	 *
	 * @param batch  the batch size of the input the layer is built for
	 * @param weight the projection weight of shape {@code [nodes, 1]}
	 * @return the layer
	 */
	private Block project(int batch, PackedCollection weight) {
		Map<String, Object> args = new HashMap<>();
		args.put("w", weight);
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FIXTURE), "project", shape(batch, 1), args);
	}
}
