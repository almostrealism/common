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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

/**
 * Pins the values {@link WaveDetailsFactory} reports as feature similarity, which
 * is documented as the trimmed mean of per-frame cosine similarities and so must
 * lie in [-1, 1]: an item is maximally similar to itself and to a rescaled copy of
 * itself, and unrelated features score near zero. The pairwise and the batched
 * paths must agree.
 */
public class WaveDetailsSimilarityTest extends TestSuiteBase {

	/** Audio sample rate of the test items. */
	private static final int SAMPLE_RATE = 44100;

	/** Audio duration of the test items, in seconds. */
	private static final double DURATION = 11.0;

	/** Builds an item whose active-model features are {@code [frames, bins]} gaussian noise times {@code scale}. */
	private static WaveDetails item(String identifier, int frames, int bins, long seed, double scale) {
		Random random = new Random(seed);
		double[] values = new double[frames * bins];
		for (int i = 0; i < values.length; i++) values[i] = scale * random.nextGaussian();

		PackedCollection features = new PackedCollection(frames, bins);
		features.set(0, values);

		WaveDetails details = new WaveDetails(identifier, SAMPLE_RATE);
		details.setSampleRate(SAMPLE_RATE);
		details.setFrameCount((int) (SAMPLE_RATE * DURATION));
		details.putFeatures(new WaveFeatures("model", frames / DURATION, 1, bins, frames, features));
		details.setActiveModelIdentifier("model");
		return details;
	}

	/** Similarity of noise features, their rescaled copies and unrelated noise, at several shapes. */
	@Test(timeout = 120000)
	public void similarityIsACosine() {
		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);

		for (int[] shape : new int[][] { { 4, 8 }, { 64, 64 }, { 237, 256 } }) {
			WaveDetails a = item("a", shape[0], shape[1], 1, 1.0);
			WaveDetails scaled = item("a-scaled", shape[0], shape[1], 1, 7.5);
			WaveDetails b = item("b", shape[0], shape[1], 2, 1.0);

			String label = shape[0] + "x" + shape[1];
			Assert.assertEquals("self-similarity " + label, 1.0, factory.similarity(a, a), 1e-4);
			Assert.assertEquals("similarity to a rescaled copy " + label,
					1.0, factory.similarity(a, scaled), 1e-4);

			double unrelated = factory.similarity(a, b);
			Assert.assertTrue("unrelated features " + label + " score " + unrelated,
					Math.abs(unrelated) < 0.5);

			double[] batch = factory.batchSimilarity(a, List.of(a, scaled, b));
			Assert.assertEquals("batched self-similarity " + label, 1.0, batch[0], 1e-4);
			Assert.assertEquals("batched rescaled similarity " + label, 1.0, batch[1], 1e-4);
			Assert.assertEquals("batched and pairwise agree " + label, unrelated, batch[2], 1e-4);
		}
	}

	/** Frames of features per clip in {@link #oneShotsAndLoopsStaySeparate}, as for a fixed-length clip. */
	private static final int CLIP_FRAMES = 184;

	/** Bins per feature frame in {@link #oneShotsAndLoopsStaySeparate}. */
	private static final int CLIP_BINS = 32;

	/** Feature frames per second in {@link #oneShotsAndLoopsStaySeparate}. */
	private static final double CLIP_RATE = 10.0;

	/**
	 * A short drum hit compared with another hit, a drum loop and a sustained
	 * tone, using features shaped like the ones the library's feature model
	 * produces: every clip is padded to a fixed length, so the frames after a
	 * sound ends hold the features of silence, and those are close to the
	 * features of the quiet frames between a loop's hits.
	 *
	 * <p>A hit lasts three frames; the loop and the tone last over forty. A hit
	 * must rank another hit above the loop and the tone, and the loop must rank
	 * another loop above the hits: the frames where only the longer sound is
	 * sounding say nothing in favor of the match.</p>
	 */
	@Test(timeout = 120000)
	public void oneShotsAndLoopsStaySeparate() {
		Random random = new Random(5);
		double[] silence = direction(random);
		double[] drum = direction(random);
		double[] tone = direction(random);

		WaveDetails hitA = clip("hitA", 3, random, silence, f -> drum, 0.8);
		WaveDetails hitB = clip("hitB", 3, random, silence, f -> drum, 0.8);
		WaveDetails loopA = clip("loopA", 44, random, silence, f -> f % 4 == 0 ? drum : silence, 0.5);
		WaveDetails loopB = clip("loopB", 44, random, silence, f -> f % 4 == 0 ? drum : silence, 0.5);
		WaveDetails sustained = clip("tone", 40, random, silence, f -> tone, 0.3);

		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		double hits = factory.similarity(hitA, hitB);
		double hitToLoop = factory.similarity(hitA, loopA);
		double hitToTone = factory.similarity(hitA, sustained);
		double loops = factory.similarity(loopA, loopB);
		double loopToHit = factory.similarity(loopA, hitB);
		log("hit-hit " + hits + ", hit-loop " + hitToLoop + ", hit-tone " + hitToTone +
				", loop-loop " + loops + ", loop-hit " + loopToHit);

		Assert.assertTrue("a hit ranks a hit above a loop", hits > hitToLoop);
		Assert.assertTrue("a hit ranks a hit above a sustained tone", hits > hitToTone);
		Assert.assertTrue("a loop ranks a loop above a hit", loops > loopToHit);

		double[] batch = factory.batchSimilarity(hitA, List.of(hitB, loopA, sustained));
		Assert.assertEquals("batched and pairwise agree for a hit", hits, batch[0], 1e-4);
		Assert.assertEquals("batched and pairwise agree for a loop", hitToLoop, batch[1], 1e-4);
		Assert.assertEquals("batched and pairwise agree for a tone", hitToTone, batch[2], 1e-4);
	}

	/** Returns a random unit vector of {@link #CLIP_BINS} values. */
	private static double[] direction(Random random) {
		double[] v = new double[CLIP_BINS];
		double norm = 0;
		for (int i = 0; i < v.length; i++) {
			v[i] = random.nextGaussian();
			norm += v[i] * v[i];
		}

		norm = Math.sqrt(norm);
		for (int i = 0; i < v.length; i++) v[i] /= norm;
		return v;
	}

	/**
	 * Builds an item sounding for {@code sounding} frames of a fixed-length clip:
	 * each sounding frame is the given frame's direction plus noise, and every
	 * frame after the sound ends is the silence direction plus a little noise.
	 */
	private static WaveDetails clip(String identifier, int sounding, Random random, double[] silence,
									IntFunction<double[]> frame, double noise) {
		double[] values = new double[CLIP_FRAMES * CLIP_BINS];
		for (int f = 0; f < CLIP_FRAMES; f++) {
			double[] center = f < sounding ? frame.apply(f) : silence;
			double scale = (f < sounding ? noise : 0.05) / Math.sqrt(CLIP_BINS);
			for (int b = 0; b < CLIP_BINS; b++) {
				values[f * CLIP_BINS + b] = center[b] + scale * random.nextGaussian();
			}
		}

		PackedCollection features = new PackedCollection(CLIP_FRAMES, CLIP_BINS);
		features.set(0, values);

		WaveDetails details = new WaveDetails(identifier, SAMPLE_RATE);
		details.setSampleRate(SAMPLE_RATE);
		details.setFrameCount((int) (SAMPLE_RATE * (sounding - 0.5) / CLIP_RATE));
		details.putFeatures(new WaveFeatures("model", CLIP_RATE, 1, CLIP_BINS, CLIP_FRAMES, features));
		details.setActiveModelIdentifier("model");
		return details;
	}
}
