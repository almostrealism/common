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

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.studio.discovery.PrototypeDiscovery;
import org.almostrealism.studio.persistence.AudioLibraryMigration;
import org.almostrealism.studio.persistence.AudioLibraryPersistence;
import org.almostrealism.studio.persistence.LibraryDestination;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import io.almostrealism.code.Precision;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the store-backed AudioLibrary startup path, verifying that:
 * <ol>
 *   <li>Migration from old format to ProtobufDiskStore is memory-bounded</li>
 *   <li>Store-backed libraries never call loadSingleDetail</li>
 *   <li>Full startup (migration + prototype discovery) completes quickly</li>
 * </ol>
 */
public class AudioLibraryStartupTest extends TestSuiteBase {

	private Path tempDir;
	private Path oldDataDir;
	private Path storeDir;
	private Path samplesDir;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("startup-test");
		oldDataDir = tempDir.resolve("old");
		storeDir = tempDir.resolve("store");
		samplesDir = tempDir.resolve("samples");

		Files.createDirectories(oldDataDir);
		Files.createDirectories(storeDir);
		Files.createDirectories(samplesDir);

		AudioLibraryPersistence.resetCallRateLimit();
	}

	@After
	public void tearDown() {
		AudioLibraryPersistence.resetCallRateLimit();
		deleteRecursive(tempDir.toFile());
	}

	/**
	 * Verifies that migration from old batch format to ProtobufDiskStore
	 * completes without loading similarity data into memory. Creates
	 * entries with large similarity maps that would cause OOM if loaded.
	 */
	@Test(timeout = 60000)
	public void migrationSkipsSimilarities() throws Exception {
		int entryCount = 200;

		// Create old-format batch file with entries that have large similarity maps
		writeOldFormatBatch(oldDataDir.resolve("library"), entryCount, true);

		// Migrate
		String prefix = oldDataDir.resolve("library").toString();
		int migrated = AudioLibraryMigration.migrate(
				Path.of(prefix), storeDir);

		Assert.assertEquals("All entries should be migrated", entryCount, migrated);

		// Verify store has all entries
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertEquals(entryCount, store.size());

		// Verify similarity maps were not copied (they are N² data)
		WaveDetails loaded = store.get("entry-0");
		Assert.assertNotNull("Entry should exist in store", loaded);
		Assert.assertTrue("Similarities should be empty after migration",
				loaded.getSimilarities().isEmpty());

		store.close();
	}

	/**
	 * Verifies that a store-backed AudioLibrary never calls loadSingleDetail
	 * even when iterating all entries (which exceeds cache capacity).
	 * The loadSingleDetail rate guard would throw if it were called.
	 */
	@Test(timeout = 30000)
	public void storeBackedLibraryNeverCallsLoadSingleDetail() throws IOException {
		int entryCount = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY + 200;
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());

		// Populate the store directly
		for (int i = 0; i < entryCount; i++) {
			WaveDetails details = createCompleteDetails("entry-" + i);
			details.setPersistent(true);
			PackedCollection embedding = createEmbedding(i);
			store.put("entry-" + i, details, embedding);
		}
		store.flush();

		// Create store-backed library (no detailsLoader set)
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				44100, store);

		Assert.assertEquals("All entries should be known",
				entryCount, library.getAllIdentifiers().size());

		// Iterate all details multiple times — this forces cache evictions
		// and re-resolution via the store. If loadSingleDetail were called
		// instead, the rate guard would throw.
		for (int pass = 0; pass < 3; pass++) {
			long count = library.allDetails().count();
			Assert.assertEquals("All entries should be resolvable",
					entryCount, count);
		}

		library.stop();
		store.close();
	}

	/**
	 * Simulates the full startup sequence: migrate old data, create
	 * store-backed library, and run prototype discovery. Verifies
	 * completion within a reasonable time bound.
	 */
	@Test(timeout = 60000)
	public void fullStartupSequenceWithPrototypeDiscovery() throws Exception {
		int entryCount = 100;

		// Create old-format batch file
		writeOldFormatBatch(oldDataDir.resolve("library"), entryCount, false);

		// Step 1: Migrate
		long startMigrate = System.currentTimeMillis();
		LibraryDestination dest = new LibraryDestination(
				oldDataDir.resolve("library").toString());
		ProtobufWaveDetailsStore store = dest.migrateAndOpenStore(storeDir.toFile());
		long migrateMs = System.currentTimeMillis() - startMigrate;
		System.out.println("Migration: " + migrateMs + "ms, entries: " + store.size());

		Assert.assertEquals(entryCount, store.size());

		// Step 2: Create store-backed library
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				44100, store);

		Assert.assertEquals(entryCount, library.getAllIdentifiers().size());

		// Step 3: Prototype discovery (sparse graph via HNSW)
		long startDiscovery = System.currentTimeMillis();
		List<PrototypeDiscovery.PrototypeResult> prototypes =
				PrototypeDiscovery.discoverPrototypes(library, 5, null);
		long discoveryMs = System.currentTimeMillis() - startDiscovery;
		System.out.println("Discovery: " + discoveryMs + "ms, prototypes: " + prototypes.size());

		Assert.assertFalse("Should find at least one prototype", prototypes.isEmpty());

		library.stop();
		store.close();

		long totalMs = migrateMs + discoveryMs;
		System.out.println("Total startup: " + totalMs + "ms");
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Writes an old-format batch file containing the specified number of
	 * entries with freq and feature data, and optionally large similarity maps.
	 */
	private void writeOldFormatBatch(Path prefix, int count,
									 boolean includeSimilarities) throws IOException {
		Audio.AudioLibraryData.Builder data = Audio.AudioLibraryData.newBuilder();

		for (int i = 0; i < count; i++) {
			Audio.WaveDetailData.Builder entry = Audio.WaveDetailData.newBuilder()
					.setIdentifier("entry-" + i)
					.setSampleRate(44100)
					.setChannelCount(1)
					.setFrameCount(1024)
					.setPersistent(true)
					.setFreqSampleRate(44100)
					.setFreqBinCount(16)
					.setFreqChannelCount(1)
					.setFreqFrameCount(8)
					.setFreqData(CollectionEncoder.encode(
							new PackedCollection(16), Precision.FP32))
					.setFeatureSampleRate(64)
					.setFeatureBinCount(32)
					.setFeatureChannelCount(1)
					.setFeatureFrameCount(16)
					.setFeatureData(CollectionEncoder.encode(
							createFeatureData(i), Precision.FP32));

			if (includeSimilarities) {
				Map<String, Double> sims = new HashMap<>();
				for (int j = 0; j < count; j++) {
					if (j != i) sims.put("entry-" + j, Math.random());
				}
				entry.putAllSimilarities(sims);
			}

			data.putInfo("entry-" + i, entry.build());
		}

		File outFile = new File(prefix + "_0.bin");
		outFile.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(outFile)) {
			data.build().writeTo(out);
		}
	}

	private WaveDetails createCompleteDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier, 44100);
		details.setFreqData(new PackedCollection(8, 16, 1));
		details.setFeatureData(createFeatureData(identifier.hashCode()));
		details.setFeatureBinCount(32);
		details.setFeatureFrameCount(16);
		details.setSimilarities(new HashMap<>());
		return details;
	}

	/** Creates distinct 2D feature data (frames × bins) so HNSW can build a meaningful index. */
	private PackedCollection createFeatureData(int seed) {
		PackedCollection features = new PackedCollection(16, 32, 1);
		for (int i = 0; i < features.getMemLength(); i++) {
			features.setMem(i, Math.sin(seed * 0.1 + i * 0.01) * 0.5 + 0.5);
		}
		return features;
	}

	/** Creates a mean-pooled embedding vector from feature data. */
	private PackedCollection createEmbedding(int seed) {
		PackedCollection embedding = new PackedCollection(32);
		for (int i = 0; i < 32; i++) {
			embedding.setMem(i, Math.sin(seed * 0.1 + i * 0.3) * 0.5 + 0.5);
		}
		return embedding;
	}

	private void deleteRecursive(File file) {
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursive(child);
				}
			}
		}
		file.delete();
	}
}
