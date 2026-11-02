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
 * Standalone tests of the {@code embedding} and {@code log_softmax} built-ins: the token lookup, which
 * replaces every input value with its row of a {@code [vocab, dim]} table, and the row-wise
 * log-softmax, which replaces every row of a {@code [rows, size]} input with its log-probabilities.
 * Each case builds a layer of {@code /pdsl/test_embedding_log_softmax_primitives.pdsl}, compiles it and
 * compares its output, or its gradient, with the same quantity computed on the host; the rejection
 * cases check each built-in's argument contract.
 */
public class EmbeddingLogSoftmaxPrimitivesTest extends TestSuiteBase implements ModelTestFeatures {

	/** Classpath location of the primitive fixtures. */
	private static final String FIXTURE = "/pdsl/test_embedding_log_softmax_primitives.pdsl";

	/** Rows of the embedding table. */
	private static final int VOCAB = 5;

	/** Columns of the embedding table: the width of every embedding vector. */
	private static final int DIM = 3;

	/** Agreement required with the double-precision host computation. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * Every id of a {@code [positions]} input is replaced by its row of the table, in order, so the
	 * output is {@code [positions, dim]}; an id that appears twice selects the same row twice, and a
	 * row no id selects does not appear.
	 */
	@Test(timeout = 120000)
	public void embeddingGathersTheRowOfEveryId() {
		assertGathers(seeded(shape(VOCAB, DIM), new Random(1)), PackedCollection.of(4, 0, 2, 4), shape(4));
	}

	/**
	 * The output keeps the shape of the ids and appends the embedding axis: a {@code [batch, positions]}
	 * block of ids becomes {@code [batch, positions, dim]} vectors.
	 */
	@Test(timeout = 120000)
	public void embeddingKeepsTheShapeOfTheIds() {
		assertGathers(seeded(shape(VOCAB, DIM), new Random(2)),
				PackedCollection.of(1, 3, 0, 2, 2, 4).reshape(shape(2, 3)), shape(2, 3));
	}

	/**
	 * The table is a registered weight of the stage: the backward pass delivers to each row the sum of
	 * the output gradients of the positions that selected it, so a row selected twice receives both, and
	 * a row no position selected receives none.
	 */
	@Test(timeout = 300000)
	public void embeddingAccumulatesEveryPositionsGradientIntoItsRow() {
		PackedCollection table = seeded(shape(VOCAB, DIM), new Random(3));
		PackedCollection ids = PackedCollection.of(2, 0, 2, 3);
		PackedCollection outputGradient = seeded(shape(4, DIM), new Random(4));

		GradientRecorder recorder = new GradientRecorder();
		Model model = new Model(shape(4), recorder);
		model.add(build("lookup", shape(4), Map.of("table", table)));
		try (CompiledModel compiled = model.compile(true)) {
			compiled.forward(ids);
			compiled.backward(outputGradient);

			double[] gradient = recorder.gradientOfSize(VOCAB * DIM);
			double[] g = outputGradient.doubleStream().toArray();
			double[] id = ids.doubleStream().toArray();
			double[] expected = new double[VOCAB * DIM];
			for (int p = 0; p < id.length; p++) {
				for (int d = 0; d < DIM; d++) {
					expected[(int) id[p] * DIM + d] += g[p * DIM + d];
				}
			}

			for (int i = 0; i < expected.length; i++) {
				Assert.assertEquals("gradient of row " + i / DIM + " column " + i % DIM,
						expected[i], gradient[i], TOLERANCE);
			}
		}
	}

	/**
	 * Every row of a {@code [rows, size]} input becomes {@code x - log(sum(exp(x)))} of that row, so the
	 * exponentials of each output row sum to one.
	 */
	@Test(timeout = 120000)
	public void logSoftmaxMatchesTheFormula() {
		int rows = 3;
		int size = 5;
		PackedCollection input = seeded(shape(rows, size), new Random(5));

		Model model = new Model(shape(rows, size));
		model.add(build("log_probabilities", shape(rows, size), new HashMap<>()));
		try (CompiledModel compiled = model.compile(false)) {
			double[] actual = compiled.forward(input).doubleStream().toArray();
			double[] expected = expectedLogProbabilities(input.doubleStream().toArray(), size);

			Assert.assertEquals("output size", rows * size, actual.length);
			for (int i = 0; i < expected.length; i++) {
				Assert.assertEquals("row " + i / size + " entry " + i % size, expected[i], actual[i], TOLERANCE);
			}
		}
	}

	/**
	 * The stage passes its gradient to its input: for {@code L = sum(output * G)} the gradient of row
	 * {@code r} is {@code G_r - softmax(x_r) * sum(G_r)}, the derivative of the log-softmax.
	 */
	@Test(timeout = 300000)
	public void logSoftmaxPassesTheGradientOfEachRow() {
		int rows = 2;
		int size = 4;
		PackedCollection input = seeded(shape(rows, size), new Random(6));
		PackedCollection outputGradient = seeded(shape(rows, size), new Random(7));

		Model model = new Model(shape(rows, size));
		model.add(build("log_probabilities", shape(rows, size), new HashMap<>()));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] actual = compiled.backward(outputGradient).doubleStream().toArray();

			double[] logProbabilities = expectedLogProbabilities(input.doubleStream().toArray(), size);
			double[] g = outputGradient.doubleStream().toArray();
			for (int r = 0; r < rows; r++) {
				double total = 0.0;
				for (int i = 0; i < size; i++) {
					total += g[r * size + i];
				}

				for (int i = 0; i < size; i++) {
					double expected = g[r * size + i] - Math.exp(logProbabilities[r * size + i]) * total;
					Assert.assertEquals("row " + r + " entry " + i, expected, actual[r * size + i], TOLERANCE);
				}
			}
		}
	}

	/**
	 * Each built-in's argument contract: {@code embedding} takes exactly one argument, a bound tensor,
	 * and {@code log_softmax} none, rejecting an input that is not {@code [rows, size]}; these are
	 * {@link PdslParseException}s. The embedding table's shape is checked by the layer itself, as it is
	 * for a Java caller, with an {@link IllegalArgumentException}.
	 */
	@Test(timeout = 60000)
	public void builtinsRejectAnInvalidCall() {
		assertRejected(PdslParseException.class,
				() -> build("lookup_without_table", shape(4), new HashMap<>()));
		assertRejected(PdslParseException.class,
				() -> build("lookup", shape(4), Map.of("table", cp(seeded(shape(VOCAB, DIM), new Random(8))))));
		assertRejected(IllegalArgumentException.class,
				() -> build("lookup", shape(4), Map.of("table", seeded(shape(VOCAB * DIM), new Random(9)))));
		assertRejected(PdslParseException.class,
				() -> build("log_probabilities_with_argument", shape(2, 4), Map.of("temperature", 2.0)));
		assertRejected(PdslParseException.class,
				() -> build("log_probabilities", shape(2, 3, 4), new HashMap<>()));
		assertRejected(PdslParseException.class,
				() -> build("log_probabilities", shape(4), new HashMap<>()));
	}

	/**
	 * Builds the {@code lookup} layer for ids of {@code idShape}, forwards {@code ids} and requires the
	 * output to be the table row of every id, exactly, in the shape of the ids with a trailing
	 * {@code dim} axis.
	 *
	 * @param table   the {@code [vocab, dim]} table
	 * @param ids     the token ids
	 * @param idShape the shape of the ids
	 */
	private void assertGathers(PackedCollection table, PackedCollection ids, TraversalPolicy idShape) {
		Block block = build("lookup", idShape, Map.of("table", table));
		Assert.assertArrayEquals("output shape", idShape.appendDimension(DIM).extent(),
				block.getOutputShape().extent());

		Model model = new Model(idShape);
		model.add(block);
		try (CompiledModel compiled = model.compile(false)) {
			double[] actual = compiled.forward(ids).doubleStream().toArray();
			double[] rows = table.doubleStream().toArray();
			double[] id = ids.doubleStream().toArray();

			Assert.assertEquals("output size", id.length * DIM, actual.length);
			for (int p = 0; p < id.length; p++) {
				for (int d = 0; d < DIM; d++) {
					Assert.assertEquals("position " + p + " column " + d,
							rows[(int) id[p] * DIM + d], actual[p * DIM + d], 0.0);
				}
			}
		}
	}

	/**
	 * The log-softmax of every row of {@code values}, computed on the host in double precision with
	 * the row maximum subtracted first.
	 *
	 * @param values the rows, one after another
	 * @param size   the length of a row
	 * @return the log-probabilities, row by row
	 */
	private static double[] expectedLogProbabilities(double[] values, int size) {
		double[] result = new double[values.length];
		for (int r = 0; r < values.length / size; r++) {
			double max = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < size; i++) {
				max = Math.max(max, values[r * size + i]);
			}

			double total = 0.0;
			for (int i = 0; i < size; i++) {
				total += Math.exp(values[r * size + i] - max);
			}

			for (int i = 0; i < size; i++) {
				result[r * size + i] = values[r * size + i] - max - Math.log(total);
			}
		}
		return result;
	}

	/**
	 * Builds a fixture layer.
	 *
	 * @param layer the fixture layer name
	 * @param shape the input shape
	 * @param args  the layer's arguments
	 * @return the layer
	 */
	private Block build(String layer, TraversalPolicy shape, Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FIXTURE), layer, shape, args);
	}

	/**
	 * Requires {@code build} to be rejected with an exception of the given type; an exception of any
	 * other type propagates.
	 *
	 * @param expected the type of the exception the rejection must raise
	 * @param build    builds the layer that must be rejected
	 */
	private void assertRejected(Class<? extends RuntimeException> expected, Runnable build) {
		try {
			build.run();
		} catch (RuntimeException rejection) {
			if (!expected.isInstance(rejection)) throw rejection;
			log("rejected: " + rejection.getMessage());
			return;
		}
		Assert.fail("expected " + expected.getSimpleName());
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
