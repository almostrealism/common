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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Standalone tests of the {@code scaled_dot_product} and {@code key_mask} PDSL primitives, each
 * exercised by its own single-primitive layer in {@code /pdsl/test_sdpa_primitives.pdsl} and
 * compared against a pure-Java reference. These pin the primitives independently of the scaled
 * dot-product attention migration that motivated them, so they remain meaningful whatever the
 * {@code sdpa} asset does.
 */
public class SdpaPrimitivesTest extends TestSuiteBase implements AttentionFeatures {

	/** Classpath location of the primitive fixtures. */
	private static final String FIXTURE = "/pdsl/test_sdpa_primitives.pdsl";

	/** Batch size the primitives are exercised at. */
	private static final int BATCH = 1;

	/** Head count of the fixtures. */
	private static final int HEADS = 2;

	/** Query length of the fixtures. */
	private static final int QUERIES = 3;

	/** Key length of the fixtures. */
	private static final int KEYS = 4;

	/** Dimension per head of the fixtures. */
	private static final int DIM = 5;

	/**
	 * Runs one primitive layer of the fixture over {@code input} with a single tensor argument.
	 *
	 * @param layer      the fixture layer name
	 * @param inputShape the layer's input shape
	 * @param argName    the layer's parameter name
	 * @param argValue   the bound argument
	 * @param input      the input tensor to forward
	 * @return the layer's output, flattened
	 */
	private double[] run(String layer, TraversalPolicy inputShape,
						 String argName, PackedCollection argValue, PackedCollection input) {
		PdslLoader loader = new PdslLoader();
		Map<String, Object> args = new HashMap<>();
		args.put(argName, argValue);
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(FIXTURE), layer, inputShape, args));
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input).doubleStream().toArray();
	}

	/**
	 * {@code scaled_dot_product(other, true)} computes {@code Q Kᵀ}: for a
	 * {@code [batch, heads, queries, dim]} input and a {@code [batch, heads, keys, dim]} operand,
	 * the result is {@code sum_d input[b,h,i,d] other[b,h,j,d]} at {@code [b,h,i,j]}.
	 */
	@Test(timeout = 120000)
	public void scaledDotProductTransposeIsQKt() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, DIM)).randnFill();
		PackedCollection other = new PackedCollection(shape(BATCH, HEADS, KEYS, DIM)).randnFill();
		double[] in = input.doubleStream().toArray();
		double[] o = other.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * KEYS];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int j = 0; j < KEYS; j++) {
					double dot = 0;
					for (int d = 0; d < DIM; d++) {
						dot += in[(h * QUERIES + i) * DIM + d] * o[(h * KEYS + j) * DIM + d];
					}
					expected[(h * QUERIES + i) * KEYS + j] = dot;
				}
			}
		}

		double[] actual = run("sdp_transpose", shape(BATCH, HEADS, QUERIES, DIM), "other", other, input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			assertEquals("Q Kᵀ element " + idx, expected[idx], actual[idx], 1e-4);
		}
	}

	/**
	 * {@code scaled_dot_product(other, false)} computes {@code A V}: for a
	 * {@code [batch, heads, queries, keys]} input and a {@code [batch, heads, keys, dim]} operand,
	 * the result is {@code sum_j input[b,h,i,j] other[b,h,j,d]} at {@code [b,h,i,d]}.
	 */
	@Test(timeout = 120000)
	public void scaledDotProductPlainIsAv() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, KEYS)).randnFill();
		PackedCollection other = new PackedCollection(shape(BATCH, HEADS, KEYS, DIM)).randnFill();
		double[] in = input.doubleStream().toArray();
		double[] o = other.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * DIM];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int d = 0; d < DIM; d++) {
					double sum = 0;
					for (int j = 0; j < KEYS; j++) {
						sum += in[(h * QUERIES + i) * KEYS + j] * o[(h * KEYS + j) * DIM + d];
					}
					expected[(h * QUERIES + i) * DIM + d] = sum;
				}
			}
		}

		double[] actual = run("sdp_plain", shape(BATCH, HEADS, QUERIES, KEYS), "other", other, input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			assertEquals("A V element " + idx, expected[idx], actual[idx], 1e-4);
		}
	}

	/**
	 * {@code key_mask(mask)} adds {@code (mask[b,j] - 1) * MASKED_LOGIT_PENALTY} to every score at
	 * key {@code j}: zero for a valid key (mask 1) and the large penalty for a masked one (mask 0),
	 * broadcast across the head and query axes.
	 */
	@Test(timeout = 120000)
	public void keyMaskBiasesMaskedKeys() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, KEYS)).randnFill();
		PackedCollection mask = new PackedCollection(shape(BATCH, KEYS));
		int validKeys = 2;
		lessThan(integers(0, KEYS), c((double) validKeys), c(1.0), c(0.0))
				.into(mask.traverseEach()).evaluate();

		double[] in = input.doubleStream().toArray();
		double[] m = mask.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * KEYS];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int j = 0; j < KEYS; j++) {
					int idx = (h * QUERIES + i) * KEYS + j;
					expected[idx] = in[idx] + (m[j] - 1.0) * MASKED_LOGIT_PENALTY;
				}
			}
		}

		double[] actual = run("km", shape(BATCH, HEADS, QUERIES, KEYS), "mask", mask, input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			// Masked scores are near -1e9, where single precision loses the sub-unit input term;
			// tolerate that with a magnitude-relative bound while pinning valid scores tightly.
			double tolerance = Math.max(1e-3, Math.abs(expected[idx]) * 1e-5);
			assertEquals("key_mask element " + idx, expected[idx], actual[idx], tolerance);
		}
	}

	/**
	 * {@code key_mask(mask)} rejects a mask whose shape does not match the score shape's batch and
	 * key extents rather than letting the broadcast silently reshape it: a rank other than two, a
	 * mismatched batch, and a mismatched key count are each rejected.
	 */
	@Test(timeout = 60000)
	public void keyMaskRejectsMismatchedMaskShape() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(FIXTURE);
		TraversalPolicy scoresShape = shape(BATCH, HEADS, QUERIES, KEYS);

		try {
			loader.buildLayer(program, "km", scoresShape,
					argMap("mask", new PackedCollection(shape(BATCH, KEYS, 1))));
			Assert.fail("key_mask should reject a mask that is not [batch, keys]");
		} catch (PdslParseException expected) {
			// expected
		}

		try {
			loader.buildLayer(program, "km", scoresShape,
					argMap("mask", new PackedCollection(shape(BATCH + 1, KEYS))));
			Assert.fail("key_mask should reject a mask whose batch does not match the score shape");
		} catch (PdslParseException expected) {
			// expected
		}

		try {
			loader.buildLayer(program, "km", scoresShape,
					argMap("mask", new PackedCollection(shape(BATCH, KEYS + 1))));
			Assert.fail("key_mask should reject a mask whose key count does not match the score shape");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * {@code scaled_dot_product(other, transpose)} rejects an operand whose batch, head, or
	 * contracted extent is incompatible with the input rather than reinterpreting it: a mismatched
	 * head count (transpose) and a mismatched contracted key count (plain) are each rejected.
	 */
	@Test(timeout = 60000)
	public void scaledDotProductRejectsIncompatibleOperand() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(FIXTURE);

		try {
			loader.buildLayer(program, "sdp_transpose", shape(BATCH, HEADS, QUERIES, DIM),
					argMap("other", new PackedCollection(shape(BATCH, HEADS + 1, KEYS, DIM))));
			Assert.fail("scaled_dot_product(true) should reject an operand with a mismatched head count");
		} catch (PdslParseException expected) {
			// expected
		}

		try {
			loader.buildLayer(program, "sdp_plain", shape(BATCH, HEADS, QUERIES, KEYS),
					argMap("other", new PackedCollection(shape(BATCH, HEADS, KEYS + 1, DIM))));
			Assert.fail("scaled_dot_product(false) should reject an operand with a mismatched key count");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * Builds a single-entry argument map for a fixture layer.
	 *
	 * @param name  the layer's parameter name
	 * @param value the bound argument
	 * @return the argument map
	 */
	private static Map<String, Object> argMap(String name, PackedCollection value) {
		Map<String, Object> args = new HashMap<>();
		args.put(name, value);
		return args;
	}
}
