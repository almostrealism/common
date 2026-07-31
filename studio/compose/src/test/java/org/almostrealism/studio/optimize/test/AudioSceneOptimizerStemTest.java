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

package org.almostrealism.studio.optimize.test;

import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.pattern.test.AudioSceneTestBase;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the standard optimizer entry point — {@link AudioSceneOptimizer#build}
 * followed by {@code run()}, with scores observed through the health listener — and
 * asserts the stem contract on what the listener delivers: every score carries one stem
 * path per audio channel, every stem file exists, and the score survives the
 * {@link XMLEncoder} round trip the app applies when persisting and reloading its
 * population. This is deliberately the app's call sequence rather than a direct
 * {@code computeHealth()} harness, so isolated-context cycles, population persistence,
 * and per-cycle health initialization are all in play.
 */
public class AudioSceneOptimizerStemTest extends AudioSceneTestBase {

	/** Evaluation seconds per genome; long enough for pattern content, short enough to test. */
	private static final int MAX_DURATION_SECONDS = 8;

	/**
	 * Builds and runs a one-cycle optimizer over a two-genome population and verifies
	 * the stems attached to every delivered health score.
	 *
	 * @throws Exception if the curated scene cannot be loaded or the run fails
	 */
	@Test(timeout = 900_000)
	@TestDepth(2)
	public void optimizerScoresCarryStems() throws Exception {
		File library = requireCuratedLibrary();
		File patternFactory = new File(PATTERN_FACTORY);

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		MixdownManager.enablePdslMixdown = true;
		PatternSystemManager.enableWarnings = false;
		AudioSceneRealtimeRunner.renderAheadSlots = 24;

		PopulationOptimizer.popSize = 2;
		PopulationOptimizer.maxChildren = 2;
		new File(AudioSceneOptimizer.POPULATION_FILE).delete();

		AudioScene<?> scene = loadCuratedScene(library, patternFactory, 120.0, 64);

		AudioSceneOptimizer optimizer = AudioSceneOptimizer.build(scene, 1);
		((StableDurationHealthComputation) optimizer.getHealthComputation())
				.setMaxDuration(MAX_DURATION_SECONDS);

		Map<String, AudioHealthScore> scores = new LinkedHashMap<>();
		optimizer.setHealthListener(scores::put);

		try {
			optimizer.run();
		} finally {
			optimizer.destroy();
		}

		Assert.assertFalse("Optimizer delivered no health scores", scores.isEmpty());

		int expected = scene.getChannelCount() + 1;

		for (Map.Entry<String, AudioHealthScore> entry : scores.entrySet()) {
			AudioHealthScore score = xmlRoundTrip(entry.getValue());

			Assert.assertNotNull("Score for " + entry.getKey() + " carries no stem list",
					score.getStems());
			Assert.assertEquals("Score for " + entry.getKey()
							+ " must carry one stem per audio channel",
					expected, score.getStems().size());

			for (String path : score.getStems()) {
				Assert.assertTrue("Missing stem file " + path, new File(path).exists());
			}

			log("genome=" + entry.getKey() + " stems=" + score.getStems());
		}
	}

	/**
	 * Encodes and decodes the value with the bean serialization used for persisted
	 * population records, so any property lost in that round trip fails here rather
	 * than only in a client application. Encoding exceptions are rethrown
	 * rather than skipped, because the app's default listener silently drops the
	 * failing property — exactly the failure mode this receipt exists to surface.
	 *
	 * @param value the value to round trip
	 * @param <T>   the value type
	 * @return the decoded copy
	 */
	private <T> T xmlRoundTrip(T value) {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		try (XMLEncoder encoder = new XMLEncoder(data)) {
			encoder.setExceptionListener(e -> {
				throw new IllegalStateException(
						"Population record does not survive bean serialization", e);
			});
			encoder.writeObject(value);
		}
		try (XMLDecoder decoder = new XMLDecoder(new ByteArrayInputStream(data.toByteArray()))) {
			return (T) decoder.readObject();
		}
	}
}
