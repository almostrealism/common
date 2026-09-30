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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pins the shape a layer is built for when another layer calls it, over the layers of
 * {@code test_nested_layer_shape.pdsl}: a called layer that declares no {@code -> [shape]}
 * annotation is built for the signal at the point where the call is placed — after the stages
 * before it, or as the input of the {@code accum} that holds it — exactly as a built-in is.
 *
 * <p>Before this rule a called layer without an annotation was built for {@code [1, n]}, where
 * {@code n} was the last axis of its first {@code weight} argument: right for a layer over one
 * token's {@code [1, dim]} vector, wrong for anything else. Every nested build below failed on
 * master (commit 4da766e15) with {@code "conv1d() expects a [batch, channels, length] input
 * shape, got (1, 3)"}, because {@code smooth}'s first weight is a {@code [2, 2, 3]} convolution
 * kernel.</p>
 */
public class PdslNestedLayerShapeTest extends TestSuiteBase {

	/** Classpath location of the fixture program. */
	private static final String FIXTURE = "/pdsl/test_nested_layer_shape.pdsl";

	/** Batch size of the input. */
	private static final int BATCH = 1;

	/** Channel count, held constant by every layer. */
	private static final int CHANNELS = 2;

	/** Length of the input. */
	private static final int LENGTH = 8;

	/**
	 * Two calls of {@code smooth} in a row are each built for the {@code [batch, channels, length]}
	 * signal they receive, and compute what the same two convolutions written inline compute.
	 */
	@Test(timeout = 120000)
	public void calledLayerIsBuiltForItsPlacement() {
		assertNestedMatchesFlat("smooth_twice", shape(BATCH, CHANNELS, LENGTH));
	}

	/**
	 * A call placed after a stage that halves the length is built for the halved signal, not for
	 * the input of the layer that calls it.
	 */
	@Test(timeout = 120000)
	public void calledLayerFollowsAShapeChange() {
		assertNestedMatchesFlat("halve_then_smooth", shape(BATCH, CHANNELS, LENGTH / 2));
	}

	/** A call inside a residual {@code accum} is built for the accum's input. */
	@Test(timeout = 120000)
	public void calledLayerInsideAccumIsBuiltForItsInput() {
		assertNestedMatchesFlat("smooth_residual", shape(BATCH, CHANNELS, LENGTH));
	}

	/**
	 * A call bound with {@code let} is built where the name is placed, after the halving stage
	 * that follows the binding, so it computes what {@code halve_then_smooth} computes.
	 */
	@Test(timeout = 120000)
	public void letBoundCallIsBuiltWhereItIsPlaced() {
		Block bound = build("smooth_bound_before_halving");
		Assert.assertArrayEquals("output shape", new int[] {BATCH, CHANNELS, LENGTH / 2},
				bound.getOutputShape().extent());

		assertComputesSame("let-bound call vs inline stages", build("halve_then_smooth_flat"), bound);
	}

	/**
	 * Builds {@code <name>_nested} and {@code <name>_flat}, checks that the nested layer produces
	 * {@code expectedOutput}, and that it computes what its inline twin computes.
	 */
	private void assertNestedMatchesFlat(String name, TraversalPolicy expectedOutput) {
		Block nested = build(name + "_nested");
		Assert.assertArrayEquals(name + " output shape", expectedOutput.extent(), nested.getOutputShape().extent());

		assertComputesSame(name + ": nested vs inline stages", build(name + "_flat"), nested);
	}

	/** Asserts that two blocks compute the same output for the input, element by element. */
	private void assertComputesSame(String label, Block expected, Block actual) {
		double[] values = forward(expected).toArray();
		PackedCollection output = forward(actual);
		TraversalPolicy shape = output.getShape();
		assertEquals(label + " (element count)", values.length, shape.getTotalSize());
		assertEquals(label, 0.0, largestDeviation(shape, position -> values[shape.index(position)], output), 1e-6);
	}

	/** Builds a fixture layer for the {@code [batch, channels, length]} input. */
	private Block build(String layer) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FIXTURE), layer, shape(BATCH, CHANNELS, LENGTH), arguments());
	}

	/** Compiles a block on its own and runs the input through it. */
	private PackedCollection forward(Block block) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		return model.compile().forward(input());
	}

	/**
	 * The weights every fixture layer takes: {@code w}, a {@code [channels, channels, 3]} kernel,
	 * its {@code [channels]} bias, and {@code down}, a {@code [channels, channels, 2]} kernel that
	 * halves the length at stride 2.
	 */
	private Map<String, Object> arguments() {
		Map<String, Object> args = new HashMap<>();
		args.put("w", wave(shape(CHANNELS, CHANNELS, 3), 0.37, 0.1));
		args.put("bias", wave(shape(CHANNELS), 0.19, 0.3));
		args.put("down", wave(shape(CHANNELS, CHANNELS, 2), 0.53, 0.2));
		return args;
	}

	/** The {@code [batch, channels, length]} input. */
	private PackedCollection input() {
		return wave(shape(BATCH, CHANNELS, LENGTH), 0.61, 0.0);
	}

	/** A deterministic, non-degenerate collection: the sine of a scaled, shifted index ramp. */
	private PackedCollection wave(TraversalPolicy shape, double frequency, double phase) {
		return sin(integers(0, shape.getTotalSize()).multiply(frequency).add(phase)).reshape(shape).evaluate();
	}
}
