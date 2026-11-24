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
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.TagModelVersion;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.audio.data.WaveTags;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.AudioLibraryPersistence;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Verifies that computed tags carry (tagging model, vocabulary version)
 * provenance: tag sets under different keys sit beside each other on a
 * {@link WaveDetails}, the current key's tags are read back exactly, the stale
 * tag cleanup reports and removes exactly the tag sets under any other key
 * (differing by model, by vocabulary version, or both), and tag sets survive
 * the protobuf encode/decode and store round-trips.
 *
 * @see WaveTags
 * @see TagModelVersion
 * @see WaveFeatureCleanup
 * @see AudioLibraryPersistence
 */
public class TagProvenanceTest extends TestSuiteBase {

	/** Library analysis sample rate. */
	private static final int SAMPLE_RATE = 44100;

	/** Feature model the library is configured with, so items count as complete. */
	private static final String FEATURE_MODEL = "current-model";

	/** The tagging model treated as current. */
	private static final String TAG_MODEL = "tagger-a";

	/** The vocabulary version treated as current. */
	private static final String VOCAB = "vocab-2";

	/** The (tagging model, vocabulary version) key treated as current. */
	private static final TagModelVersion CURRENT = new TagModelVersion(TAG_MODEL, VOCAB);

	/** Temporary directory backing the library's file tree and store. */
	private Path tempDir;

	/**
	 * Creates the temporary directory used by the tests.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("tag-provenance-test");
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

	/** Creates a tag set with the given labels, each scored by its position. */
	private WaveTags tagSet(String model, String vocab, String... labels) {
		WaveTags tags = new WaveTags(new TagModelVersion(model, vocab));
		for (int i = 0; i < labels.length; i++) {
			tags.putTag(labels[i], 0.1 * (i + 1));
		}
		return tags;
	}

	/**
	 * Creates an item that is complete for {@link #FEATURE_MODEL} and carries
	 * tags under the current key, an older vocabulary of the same model, and a
	 * different model with the current vocabulary version.
	 */
	private WaveDetails taggedItem(String id) {
		WaveDetails d = new WaveDetails(id, SAMPLE_RATE);
		d.setFrameCount(SAMPLE_RATE);
		d.setFreqData(new PackedCollection(1));
		d.putFeatures(new WaveFeatures(FEATURE_MODEL, 10.0, 1, 3, 2,
				pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3))));
		d.putTags(tagSet(TAG_MODEL, VOCAB, "kick", "punchy"));
		d.putTags(tagSet(TAG_MODEL, "vocab-1", "drum"));
		d.putTags(tagSet("tagger-b", VOCAB, "percussion", "low", "short"));
		return d;
	}

	/** Creates a library over the temporary directory, configured for {@link #FEATURE_MODEL}. */
	private AudioLibrary library(ProtobufWaveDetailsStore store) {
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(tempDir.toFile()), SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(
				new LogSpectrumFeatureProvider(2, 3, SAMPLE_RATE, 1.0, FEATURE_MODEL));
		return library;
	}

	/** Asserts the decoded item carries the same tag sets as the original. */
	private void assertSameTags(String message, WaveDetails expected, WaveDetails actual) {
		Assert.assertEquals(message + " (keys)",
				expected.getTagModelVersions(), actual.getTagModelVersions());
		for (TagModelVersion key : expected.getTagModelVersions()) {
			Map<String, Double> want = expected.getTags(key).getTags();
			Map<String, Double> got = actual.getTags(key).getTags();
			Assert.assertEquals(message + " (tags for " + key + ")", want, got);
		}
	}

	/**
	 * Read-current: {@link WaveDetails#getTags(TagModelVersion)} returns exactly
	 * the tag set written under a key, distinguishes keys that differ only by
	 * vocabulary version, and returns {@code null} for a key never written.
	 */
	@Test(timeout = 60000)
	public void readCurrentTags() {
		WaveDetails d = taggedItem("read-0");

		Assert.assertEquals("three keys are present", Set.of(
				CURRENT,
				new TagModelVersion(TAG_MODEL, "vocab-1"),
				new TagModelVersion("tagger-b", VOCAB)), d.getTagModelVersions());

		WaveTags current = d.getTags(CURRENT);
		Assert.assertNotNull(current);
		Assert.assertEquals(Map.of("kick", 0.1, "punchy", 0.2), current.getTags());
		Assert.assertEquals(2, current.tagCount());
		Assert.assertEquals(TAG_MODEL, current.getModelIdentifier());
		Assert.assertEquals(VOCAB, current.getVocabularyVersion());

		Assert.assertEquals("an older vocabulary is a separate tag set",
				Map.of("drum", 0.1), d.getTags(new TagModelVersion(TAG_MODEL, "vocab-1")).getTags());
		Assert.assertNull("unknown vocabulary", d.getTags(new TagModelVersion(TAG_MODEL, "vocab-3")));
		Assert.assertNull("unknown model", d.getTags(new TagModelVersion("tagger-c", VOCAB)));

		d.putTags(tagSet(TAG_MODEL, VOCAB, "snare"));
		Assert.assertEquals("writing the same key replaces the tag set",
				Map.of("snare", 0.1), d.getTags(CURRENT).getTags());
		Assert.assertEquals(3, d.getTagModelVersions().size());

		Assert.assertEquals("two stale sets relative to the current key",
				2, d.otherModelTagCount(CURRENT));
		Assert.assertTrue(d.hasOtherModelTags(CURRENT));
	}

	/**
	 * Round-trip: tag sets under several keys survive
	 * {@link AudioLibraryPersistence#encode} and {@link AudioLibraryPersistence#decode},
	 * and a {@link ProtobufWaveDetailsStore} put/get across a store reopen,
	 * without disturbing the feature sets beside them.
	 */
	@Test(timeout = 120000)
	public void tagsSurviveRoundTrip() {
		WaveDetails details = taggedItem("round-0");

		Audio.WaveDetailData encoded = AudioLibraryPersistence.encode(details, false);
		Assert.assertEquals("every tag set is written", 3, encoded.getTagSetsCount());
		Assert.assertEquals("feature sets are unaffected", 1, encoded.getFeatureSetsCount());

		WaveDetails decoded = AudioLibraryPersistence.decode(encoded);
		assertSameTags("encode/decode", details, decoded);
		Assert.assertNotNull("feature set survives beside tags", decoded.getFeatures(FEATURE_MODEL));

		WaveDetails untagged = new WaveDetails("plain", SAMPLE_RATE);
		untagged.setFreqData(new PackedCollection(1));
		Audio.WaveDetailData plain = AudioLibraryPersistence.encode(untagged, false);
		Assert.assertEquals("an untagged item writes no tag sets", 0, plain.getTagSetsCount());
		Assert.assertTrue(AudioLibraryPersistence.decode(plain).getTagModelVersions().isEmpty());

		File storeDir = tempDir.resolve("store-round").toFile();
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);
		store.put(details.getIdentifier(), details);
		store.close();

		ProtobufWaveDetailsStore reopened = new ProtobufWaveDetailsStore(storeDir);
		try {
			assertSameTags("store put/get", details, reopened.get(details.getIdentifier()));
		} finally {
			reopened.close();
		}
	}

	/**
	 * Enumerate-stale and delete: the cleanup counts exactly the items and tag
	 * sets whose key differs from the kept one (by vocabulary version only, by
	 * model only), ignores items carrying only the kept key, and after removal
	 * only the kept tags remain and a fresh report reads zero.
	 */
	@Test(timeout = 120000)
	public void cleanupReportsAndRemovesStaleTags() {
		AudioLibrary library = library(null);

		int staleItems = 3;
		for (int i = 0; i < staleItems; i++) {
			library.include(taggedItem("stale-" + i));
		}

		WaveDetails clean = taggedItem("clean-0");
		clean.removeOtherModelTags(CURRENT);
		library.include(clean);

		WaveFeatureCleanup report = WaveFeatureCleanup.report(library, CURRENT);
		Assert.assertEquals("only items with stale tags are counted", staleItems, report.itemCount());
		Assert.assertEquals("two stale sets per item", 2 * staleItems, report.staleTagSetCount());

		WaveFeatureCleanup byModel = WaveFeatureCleanup.report(library, new TagModelVersion("tagger-b", VOCAB));
		Assert.assertEquals("keeping another key makes the current one stale",
				staleItems + 1, byModel.itemCount());
		Assert.assertEquals(2 * staleItems + 1, byModel.staleTagSetCount());

		Assert.assertEquals("every stale item is cleaned", staleItems, report.remove());

		for (WaveDetails d : library.allDetails().toList()) {
			Assert.assertEquals("only the kept key remains on " + d.getIdentifier(),
					Set.of(CURRENT), d.getTagModelVersions());
			Assert.assertNotNull(d.getTags(CURRENT));
		}

		WaveFeatureCleanup after = WaveFeatureCleanup.report(library, CURRENT);
		Assert.assertEquals(0, after.itemCount());
		Assert.assertEquals(0, after.staleTagSetCount());

		library.stop();
	}

	/**
	 * The removal is re-persisted: on a store-backed library, the tags read back
	 * from a reopened store carry only the kept key.
	 */
	@Test(timeout = 120000)
	public void cleanupSurvivesReload() {
		File storeDir = tempDir.resolve("store-cleanup").toFile();
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);
		AudioLibrary library = library(store);

		library.include(taggedItem("persist-0"));
		library.include(taggedItem("persist-1"));

		WaveFeatureCleanup report = WaveFeatureCleanup.report(library, CURRENT);
		Assert.assertEquals(2, report.itemCount());
		Assert.assertEquals(2, report.remove());

		library.stop();
		store.close();

		ProtobufWaveDetailsStore reopened = new ProtobufWaveDetailsStore(storeDir);
		try {
			for (String id : new String[] {"persist-0", "persist-1"}) {
				WaveDetails reloaded = reopened.get(id);
				Assert.assertNotNull(reloaded);
				Assert.assertEquals("stale tags stay deleted after reload of " + id,
						Set.of(CURRENT), reloaded.getTagModelVersions());
				Assert.assertEquals(Map.of("kick", 0.1, "punchy", 0.2),
						reloaded.getTags(CURRENT).getTags());
			}
		} finally {
			reopened.close();
		}
	}
}
