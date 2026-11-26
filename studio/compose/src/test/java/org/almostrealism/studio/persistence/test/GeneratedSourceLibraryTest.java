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

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.audio.synth.InterpolatedAudioSynthesisModel;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.GeneratedSourceLibrary;
import org.almostrealism.studio.persistence.LibraryDestination;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Tests for {@link GeneratedSourceLibrary}, the keyed store of synthesized
 * audio source models, focusing on the in-memory map contract and the
 * Protocol Buffers codec ({@link GeneratedSourceLibrary#convert}) that is the
 * round-trip anchor for persisting {@link InterpolatedAudioSynthesisModel}s.
 *
 * @see GeneratedSourceLibrary
 */
public class GeneratedSourceLibraryTest extends TestSuiteBase {

	/** Sample rate stored on the synthesis models under test. */
	private static final double LEVEL_SAMPLE_RATE = 400.0;

	/** Root temporary directory for the persistence round-trip, removed in teardown. */
	private Path tempDir;

	/**
	 * Creates the temporary directory used by the persistence round-trip test.
	 *
	 * @throws IOException if directory creation fails
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("generated-source-library-test");
	}

	/**
	 * Recursively deletes the test's temporary directory.
	 *
	 * @throws IOException if directory traversal fails
	 */
	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			}
		}
	}

	/** Builds a small model with a known [2, 3] level matrix and ratio array. */
	private InterpolatedAudioSynthesisModel sampleModel() {
		PackedCollection levelData = new PackedCollection(2, 3);
		levelData.fill(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);
		return new InterpolatedAudioSynthesisModel(
				new double[] {1.0, 2.0}, LEVEL_SAMPLE_RATE, levelData);
	}

	/**
	 * A model added under a key is returned for that key, and an unknown key
	 * yields {@code null}.
	 */
	@Test(timeout = 90000)
	public void addedModelIsRetrievedByKeyAndUnknownKeyIsNull() {
		GeneratedSourceLibrary library = new GeneratedSourceLibrary(
				new LibraryDestination(tempDir.resolve("lib").toString(), true));
		InterpolatedAudioSynthesisModel model = sampleModel();

		library.add("synth:A", model);

		Assert.assertSame("The stored model must be returned for its key",
				model, library.getModel("synth:A"));
		Assert.assertNull("An unknown key must resolve to null", library.getModel("synth:absent"));
	}

	/**
	 * {@link GeneratedSourceLibrary#getSource(String)} always returns a usable
	 * audio source, whether or not a model is registered for the key.
	 */
	@Test(timeout = 90000)
	public void getSourceReturnsASynthesizerForKnownAndUnknownKeys() {
		GeneratedSourceLibrary library = new GeneratedSourceLibrary(
				new LibraryDestination(tempDir.resolve("lib").toString(), true));
		InterpolatedAudioSynthesisModel model = sampleModel();
		library.add("synth:A", model);

		StatelessSource configured = library.getSource("synth:A");
		StatelessSource bare = library.getSource("synth:missing");

		Assert.assertNotNull("A source must be returned for a known key", configured);
		Assert.assertNotNull("A source must still be returned for an unknown key", bare);
		Assert.assertSame("The known-key source must be backed by the registered model",
				model, ((AudioSynthesizer) configured).getModel());
		Assert.assertNull("The unknown-key source must carry no model",
				((AudioSynthesizer) bare).getModel());
	}

	/**
	 * The protobuf codec round-trips a model: {@link
	 * GeneratedSourceLibrary#convert(String, InterpolatedAudioSynthesisModel)}
	 * then {@link GeneratedSourceLibrary#convert(Audio.SynthesizerModelData)}
	 * recovers the sample rate, frequency ratios, and level data exactly.
	 */
	@Test(timeout = 90000)
	public void convertRoundTripsModelThroughProtobuf() {
		GeneratedSourceLibrary library = new GeneratedSourceLibrary(
				new LibraryDestination(tempDir.resolve("lib").toString(), true));
		InterpolatedAudioSynthesisModel model = sampleModel();

		Audio.SynthesizerModelData data = library.convert("synth:A", model);
		Assert.assertEquals("The key must be carried on the message", "synth:A", data.getKey());
		Assert.assertEquals("The level sample rate must be carried on the message",
				LEVEL_SAMPLE_RATE, data.getLevelSampleRate(), 0.0);
		Assert.assertEquals("Both frequency ratios must be carried", 2, data.getFrequencyRatiosCount());

		InterpolatedAudioSynthesisModel restored = library.convert(data);
		Assert.assertArrayEquals("Frequency ratios must survive the round trip",
				model.getFrequencyRatios(), restored.getFrequencyRatios(), 0.0);
		Assert.assertEquals("Sample rate must survive the round trip",
				model.getSampleRate(), restored.getSampleRate(), 0.0);

		PackedCollection expected = model.getLevelData();
		PackedCollection actual = restored.getLevelData();
		Assert.assertEquals("Level data length must match",
				expected.getMemLength(), actual.getMemLength());
		for (int i = 0; i < expected.getMemLength(); i++) {
			Assert.assertEquals("Level sample " + i + " must survive the round trip",
					expected.toDouble(i), actual.toDouble(i), 1e-6);
		}
	}

	/**
	 * A model saved to a {@link LibraryDestination} is reloaded into a fresh
	 * library with its key and frequency ratios intact.
	 *
	 * @throws IOException if saving to the destination fails
	 */
	@Test(timeout = 90000)
	public void savedModelReloadsFromDestination() throws IOException {
		String prefix = tempDir.resolve("lib").toString();
		GeneratedSourceLibrary saved = new GeneratedSourceLibrary(
				new LibraryDestination(prefix, true));
		saved.add("synth:A", sampleModel());
		saved.save();

		Assert.assertTrue("A batch file must have been written",
				new File(prefix + "_0.bin").exists());

		GeneratedSourceLibrary reloaded = new GeneratedSourceLibrary(
				new LibraryDestination(prefix, true));
		reloaded.load();

		InterpolatedAudioSynthesisModel model = (InterpolatedAudioSynthesisModel) reloaded.getModel("synth:A");
		Assert.assertNotNull("The model must reload under its original key", model);
		Assert.assertArrayEquals("Reloaded frequency ratios must match the saved model",
				new double[] {1.0, 2.0}, model.getFrequencyRatios(), 0.0);
		Assert.assertEquals("Reloaded sample rate must match the saved model",
				LEVEL_SAMPLE_RATE, model.getSampleRate(), 0.0);
	}
}
