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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Standalone tests of the {@code modulate(factors, offsets, axis)} built-in, the feature-wise affine
 * counterpart of the per-axis {@code scale(factors, axis)}: every element at a position of the axis
 * is multiplied by that position's factor and has its offset added, the factors and offsets holding
 * one value per batch entry and position. Each fixture layer of
 * {@code /pdsl/test_modulate_primitives.pdsl} applies one stage and is compared with a reference
 * computed on the host, so the built-in is pinned independently of the feed-forward migration that
 * introduced it: its forward values, the shapes it rejects, the gradient it passes back, and that it
 * reads its factors and offsets afresh on every forward pass.
 */
public class ModulatePrimitiveTest extends TestSuiteBase {

	/** Classpath location of the fixtures. */
	private static final String FIXTURE = "/pdsl/test_modulate_primitives.pdsl";

	/** Largest absolute difference permitted from a host reference of single-precision arithmetic. */
	private static final double TOLERANCE = 1e-6;

	/**
	 * {@code modulate(factors, offsets, 2)} multiplies every element at position {@code n} of axis 2
	 * by {@code factors[0, n]} and adds {@code offsets[0, n]}, across the head and feature axes of a
	 * {@code [1, heads, positions, width]} input; a factor of zero replaces the position by its offset.
	 */
	@Test(timeout = 120000)
	public void axisModulationScalesAndOffsetsEachPosition() {
		int heads = 2;
		int positions = 4;
		int width = 3;
		TraversalPolicy inputShape = shape(1, heads, positions, width);
		PackedCollection factors = PackedCollection.of(1.5, 0.0, -0.5, 2.0).reshape(shape(1, positions));
		PackedCollection offsets = PackedCollection.of(0.25, 1.0, -1.0, 0.5).reshape(shape(1, positions));
		PackedCollection input = wave(inputShape, 0.9, 0.31);
		double[] in = input.doubleStream().toArray();
		double[] f = factors.doubleStream().toArray();
		double[] o = offsets.doubleStream().toArray();

		double[] actual = forward(inputShape, build("position_modulation", inputShape, factors, offsets), input);
		Assert.assertEquals(in.length, actual.length);
		for (int h = 0; h < heads; h++) {
			for (int n = 0; n < positions; n++) {
				for (int d = 0; d < width; d++) {
					int idx = (h * positions + n) * width + d;
					Assert.assertEquals("element " + idx, in[idx] * f[n] + o[n], actual[idx], TOLERANCE);
				}
			}
		}
	}

	/**
	 * Along the channel axis of a {@code [batch, seq_len, dim]} activation every batch entry has its
	 * own factor and offset per channel, applied at every position:
	 * {@code out[b, s, d] = in[b, s, d] * factors[b, d] + offsets[b, d]}. This is the modulation an
	 * adaptive layer normalization applies to its normalized activation.
	 */
	@Test(timeout = 120000)
	public void channelModulationGivesEachBatchEntryItsOwnFactorsAndOffsets() {
		TraversalPolicy inputShape = shape(2, 3, 5);
		TraversalPolicy modulationShape = shape(2, 5);
		PackedCollection factors = cp(wave(modulationShape, 0.6, 0.47)).add(1.0).evaluate();
		PackedCollection offsets = wave(modulationShape, 0.7, 0.53);
		PackedCollection input = wave(inputShape, 1.1, 0.29);

		double[] actual = forward(inputShape, build("position_modulation", inputShape, factors, offsets), input);
		assertModulatedChannels("channel modulation", input, factors, offsets, actual);
	}

	/**
	 * The factors and the offsets must each be exactly {@code [batch, length(axis)]}. Three factors
	 * for four positions are rejected, and so are offsets with the right count in another layout — a
	 * transposed {@code [4, 1]} and a flat {@code [4]} — rather than being reinterpreted by the
	 * broadcast.
	 */
	@Test(timeout = 60000)
	public void axisModulationRejectsFactorsOrOffsetsOfTheWrongShape() {
		TraversalPolicy inputShape = shape(1, 2, 4, 3);
		PackedCollection fourByPosition = PackedCollection.of(1.0, 2.0, 3.0, 4.0).reshape(shape(1, 4));
		assertRejected("position_modulation", inputShape,
				PackedCollection.of(1.0, 2.0, 3.0).reshape(shape(1, 3)), fourByPosition,
				"modulate() should reject three factors for four positions");
		assertRejected("position_modulation", inputShape, fourByPosition,
				PackedCollection.of(1.0, 2.0, 3.0, 4.0).reshape(shape(4, 1)),
				"modulate() should reject transposed offsets");
		assertRejected("position_modulation", inputShape, fourByPosition,
				PackedCollection.of(1.0, 2.0, 3.0, 4.0),
				"modulate() should reject flat offsets");
	}

	/**
	 * The axis must be a non-batch axis of the input: the batch axis itself and an axis past the last
	 * one of a four-axis input are rejected.
	 */
	@Test(timeout = 60000)
	public void axisModulationRejectsAnAxisThatIsNotANonBatchAxisOfTheInput() {
		TraversalPolicy inputShape = shape(1, 2, 4, 3);
		PackedCollection values = PackedCollection.of(1.0, 2.0).reshape(shape(1, 2));
		assertRejected("batch_axis_modulation", inputShape, values, values,
				"modulate(factors, offsets, 0) should reject the batch axis");
		assertRejected("beyond_axis_modulation", inputShape, values, values,
				"modulate(factors, offsets, 4) should reject an axis a four-axis input does not have");
	}

	/** A call that leaves out the axis does not say which positions the values belong to, and is rejected. */
	@Test(timeout = 60000)
	public void modulateRejectsACallWithoutItsAxis() {
		PackedCollection values = PackedCollection.of(1.0, 2.0, 3.0, 4.0).reshape(shape(1, 4));
		assertRejected("axis_free_modulation", shape(1, 2, 4, 3), values, values,
				"modulate(factors, offsets) should reject a call without the axis");
	}

	/**
	 * The factors and offsets do not depend on the input, so the stage's Jacobian is diagonal, with
	 * each element's factor on the diagonal: the backward pass delivers its output gradient, times the
	 * factor of each element's batch entry and channel, to its input.
	 */
	@Test(timeout = 120000)
	public void axisModulationPassesTheGradientTimesItsFactors() {
		TraversalPolicy inputShape = shape(2, 3, 4);
		TraversalPolicy modulationShape = shape(2, 4);
		PackedCollection factors = cp(wave(modulationShape, 0.5, 0.61)).add(1.0).evaluate();
		PackedCollection offsets = wave(modulationShape, 0.4, 0.37);
		PackedCollection input = wave(inputShape, 0.8, 0.19);
		PackedCollection outputGradient = wave(inputShape, 0.6, 0.43);
		double[] f = factors.doubleStream().toArray();
		double[] g = outputGradient.doubleStream().toArray();

		Model model = new Model(inputShape);
		model.add(build("position_modulation", inputShape, factors, offsets));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();
			Assert.assertEquals(g.length, inputGradient.length);
			for (int i = 0; i < g.length; i++) {
				int batch = i / (3 * 4);
				Assert.assertEquals("input gradient " + i, g[i] * f[batch * 4 + i % 4], inputGradient[i], TOLERANCE);
			}
		}
	}

	/**
	 * The factors and offsets are read from the collections they are bound to on every forward pass,
	 * so a caller that writes new values between passes (a diffusion transformer refreshing its
	 * conditioning before each denoising step) needs no rebuild: one compiled stage, run twice with
	 * different values, applies the values of each pass.
	 */
	@Test(timeout = 120000)
	public void axisModulationReadsItsFactorsAndOffsetsOnEveryForwardPass() {
		TraversalPolicy inputShape = shape(2, 3, 4);
		TraversalPolicy modulationShape = shape(2, 4);
		PackedCollection factors = new PackedCollection(modulationShape);
		PackedCollection offsets = new PackedCollection(modulationShape);
		PackedCollection firstFactors = cp(wave(modulationShape, 0.5, 0.61)).add(1.0).evaluate();
		PackedCollection firstOffsets = wave(modulationShape, 0.4, 0.37);
		PackedCollection laterFactors = cp(wave(modulationShape, 0.7, 0.23)).add(0.5).evaluate();
		PackedCollection laterOffsets = wave(modulationShape, 0.9, 0.17);
		PackedCollection input = wave(inputShape, 0.8, 0.19);

		Model model = new Model(inputShape);
		model.add(build("position_modulation", inputShape, factors, offsets));
		try (CompiledModel compiled = model.compile(false)) {
			factors.setFrom(0, firstFactors);
			offsets.setFrom(0, firstOffsets);
			assertModulatedChannels("first pass", input, firstFactors, firstOffsets,
					compiled.forward(input).doubleStream().toArray());

			factors.setFrom(0, laterFactors);
			offsets.setFrom(0, laterOffsets);
			assertModulatedChannels("second pass", input, laterFactors, laterOffsets,
					compiled.forward(input).doubleStream().toArray());
		}
	}

	/**
	 * Asserts that every element of a {@code [batch, seq_len, dim]} output is its input times the
	 * factor of its batch entry and channel, plus that channel's offset.
	 *
	 * @param label   the comparison, for failure messages
	 * @param input   the input
	 * @param factors the factors, {@code [batch, dim]}
	 * @param offsets the offsets, {@code [batch, dim]}
	 * @param actual  the output, flattened
	 */
	private void assertModulatedChannels(String label, PackedCollection input, PackedCollection factors,
										 PackedCollection offsets, double[] actual) {
		double[] in = input.doubleStream().toArray();
		double[] f = factors.doubleStream().toArray();
		double[] o = offsets.doubleStream().toArray();
		int dim = factors.getShape().length(1);
		int perBatch = in.length / factors.getShape().length(0);
		Assert.assertEquals(label + " length", in.length, actual.length);
		for (int i = 0; i < in.length; i++) {
			int channel = (i / perBatch) * dim + i % dim;
			Assert.assertEquals(label + " element " + i, in[i] * f[channel] + o[channel], actual[i], TOLERANCE);
		}
	}

	/**
	 * Builds a fixture layer from {@link #FIXTURE} with the given factors and offsets.
	 *
	 * @param name       the fixture layer
	 * @param inputShape the layer's input shape
	 * @param factors    the factors to bind
	 * @param offsets    the offsets to bind
	 * @return the layer
	 */
	private Block build(String name, TraversalPolicy inputShape, PackedCollection factors,
						PackedCollection offsets) {
		Map<String, Object> args = new HashMap<>();
		args.put("factors", factors);
		args.put("offsets", offsets);
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FIXTURE), name, inputShape, args);
	}

	/**
	 * Compiles a block as a one-block model, forwards the input once and returns the output.
	 *
	 * @param inputShape the block's input shape
	 * @param block      the block
	 * @param input      the input
	 * @return the output, flattened
	 */
	private double[] forward(TraversalPolicy inputShape, Block block, PackedCollection input) {
		Model model = new Model(inputShape);
		model.add(block);
		try (CompiledModel compiled = model.compile(false)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/**
	 * Asserts that building a fixture layer with the given factors and offsets is rejected with a
	 * {@link PdslParseException}.
	 *
	 * @param name       the fixture layer
	 * @param inputShape the layer's input shape
	 * @param factors    the factors to bind
	 * @param offsets    the offsets to bind
	 * @param message    the failure message when the layer is not rejected
	 */
	private void assertRejected(String name, TraversalPolicy inputShape, PackedCollection factors,
								PackedCollection offsets, String message) {
		try {
			build(name, inputShape, factors, offsets);
			Assert.fail(message);
		} catch (PdslParseException expected) {
			log("rejected " + name + ": " + expected.getMessage());
		}
	}

	/**
	 * A deterministic collection, {@code amplitude * sin(frequency * i + 0.3)} for element {@code i},
	 * computed on the device.
	 *
	 * @param shape     the shape of the collection
	 * @param amplitude the amplitude of the sinusoid
	 * @param frequency the angular step between consecutive elements
	 * @return the collection
	 */
	private PackedCollection wave(TraversalPolicy shape, double amplitude, double frequency) {
		return sin(integers(0, shape.getTotalSize()).multiply(frequency).add(0.3))
				.multiply(amplitude).reshape(shape).evaluate();
	}
}
