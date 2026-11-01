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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.GradientRecorder;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pins the backward pass of the {@code concat_blocks} statement through the {@code concat_grad}
 * layer of {@code test_concat_blocks_primitives.pdsl}: a concatenation of two trainable dense parts,
 * the second starting with a {@code slice}. Building the layer for training and running one forward
 * then one backward pass checks that each part's weights receive the gradient implied by its segment
 * of the output gradient — exercising the slice and concat backward passes together — and that the
 * returned input gradient is the sum of the parts' contributions.
 *
 * <p>The parts are bias-free dense layers, so the references are exact: {@code W_a} spans the whole
 * input and {@code W_b} spans its first {@code hidden_size} elements, so part A's weight gradient is
 * {@code g_a·xᵀ}, part B's is {@code g_b·(x[0:hidden_size])ᵀ}, and the input gradient is
 * {@code W_aᵀ·g_a} with {@code W_bᵀ·g_b} added into its first {@code hidden_size} positions.</p>
 */
public class ConcatBlocksPrimitiveTest extends TestSuiteBase implements ModelTestFeatures {

	/** Classpath location of the fixture program. */
	private static final String FIXTURE = "/pdsl/test_concat_blocks_primitives.pdsl";

	/** Input size of the layer. */
	private static final int IN_SIZE = 4;
	/** Size of the input prefix the second part reads. */
	private static final int HIDDEN_SIZE = 3;
	/** Output size of the first part. */
	private static final int OUT_A = 2;
	/** Output size of the second part. */
	private static final int OUT_B = 3;
	/** Row size of the cache the inline cache_read part reads. */
	private static final int CACHE_SIZE = 2;

	/** Agreement required with the exact double-precision references. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The weights inside both concatenated parts train: the backward pass delivers to each the
	 * gradient its segment of the output gradient implies, and the returned input gradient is the
	 * sum of the parts' input gradients, with the sliced part contributing only to the prefix it
	 * reads.
	 */
	@Test(timeout = 120000)
	public void concatBlocksTrainsBothParts() {
		Random random = new Random(31);
		PackedCollection weightA = seeded(shape(OUT_A, IN_SIZE), random);
		PackedCollection weightB = seeded(shape(OUT_B, HIDDEN_SIZE), random);
		PackedCollection input = seeded(shape(IN_SIZE), random);
		PackedCollection outputGradient = seeded(shape(OUT_A + OUT_B), random);

		GradientRecorder recorder = new GradientRecorder();
		Model model = new Model(shape(IN_SIZE), recorder);
		model.add(concatGrad(weightA, weightB));
		try (CompiledModel compiled = model.compile(true, true)) {
			double[] output = compiled.forward(input).doubleStream().toArray();
			double[] x = input.doubleStream().toArray();
			double[] g = outputGradient.doubleStream().toArray();

			Assert.assertEquals(OUT_A + OUT_B, output.length);
			for (int i = 0; i < OUT_A; i++) {
				Assert.assertEquals("segment a " + i, dot(weightA, i, x, IN_SIZE), output[i], TOLERANCE);
			}
			for (int i = 0; i < OUT_B; i++) {
				Assert.assertEquals("segment b " + i, dot(weightB, i, x, HIDDEN_SIZE), output[OUT_A + i], TOLERANCE);
			}

			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();

			// Part A's weight gradient is the outer product of g[0:OUT_A] with the whole input.
			double[] gradientA = recorder.gradientOfSize(OUT_A * IN_SIZE);
			for (int i = 0; i < OUT_A; i++) {
				for (int j = 0; j < IN_SIZE; j++) {
					Assert.assertEquals("weight a [" + i + ", " + j + "]",
							g[i] * x[j], gradientA[i * IN_SIZE + j], TOLERANCE);
				}
			}

			// Part B's weight gradient is the outer product of g[OUT_A:] with the input prefix.
			double[] gradientB = recorder.gradientOfSize(OUT_B * HIDDEN_SIZE);
			for (int i = 0; i < OUT_B; i++) {
				for (int j = 0; j < HIDDEN_SIZE; j++) {
					Assert.assertEquals("weight b [" + i + ", " + j + "]",
							g[OUT_A + i] * x[j], gradientB[i * HIDDEN_SIZE + j], TOLERANCE);
				}
			}

			// The input gradient is W_aᵀ·g_a plus W_bᵀ·g_b in the first HIDDEN_SIZE positions.
			Assert.assertEquals(IN_SIZE, inputGradient.length);
			for (int j = 0; j < IN_SIZE; j++) {
				double expected = 0.0;
				for (int i = 0; i < OUT_A; i++) {
					expected += weightA.toDouble(i * IN_SIZE + j) * g[i];
				}
				if (j < HIDDEN_SIZE) {
					for (int i = 0; i < OUT_B; i++) {
						expected += weightB.toDouble(i * HIDDEN_SIZE + j) * g[OUT_A + i];
					}
				}
				Assert.assertEquals("input " + j, expected, inputGradient[j], TOLERANCE);
			}
		}
	}

	/**
	 * An inline input-independent part — {@code { cache_read(row_cache, position) }}, which the
	 * loader wraps in a {@link org.almostrealism.model.SequentialBlock}, as in the GRU decoder's
	 * {@code [x | h]} concatenation — does not prevent the layer from being built for training: the
	 * dense part still receives the gradient of its own segment, the cache part contributes its row
	 * to the forward output, and the input gradient is {@code W_aᵀ·g_a} alone.
	 */
	@Test(timeout = 120000)
	public void concatBlocksTrainsAlongsideInlineCacheRead() {
		Random random = new Random(32);
		PackedCollection weightA = seeded(shape(OUT_A, IN_SIZE), random);
		PackedCollection cache = seeded(shape(2, CACHE_SIZE), random);
		PackedCollection input = seeded(shape(IN_SIZE), random);
		PackedCollection outputGradient = seeded(shape(OUT_A + CACHE_SIZE), random);

		Map<String, Object> args = new HashMap<>();
		args.put("in_size", IN_SIZE);
		args.put("position", 1);
		args.put("weight_a", weightA);
		args.put("row_cache", cache);

		GradientRecorder recorder = new GradientRecorder();
		Model model = new Model(shape(IN_SIZE), recorder);
		model.add(build("concat_read_grad", args));
		try (CompiledModel compiled = model.compile(true, true)) {
			double[] output = compiled.forward(input).doubleStream().toArray();
			double[] x = input.doubleStream().toArray();
			double[] g = outputGradient.doubleStream().toArray();

			Assert.assertEquals(OUT_A + CACHE_SIZE, output.length);
			for (int i = 0; i < OUT_A; i++) {
				Assert.assertEquals("segment a " + i, dot(weightA, i, x, IN_SIZE), output[i], TOLERANCE);
			}
			for (int i = 0; i < CACHE_SIZE; i++) {
				Assert.assertEquals("segment cache " + i, cache.toDouble(CACHE_SIZE + i),
						output[OUT_A + i], TOLERANCE);
			}

			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();

			double[] gradientA = recorder.gradientOfSize(OUT_A * IN_SIZE);
			for (int i = 0; i < OUT_A; i++) {
				for (int j = 0; j < IN_SIZE; j++) {
					Assert.assertEquals("weight a [" + i + ", " + j + "]",
							g[i] * x[j], gradientA[i * IN_SIZE + j], TOLERANCE);
				}
			}

			Assert.assertEquals(IN_SIZE, inputGradient.length);
			for (int j = 0; j < IN_SIZE; j++) {
				double expected = 0.0;
				for (int i = 0; i < OUT_A; i++) {
					expected += weightA.toDouble(i * IN_SIZE + j) * g[i];
				}
				Assert.assertEquals("input " + j, expected, inputGradient[j], TOLERANCE);
			}
		}
	}

	/**
	 * Builds the {@code concat_grad} fixture layer with the given part weights.
	 *
	 * @param weightA the first part's weights, shape {@code [OUT_A, IN_SIZE]}
	 * @param weightB the second part's weights, shape {@code [OUT_B, HIDDEN_SIZE]}
	 * @return the layer
	 */
	private Block concatGrad(PackedCollection weightA, PackedCollection weightB) {
		Map<String, Object> args = new HashMap<>();
		args.put("in_size", IN_SIZE);
		args.put("hidden_size", HIDDEN_SIZE);
		args.put("weight_a", weightA);
		args.put("weight_b", weightB);

		return build("concat_grad", args);
	}

	/**
	 * Builds a layer of the fixture program for an input of {@link #IN_SIZE}.
	 *
	 * @param layer the layer name
	 * @param args  the layer arguments
	 * @return the layer
	 */
	private Block build(String layer, Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FIXTURE), layer, shape(IN_SIZE), args);
	}

	/**
	 * The dot product of row {@code row} of a weight matrix with the first {@code size} values of
	 * the input.
	 *
	 * @param weights the weight matrix, with {@code size} columns
	 * @param row     the row index
	 * @param input   the input values
	 * @param size    the number of columns of the weight matrix
	 * @return the dot product
	 */
	private double dot(PackedCollection weights, int row, double[] input, int size) {
		double sum = 0.0;
		for (int j = 0; j < size; j++) {
			sum += weights.toDouble(row * size + j) * input[j];
		}
		return sum;
	}

	/**
	 * A collection of standard Gaussian values drawn from {@code random}.
	 *
	 * @param shape  the shape
	 * @param random the generator
	 * @return the collection
	 */
	private PackedCollection seeded(TraversalPolicy shape, Random random) {
		return new PackedCollection(shape).randnFill(random);
	}
}
