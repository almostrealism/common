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

package org.almostrealism.spatial.test;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.spatial.GenomicNetwork;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.notes.SceneAudioNode;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Verifies that the population record kept for each rendered arrangement — a
 * {@link GenomicNetwork} carrying the genome, the {@link AudioHealthScore} with
 * its stem paths, and a {@link SceneAudioNode} over the live scene — survives
 * the bean XML serialization applied when persisting and reloading a
 * population. Clients replace their live records with the decoded copies after
 * an optimization run and build per-channel stem exports from the stems of the
 * decoded score, so a property silently dropped in this round trip removes
 * those exports with no error anywhere.
 */
public class GenomicNetworkPersistenceTest extends TestSuiteBase {

	/**
	 * Round trips a population record shaped exactly as the app shapes it after
	 * a run, using the app's own tolerance for encoding failures (properties
	 * that fail to encode are skipped, and logged here), then asserts the health
	 * score and its stems survive.
	 */
	@Test(timeout = 120000)
	public void populationRecordSurvivesBeanSerialization() {
		AudioScene<?> scene = new AudioScene<>(120, 6, 3, OutputLine.sampleRate);
		Genome<PackedCollection> genome = scene.getGenome().random();

		List<String> stems = IntStream.range(0, scene.getChannelCount() + 1)
				.mapToObj(i -> "health/record-1." + i + ".wav")
				.toList();
		AudioHealthScore score = new AudioHealthScore(10 * OutputLine.sampleRate, 0.5,
				"health/record-1.wav", stems, List.of("record"));

		GenomicNetwork network = new GenomicNetwork(0, genome);
		network.setHealthScore(score);

		SceneAudioNode node = new SceneAudioNode(network.getKey(), "network 0", scene, null);
		node.setRange(0, scene.getTotalMeasures());
		network.setSceneAudioNode(node);

		List<Exception> encodingFailures = new ArrayList<>();
		List<GenomicNetwork> decoded =
				xmlRoundTrip(new ArrayList<>(List.of(network)), encodingFailures);

		encodingFailures.forEach(e -> {
			StackTraceElement origin = Arrays.stream(e.getStackTrace())
					.filter(t -> t.getClassName().contains("almostrealism"))
					.findFirst().orElse(null);
			log("encodingFailure=" + e.getMessage() + " origin=" + origin);
		});

		Assert.assertEquals("Population record lost in the persistence round trip",
				1, decoded.size());

		AudioHealthScore result = decoded.get(0).getHealthScore();
		Assert.assertNotNull("Persisted population record lost its health score", result);
		Assert.assertNotNull("Persisted health score lost its stem list", result.getStems());
		Assert.assertEquals("Persisted health score lost stems",
				stems.size(), result.getStems().size());
		Assert.assertEquals("Persisted stem paths altered", stems, result.getStems());

		Assert.assertNotNull("Persisted population record lost its genome — score"
						+ " attachment dereferences the genome's signature, so a null"
						+ " genome breaks every later score delivery for the record",
				decoded.get(0).getGenome());
		Assert.assertEquals("Persisted genome signature altered",
				genome.signature(), decoded.get(0).getGenome().signature());
	}

	/**
	 * Encodes and decodes the value with bean serialization under the default
	 * silent tolerance for per-property encoding failures, collecting the
	 * failures for reporting instead of discarding them.
	 *
	 * @param value    the value to round trip
	 * @param failures receives any per-property encoding exceptions
	 * @param <T>      the value type
	 * @return the decoded copy
	 */
	private <T> T xmlRoundTrip(T value, List<Exception> failures) {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		try (XMLEncoder encoder = new XMLEncoder(data)) {
			encoder.setExceptionListener(failures::add);
			encoder.writeObject(value);
		}
		try (XMLDecoder decoder = new XMLDecoder(new ByteArrayInputStream(data.toByteArray()))) {
			return (T) decoder.readObject();
		}
	}
}
