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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies {@link ClassifierFreeGuidance} against a host-side transcription of the reference
 * guidance algebra, which works in denoised space: {@code d = x - sigma * v} for each prediction,
 * {@code diff = d_c - d_u}, an optional projection of {@code diff} orthogonal to {@code d_c}, then
 * {@code d_g = d_c + (scale - 1) * g} and {@code v_g = (x - d_g) / sigma}.
 */
public class ClassifierFreeGuidanceTest extends TestSuiteBase {

	/** Channels of the synthetic latent. */
	private static final int CHANNELS = 3;

	/** Frames of the synthetic latent. */
	private static final int FRAMES = 5;

	/** Noise level used for every case. */
	private static final double SIGMA = 0.6;

	/** A scale of one must return the conditional prediction unchanged, whatever the projection. */
	@Test(timeout = 120000)
	public void unitScaleIsIdentity() {
		Random random = new Random(7);
		PackedCollection x = latent(random);
		PackedCollection cond = latent(random);
		PackedCollection uncond = latent(random);

		ClassifierFreeGuidance guidance = new ClassifierFreeGuidance(1.0, 1.0);
		assertFalse(guidance.isActive());

		PackedCollection out = guidance.guide(x, SIGMA, cond, uncond).evaluate();
		assertClose(cond, out);
	}

	/** Plain guidance ({@code apg = 0}) is {@code v_c + (scale - 1) * (v_c - v_u)}. */
	@Test(timeout = 120000)
	public void plainGuidanceMatchesReference() {
		Random random = new Random(11);
		PackedCollection x = latent(random);
		PackedCollection cond = latent(random);
		PackedCollection uncond = latent(random);
		double scale = 3.0;

		PackedCollection out = new ClassifierFreeGuidance(scale, 0.0).guide(x, SIGMA, cond, uncond).evaluate();
		assertClose(reference(x, cond, uncond, scale, 0.0), out);
	}

	/** Full adaptive projection ({@code apg = 1}) removes the component parallel to the conditional estimate. */
	@Test(timeout = 120000)
	public void projectedGuidanceMatchesReference() {
		Random random = new Random(13);
		PackedCollection x = latent(random);
		PackedCollection cond = latent(random);
		PackedCollection uncond = latent(random);
		double scale = 2.5;

		PackedCollection out = new ClassifierFreeGuidance(scale).guide(x, SIGMA, cond, uncond).evaluate();
		assertClose(reference(x, cond, uncond, scale, 1.0), out);
	}

	/** A partial blend interpolates between the plain and projected differences. */
	@Test(timeout = 120000)
	public void blendedGuidanceMatchesReference() {
		Random random = new Random(17);
		PackedCollection x = latent(random);
		PackedCollection cond = latent(random);
		PackedCollection uncond = latent(random);
		double scale = 4.0;
		double apg = 0.3;

		PackedCollection out = new ClassifierFreeGuidance(scale, apg).guide(x, SIGMA, cond, uncond).evaluate();
		assertClose(reference(x, cond, uncond, scale, apg), out);
	}

	/**
	 * Host-side reference guidance, in denoised space exactly as the reference model computes it.
	 *
	 * @param x      noisy latent
	 * @param cond   conditional prediction
	 * @param uncond unconditional prediction
	 * @param scale  guidance scale
	 * @param apg    projection blend
	 * @return the guided prediction in model-output space
	 */
	private double[] reference(PackedCollection x, PackedCollection cond, PackedCollection uncond,
							   double scale, double apg) {
		int n = CHANNELS * FRAMES;
		double[] dc = new double[n];
		double[] du = new double[n];
		double[] diff = new double[n];
		double normSq = 0.0;
		for (int i = 0; i < n; i++) {
			dc[i] = x.toDouble(i) - SIGMA * cond.toDouble(i);
			du[i] = x.toDouble(i) - SIGMA * uncond.toDouble(i);
			diff[i] = dc[i] - du[i];
			normSq += dc[i] * dc[i];
		}

		double norm = Math.sqrt(normSq);
		double dot = 0.0;
		for (int i = 0; i < n; i++) {
			dot += diff[i] * dc[i] / norm;
		}

		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			double orthogonal = diff[i] - dot * dc[i] / norm;
			double g = apg * orthogonal + (1.0 - apg) * diff[i];
			double dg = dc[i] + (scale - 1.0) * g;
			out[i] = (x.toDouble(i) - dg) / SIGMA;
		}
		return out;
	}

	/**
	 * A random latent of the test shape.
	 *
	 * @param random the source of values
	 * @return the latent
	 */
	private PackedCollection latent(Random random) {
		return new PackedCollection(new TraversalPolicy(1, CHANNELS, FRAMES)).randnFill(random);
	}

	/**
	 * Asserts element-wise closeness of an evaluated collection to a reference collection.
	 *
	 * @param expected the reference values
	 * @param actual   the evaluated values
	 */
	private void assertClose(PackedCollection expected, PackedCollection actual) {
		assertEquals(expected.getShape().getTotalSize(), actual.getShape().getTotalSize());
		for (int i = 0; i < expected.getShape().getTotalSize(); i++) {
			assertEquals(expected.toDouble(i), actual.toDouble(i), 1e-4);
		}
	}

	/**
	 * Asserts element-wise closeness of an evaluated collection to host-side reference values.
	 *
	 * @param expected the reference values
	 * @param actual   the evaluated values
	 */
	private void assertClose(double[] expected, PackedCollection actual) {
		assertEquals(expected.length, actual.getShape().getTotalSize());
		for (int i = 0; i < expected.length; i++) {
			assertEquals("element " + i, expected[i], actual.toDouble(i), 1e-4);
		}
	}
}
