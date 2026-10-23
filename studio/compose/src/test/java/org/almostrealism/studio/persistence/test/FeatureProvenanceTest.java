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

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.WaveFeatureCleanup;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.studio.persistence.AudioLibraryPersistence;
import org.almostrealism.studio.persistence.test.support.LogSpectrumFeatureProvider;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Verifies that feature data carries model provenance: features from different
 * models sit beside each other on a {@link WaveDetails}, similarity is never
 * computed across models, the other-model cleanup reports and removes exactly
 * the right data, and the legacy unkeyed slot stays readable by a previous
 * release.
 *
 * @see WaveFeatures
 * @see WaveFeatureCleanup
 * @see AudioLibraryPersistence
 */
public class FeatureProvenanceTest extends TestSuiteBase {

	/** Feature sample rate used for every feature set built in these tests. */
	private static final double FEATURE_SAMPLE_RATE = 10.0;

	/** Library analysis sample rate; unrelated to the feature metadata under test. */
	private static final int SAMPLE_RATE = 44100;

	/** Temporary directory backing the in-memory library's (unused) file tree. */
	private Path tempDir;

	/**
	 * Creates the temporary directory used to back the in-memory library.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("feature-provenance-test");
	}

	/**
	 * Deletes the temporary directory and its contents.
	 *
	 * @throws IOException if traversal fails
	 */
	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

	/** Wraps a feature tensor in a {@link WaveFeatures} for the given model. */
	private WaveFeatures featureSet(String modelId, int frames, int bins, PackedCollection data) {
		return new WaveFeatures(modelId, FEATURE_SAMPLE_RATE, 1, bins, frames, data);
	}

	/** Asserts two tensors hold the same values (within FP32 round-trip tolerance). */
	private void assertValuesEqual(String message, PackedCollection expected, PackedCollection actual) {
		Assert.assertNotNull(message, actual);
		Assert.assertEquals(message + " (length)", expected.getMemLength(), actual.getMemLength());
		Assert.assertArrayEquals(message,
				expected.toArray(0, expected.getMemLength()),
				actual.toArray(0, actual.getMemLength()), 1e-3);
	}

	/**
	 * Criterion 1: features from a new model sit beside the legacy unkeyed slot,
	 * and both survive an encode/decode round-trip.
	 */
	@Test(timeout = 120000)
	public void featuresSitBesideEachOther() {
		WaveDetails details = new WaveDetails("id-beside", SAMPLE_RATE);
		details.setFrameCount(SAMPLE_RATE);
		details.setFreqData(new PackedCollection(1));

		PackedCollection legacy = pack(100, 101, 102, 103, 104, 105).reshape(shape(2, 3));
		details.setFeatureData(legacy);
		details.setFeatureSampleRate(5.0);
		details.setFeatureChannelCount(1);
		details.setFeatureBinCount(3);
		details.setFeatureFrameCount(2);

		WaveFeatures current = featureSet("current-model", 2, 3,
				pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3)));
		details.putFeatures(current);
		details.setActiveModelIdentifier("current-model");

		assertValuesEqual("active accessor returns current model",
				current.getFeatureData(), details.getFeatureData());
		Assert.assertEquals(FEATURE_SAMPLE_RATE, details.getFeatureSampleRate(), 0);
		assertValuesEqual("legacy slot still reads back",
				legacy, details.getLegacyFeatureData());
		Assert.assertEquals(5.0, details.getLegacyFeatureSampleRate(), 0);

		WaveDetails decoded = AudioLibraryPersistence.decode(
				AudioLibraryPersistence.encode(details, false));

		assertValuesEqual("legacy slot survives round-trip",
				legacy, decoded.getLegacyFeatureData());
		Assert.assertEquals(5.0, decoded.getLegacyFeatureSampleRate(), 1e-3);
		Assert.assertNotNull("current model survives round-trip keyed",
				decoded.getFeatures("current-model"));
		assertValuesEqual("current model feature data survives round-trip",
				current.getFeatureData(), decoded.getFeatures("current-model").getFeatureData());
		assertValuesEqual("decoded no-arg accessor resolves the single keyed model",
				current.getFeatureData(), decoded.getFeatureData());
	}

	/**
	 * Criterion 2: with two models' feature sets present, a computed similarity
	 * map contains only the current model's pairs, and switching the current
	 * model yields the other model's separate map. No pair is computed across
	 * models.
	 */
	@Test(timeout = 120000)
	public void similarityNeverMixesModels() {
		AudioLibrary library = new AudioLibrary(tempDir.toFile(), SAMPLE_RATE);
		library.getWaveDetailsFactory().setFeatureProvider(
				new LogSpectrumFeatureProvider(2, 3, SAMPLE_RATE, 1.0, "model-a"));

		String[] ids = {"item-0", "item-1", "item-2"};
		for (int i = 0; i < ids.length; i++) {
			WaveDetails d = new WaveDetails(ids[i], SAMPLE_RATE);
			d.setFrameCount(SAMPLE_RATE);
			d.setFreqData(new PackedCollection(1));
			// Model A: identical features across every item -> every pair scores alike.
			d.putFeatures(featureSet("model-a", 2, 3,
					pack(1, 1, 1, 1, 1, 1).reshape(shape(2, 3))));
			// Model B: a distinct (non-parallel, non-zero) direction per item so a
			// pair scores differently than it does under model A.
			PackedCollection bData;
			if (i == 0) {
				bData = pack(2, 1, 1, 1, 2, 1, 1, 1).reshape(shape(2, 4));
			} else if (i == 1) {
				bData = pack(1, 2, 1, 1, 1, 2, 1, 1).reshape(shape(2, 4));
			} else {
				bData = pack(1, 1, 2, 1, 1, 1, 2, 1).reshape(shape(2, 4));
			}
			d.putFeatures(featureSet("model-b", 2, 4, bData));
			library.include(d);
		}

		WaveDetails a0 = library.get("item-0");
		library.computeSimilarities(a0);

		Assert.assertEquals("model A similarities computed for both peers",
				2, a0.getFeatures("model-a").getSimilarities().size());
		Assert.assertTrue("model B map untouched while model A is current",
				a0.getFeatures("model-b").getSimilarities().isEmpty());
		double aSim = a0.getFeatures("model-a").getSimilarities().get("item-1");

		library.getWaveDetailsFactory().setFeatureProvider(
				new LogSpectrumFeatureProvider(2, 4, SAMPLE_RATE, 1.0, "model-b"));

		WaveDetails b0 = library.get("item-0");
		library.computeSimilarities(b0);

		Assert.assertEquals("model B similarities computed for both peers",
				2, b0.getFeatures("model-b").getSimilarities().size());
		Assert.assertEquals("model A map unchanged by model B computation",
				2, b0.getFeatures("model-a").getSimilarities().size());
		double bSim = b0.getFeatures("model-b").getSimilarities().get("item-1");
		Assert.assertFalse("model A similarity is finite", Double.isNaN(aSim));
		Assert.assertFalse("model B similarity is finite", Double.isNaN(bSim));
		Assert.assertTrue("the same pair scores differently per model, so no cross-model"
				+ " mixing occurred (A=" + aSim + ", B=" + bSim + ")",
				Math.abs(aSim - bSim) > 1e-3);

		library.stop();
	}

	/**
	 * Criterion 3: the cleanup reports a count and byte total equal to exactly
	 * the non-current feature sets plus the legacy unkeyed slot, and after the
	 * delete only the current model's data remains and the report reads zero.
	 */
	@Test(timeout = 120000)
	public void cleanupReportsAndRemovesOtherModelData() {
		AudioLibrary library = new AudioLibrary(tempDir.toFile(), SAMPLE_RATE);
		library.getWaveDetailsFactory().setFeatureProvider(
				new LogSpectrumFeatureProvider(2, 3, SAMPLE_RATE, 1.0, "current-model"));

		int itemCount = 3;
		long expectedBytes = 0;
		for (int i = 0; i < itemCount; i++) {
			WaveDetails d = new WaveDetails("clean-" + i, SAMPLE_RATE);
			d.setFrameCount(SAMPLE_RATE);
			d.setFreqData(new PackedCollection(1));

			d.putFeatures(featureSet("current-model", 2, 3,
					pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3))));

			WaveFeatures old = featureSet("old-model", 2, 4,
					pack(50, 51, 52, 53, 54, 55, 56, 57).reshape(shape(2, 4)));
			d.putFeatures(old);

			PackedCollection legacy =
					pack(200, 201, 202, 203, 204, 205, 206, 207, 208, 209).reshape(shape(2, 5));
			d.setFeatureData(legacy);
			d.setFeatureBinCount(5);
			d.setFeatureFrameCount(2);

			expectedBytes += old.byteLength() + (long) legacy.getMemLength() * Double.BYTES;
			library.include(d);
		}

		WaveFeatureCleanup report = library.otherModelFeatures();
		Assert.assertEquals("all items carry other-model data", itemCount, report.itemCount());
		Assert.assertEquals("byte total is legacy + other-model feature data",
				expectedBytes, report.totalBytes());

		int removed = report.remove();
		Assert.assertEquals("every reported item is cleaned", itemCount, removed);

		for (int i = 0; i < itemCount; i++) {
			WaveDetails d = library.get("clean-" + i);
			Assert.assertEquals("only the current model remains keyed",
					Set.of("current-model"), d.getFeatureModelIdentifiers());
			Assert.assertNull("legacy slot cleared", d.getLegacyFeatureData());
			Assert.assertNotNull("current model features retained", d.getFeatures("current-model"));
		}

		WaveFeatureCleanup after = library.otherModelFeatures();
		Assert.assertEquals("no other-model items remain", 0, after.itemCount());
		Assert.assertEquals("no other-model bytes remain", 0L, after.totalBytes());

		library.stop();
	}

	/**
	 * The cleanup releases the native memory of the feature tensors it discards
	 * — both a non-current model's keyed set and the legacy unkeyed slot — rather
	 * than merely dropping the Java reference, while leaving the kept model's
	 * tensor intact. This exercises {@link WaveFeatures#destroy()}, the deterministic
	 * release the reported bytes claim to reclaim.
	 */
	@Test(timeout = 120000)
	public void cleanupReleasesDiscardedFeatureMemory() {
		AudioLibrary library = new AudioLibrary(tempDir.toFile(), SAMPLE_RATE);
		library.getWaveDetailsFactory().setFeatureProvider(
				new LogSpectrumFeatureProvider(2, 3, SAMPLE_RATE, 1.0, "current-model"));

		WaveDetails d = new WaveDetails("release-0", SAMPLE_RATE);
		d.setFrameCount(SAMPLE_RATE);
		d.setFreqData(new PackedCollection(1));

		PackedCollection kept = pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3));
		d.putFeatures(featureSet("current-model", 2, 3, kept));

		PackedCollection other = pack(7, 8, 9, 10, 11, 12, 13, 14).reshape(shape(2, 4));
		d.putFeatures(featureSet("old-model", 2, 4, other));

		PackedCollection legacy = pack(20, 21, 22, 23, 24, 25).reshape(shape(2, 3));
		d.setFeatureData(legacy);
		d.setFeatureBinCount(3);
		d.setFeatureFrameCount(2);

		library.include(d);

		int removed = library.otherModelFeatures().remove();
		Assert.assertTrue("the single item is cleaned", removed == 1);

		Assert.assertTrue("other-model feature memory released", other.isDestroyed());
		Assert.assertTrue("legacy feature memory released", legacy.isDestroyed());
		Assert.assertFalse("kept model feature memory retained", kept.isDestroyed());

		library.stop();
	}

	/**
	 * Criterion 4: a record written by the new encode is readable through only
	 * the legacy proto fields (as the previous release reads it), and a
	 * simulated old-release rebuild from only those fields drops the keyed
	 * feature sets.
	 */
	@Test(timeout = 120000)
	public void previousReleaseLoadDropsFeatureSetsButKeepsLegacy() {
		WaveDetails details = new WaveDetails("id-compat", SAMPLE_RATE);
		details.setFrameCount(SAMPLE_RATE);
		details.setFreqData(new PackedCollection(1));

		PackedCollection legacy = pack(100, 101, 102, 103, 104, 105).reshape(shape(2, 3));
		details.setFeatureData(legacy);
		details.setFeatureSampleRate(5.0);
		details.setFeatureChannelCount(1);
		details.setFeatureBinCount(3);
		details.setFeatureFrameCount(2);
		details.getLegacySimilarities().put("other", 0.25);

		details.putFeatures(featureSet("current-model", 2, 3,
				pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3))));

		Audio.WaveDetailData encoded = AudioLibraryPersistence.encode(details, false);

		Assert.assertEquals("keyed feature sets are written additively",
				1, encoded.getFeatureSetsCount());
		Assert.assertEquals("legacy feature metadata is written verbatim",
				3, encoded.getFeatureBinCount());
		Assert.assertEquals(5.0, encoded.getFeatureSampleRate(), 1e-6);
		Assert.assertEquals("legacy similarities are written verbatim",
				0.25, encoded.getSimilaritiesMap().get("other"), 1e-6);
		assertValuesEqual("legacy feature data is written verbatim",
				legacy, CollectionEncoder.decode(encoded.getFeatureData()));

		// Simulate the previous release: read only fields 11 and 12-16.
		WaveDetails oldReader = new WaveDetails(encoded.getIdentifier(), SAMPLE_RATE);
		oldReader.setFeatureData(CollectionEncoder.decode(encoded.getFeatureData()));
		oldReader.setFeatureSampleRate(encoded.getFeatureSampleRate());
		oldReader.setFeatureBinCount(encoded.getFeatureBinCount());
		oldReader.setFeatureFrameCount(encoded.getFeatureFrameCount());
		oldReader.getLegacySimilarities().putAll(encoded.getSimilaritiesMap());

		assertValuesEqual("old reader recovers the legacy features",
				legacy, oldReader.getLegacyFeatureData());

		Audio.WaveDetailData reEncoded = AudioLibraryPersistence.encode(oldReader, false);
		Assert.assertEquals("old-release rebuild drops the keyed feature sets",
				0, reEncoded.getFeatureSetsCount());
		Assert.assertEquals("old-release rebuild preserves legacy feature metadata",
				3, reEncoded.getFeatureBinCount());
	}
}
