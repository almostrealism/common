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

package org.almostrealism.audio.persistence.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.audio.persistence.AudioLibraryMigration;
import org.almostrealism.audio.persistence.AudioLibraryPersistence;
import org.almostrealism.audio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.collect.PackedCollection;
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
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tests for {@link AudioLibraryMigration} which migrates audio library
 * data from the legacy {@link AudioLibraryPersistence} batch format to
 * the new {@link ProtobufWaveDetailsStore} format.
 *
 * @see AudioLibraryMigration
 * @see AudioLibraryPersistence
 * @see ProtobufWaveDetailsStore
 */
public class AudioLibraryMigrationTest extends TestSuiteBase {

	private Path tempDir;
	private Path legacyDir;
	private Path storeDir;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("migration-test");
		legacyDir = tempDir.resolve("legacy");
		storeDir = tempDir.resolve("store");
		Files.createDirectories(legacyDir);
	}

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

	/**
	 * Verifies that migrate() transfers all records from legacy format
	 * to the new store, preserving identifiers and feature data.
	 */
	@Test(timeout = 30000)
	public void migrateTransfersAllRecords() {
		AudioLibrary library = createAndSaveLegacyLibrary(5);
		String prefix = legacyDir.resolve("lib").toString();

		int migrated = AudioLibraryMigration.migrate(
				Path.of(prefix), storeDir);

		Assert.assertEquals("All 5 records should be migrated", 5, migrated);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertEquals("Store should contain 5 records", 5, store.size());

		for (int i = 0; i < 5; i++) {
			String id = "migrate-id-" + i;
			Assert.assertTrue("Store should contain " + id,
					store.containsKey(id));
			WaveDetails details = store.get(id);
			Assert.assertNotNull("Details should be retrievable for " + id, details);
			Assert.assertEquals("Identifier should match", id, details.getIdentifier());
			Assert.assertNotNull("FreqData should be preserved", details.getFreqData());
			Assert.assertNotNull("FeatureData should be preserved", details.getFeatureData());
		}

		store.close();
		library.stop();
	}

	/**
	 * Verifies that migration is idempotent: running it twice does not
	 * duplicate records.
	 */
	@Test(timeout = 30000)
	public void migrateIsIdempotent() {
		AudioLibrary library = createAndSaveLegacyLibrary(3);
		String prefix = legacyDir.resolve("lib").toString();

		int firstRun = AudioLibraryMigration.migrate(
				Path.of(prefix), storeDir);
		Assert.assertEquals("First run should migrate 3 records", 3, firstRun);

		int secondRun = AudioLibraryMigration.migrate(
				Path.of(prefix), storeDir);
		Assert.assertEquals("Second run should migrate 0 (already present)", 0, secondRun);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertEquals("Store should still contain exactly 3 records", 3, store.size());
		store.close();
		library.stop();
	}

	/**
	 * Verifies that migrate() returns 0 and logs a warning when no
	 * legacy data files exist at the given prefix.
	 */
	@Test(timeout = 10000)
	public void migrateWithNoLegacyFiles() {
		int migrated = AudioLibraryMigration.migrate(
				Path.of(legacyDir.resolve("nonexistent").toString()), storeDir);

		Assert.assertEquals("Should migrate 0 records when no files exist", 0, migrated);
	}

	/**
	 * Verifies that records with feature data get embedding vectors
	 * stored in the HNSW index, making them searchable via
	 * {@link WaveDetailsStore#searchNeighbors}.
	 */
	@Test(timeout = 30000)
	public void migrateCreatesEmbeddingsForSearch() {
		AudioLibrary library = createAndSaveLegacyLibrary(10);
		String prefix = legacyDir.resolve("lib").toString();

		AudioLibraryMigration.migrate(Path.of(prefix), storeDir);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());

		WaveDetails first = store.get("migrate-id-0");
		Assert.assertNotNull("First record should exist", first);

		PackedCollection embedding = AudioLibrary.computeEmbeddingVector(first);
		Assert.assertNotNull("Should be able to compute embedding from migrated data", embedding);

		List<WaveDetailsStore.NeighborResult> neighbors =
				store.searchNeighbors(embedding, 5);
		Assert.assertFalse("HNSW search should return results after migration",
				neighbors.isEmpty());
		Assert.assertTrue("Should find at least 2 neighbors",
				neighbors.size() >= 2);

		store.close();
		library.stop();
	}

	/**
	 * Verifies that only complete records (with both freqData and featureData)
	 * are persisted by the legacy format and subsequently migrated. Incomplete
	 * records are excluded by {@link AudioLibrary#allDetails()} which only
	 * streams entries in completeIdentifiers.
	 */
	@Test(timeout = 30000)
	public void migrateOnlyIncludesCompleteRecords() {
		AudioLibrary library = new AudioLibrary(
				legacyDir.toFile(), 44100);
		WaveDetails complete = createDetailsWithFeatures("complete-id", 4, 8);
		WaveDetails incomplete = new WaveDetails("incomplete-id", 44100);
		incomplete.setFreqData(new PackedCollection(1));
		incomplete.setSimilarities(new HashMap<>());

		library.include(complete);
		library.include(incomplete);

		String prefix = legacyDir.resolve("lib").toString();
		AudioLibraryPersistence.saveLibrary(library, prefix);

		int migrated = AudioLibraryMigration.migrate(
				Path.of(prefix), storeDir);
		Assert.assertEquals("Only complete record should be migrated", 1, migrated);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertEquals("Store should contain 1 record", 1, store.size());

		WaveDetails completeReloaded = store.get("complete-id");
		Assert.assertNotNull("Complete record should be present", completeReloaded);
		Assert.assertNotNull("Feature data should be preserved for complete record",
				completeReloaded.getFeatureData());

		Assert.assertFalse("Incomplete record should not be in store",
				store.containsKey("incomplete-id"));

		store.close();
		library.stop();
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Creates a legacy-format library with the given number of complete
	 * records and saves it using {@link AudioLibraryPersistence}.
	 */
	private AudioLibrary createAndSaveLegacyLibrary(int count) {
		AudioLibrary library = new AudioLibrary(
				legacyDir.toFile(), 44100);

		for (int i = 0; i < count; i++) {
			WaveDetails details = createDetailsWithFeatures(
					"migrate-id-" + i, 4, 8);
			library.include(details);
		}

		String prefix = legacyDir.resolve("lib").toString();
		AudioLibraryPersistence.saveLibrary(library, prefix);
		return library;
	}

	/**
	 * Creates a {@link WaveDetails} with non-null freqData and featureData
	 * containing distinct values per record to verify data integrity.
	 */
	private WaveDetails createDetailsWithFeatures(String identifier,
												  int frames, int bins) {
		WaveDetails details = new WaveDetails(identifier, 44100);
		details.setFreqData(new PackedCollection(bins));

		PackedCollection featureData = new PackedCollection(frames, bins, 1);
		for (int f = 0; f < frames; f++) {
			for (int b = 0; b < bins; b++) {
				featureData.setMem(f * bins + b,
						(identifier.hashCode() + f * bins + b) * 0.001);
			}
		}
		details.setFeatureData(featureData);
		details.setFeatureSampleRate(16.0);
		details.setFeatureChannelCount(1);
		details.setSimilarities(new HashMap<>());
		return details;
	}
}
