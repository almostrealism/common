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

package org.almostrealism.persist.test;

import org.almostrealism.persist.DiskStore;
import org.almostrealism.persist.ProtobufDiskStore;
import org.almostrealism.persist.RecordCodec;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link ProtobufDiskStore} verifying round-trip persistence,
 * memory cap enforcement, pairwise scan correctness, and disk I/O behavior.
 */
public class ProtobufDiskStoreTest extends TestSuiteBase {

	private File tempDir;

	/** Simple codec that stores strings as UTF-8 bytes. */
	private static final RecordCodec<String> STRING_CODEC = new RecordCodec<String>() {
		@Override
		public byte[] encode(String record) {
			return record.getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public String decode(byte[] data) {
			return new String(data, StandardCharsets.UTF_8);
		}

		@Override
		public int estimateSize(String record) {
			return record.length() * 2;
		}
	};

	@Before
	public void setUp() throws Exception {
		tempDir = Files.createTempDirectory("diskstore-test").toFile();
	}

	@After
	public void tearDown() {
		deleteRecursively(tempDir);
	}

	/** Insert a record, retrieve it, verify equality. */
	@Test
	public void putGetRoundTrip() {
		try (ProtobufDiskStore<String> store =
					 new ProtobufDiskStore<>(tempDir, STRING_CODEC)) {
			store.put("key1", "hello world");
			String result = store.get("key1");
			Assert.assertEquals("hello world", result);
		}
	}

	/** Insert, delete, verify get returns null. */
	@Test
	public void deleteRemovesRecord() {
		try (ProtobufDiskStore<String> store =
					 new ProtobufDiskStore<>(tempDir, STRING_CODEC)) {
			store.put("key1", "value1");
			store.flush();
			Assert.assertNotNull(store.get("key1"));

			store.delete("key1");
			Assert.assertNull(store.get("key1"));
			Assert.assertEquals(0, store.size());
		}
	}

	/**
	 * Write records, close the store, create a fresh instance,
	 * verify all records are loadable.
	 */
	@Test
	public void persistenceAcrossRestart() {
		try (ProtobufDiskStore<String> store =
					 new ProtobufDiskStore<>(tempDir, STRING_CODEC)) {
			store.put("a", "alpha");
			store.put("b", "bravo");
			store.put("c", "charlie");
		}

		try (ProtobufDiskStore<String> store2 =
					 new ProtobufDiskStore<>(tempDir, STRING_CODEC)) {
			Assert.assertEquals(3, store2.size());
			Assert.assertEquals("alpha", store2.get("a"));
			Assert.assertEquals("bravo", store2.get("b"));
			Assert.assertEquals("charlie", store2.get("c"));
		}
	}

	/**
	 * Insert records exceeding the memory budget and verify the
	 * FrequencyCache size stays within the configured capacity.
	 */
	@Test
	public void memoryCap() {
		int batchSize = 1024;
		long maxMemory = 4096;
		int maxBatches = (int) (maxMemory / batchSize);

		try (ProtobufDiskStore<String> store = new ProtobufDiskStore<>(
				tempDir, STRING_CODEC, maxMemory, batchSize)) {

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < batchSize; i++) {
				sb.append('X');
			}
			String largeValue = sb.toString();

			int totalRecords = maxBatches * 4;
			for (int i = 0; i < totalRecords; i++) {
				store.put("rec-" + i, largeValue + "-" + i);
			}

			store.flush();

			Assert.assertTrue(
					"Cached batch count " + store.getCachedBatchCount()
							+ " should not exceed capacity " + maxBatches,
					store.getCachedBatchCount() <= maxBatches);

			Assert.assertEquals(totalRecords, store.size());

			for (int i = 0; i < totalRecords; i++) {
				String value = store.get("rec-" + i);
				Assert.assertNotNull("Record rec-" + i + " should exist", value);
				Assert.assertTrue(value.startsWith(largeValue));
			}
		}
	}

	/**
	 * Insert N records, pairwise scan, verify exactly N*(N-1)/2
	 * pairs visited.
	 */
	@Test
	public void pairwiseScanVisitsAllPairs() {
		int batchSize = 256;
		long maxMemory = 4096;

		try (ProtobufDiskStore<String> store = new ProtobufDiskStore<>(
				tempDir, STRING_CODEC, maxMemory, batchSize)) {

			int n = 10;
			for (int i = 0; i < n; i++) {
				store.put("item-" + i, "value-" + i);
			}

			Set<String> pairs = new HashSet<>();
			store.pairwiseScan((a, b) -> {
				String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
				pairs.add(key);
			});

			int expectedPairs = n * (n - 1) / 2;
			Assert.assertEquals(
					"Expected " + expectedPairs + " unique pairs",
					expectedPairs, pairs.size());
		}
	}

	/**
	 * Verify that pairwise scan does not load the same batch an
	 * excessive number of times.
	 */
	@Test
	public void pairwiseScanDiskIO() {
		int batchSize = 128;
		long maxMemory = 4096;

		try (ProtobufDiskStore<String> store = new ProtobufDiskStore<>(
				tempDir, STRING_CODEC, maxMemory, batchSize)) {

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 100; i++) {
				sb.append('Y');
			}
			String payload = sb.toString();

			for (int i = 0; i < 30; i++) {
				store.put("r-" + i, payload + "-" + i);
			}
			store.flush();

			AtomicInteger loadCount = new AtomicInteger(0);
			store.setLoadListener((batchId, batch) -> loadCount.incrementAndGet());

			store.pairwiseScan((a, b) -> { });

			int numBatches = store.size();
			int maxExpectedLoads = numBatches * numBatches;
			Assert.assertTrue(
					"Batch loads (" + loadCount.get()
							+ ") should be bounded by O(N^2) where N is batch count,"
							+ " max expected: " + maxExpectedLoads,
					loadCount.get() <= maxExpectedLoads);
		}
	}

	/** Insert N records, scan, verify all N visited. */
	@Test
	public void scanVisitsAllRecords() {
		try (ProtobufDiskStore<String> store =
					 new ProtobufDiskStore<>(tempDir, STRING_CODEC)) {
			int n = 20;
			Set<String> expected = new HashSet<>();
			for (int i = 0; i < n; i++) {
				String val = "scan-val-" + i;
				store.put("scan-" + i, val);
				expected.add(val);
			}

			List<String> visited = new ArrayList<>();
			store.scan(visited::add);

			Assert.assertEquals(n, visited.size());
			Assert.assertTrue(
					"All records should be visited",
					new HashSet<>(visited).containsAll(expected));
		}
	}

	/**
	 * Insert enough records to span multiple batch files,
	 * verify all are retrievable.
	 */
	@Test
	public void multipleRecordsAcrossBatches() {
		int batchSize = 128;

		try (ProtobufDiskStore<String> store = new ProtobufDiskStore<>(
				tempDir, STRING_CODEC, DEFAULT_MEM, batchSize)) {

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 80; i++) {
				sb.append('Z');
			}
			String payload = sb.toString();

			int n = 50;
			for (int i = 0; i < n; i++) {
				store.put("multi-" + i, payload + "-" + i);
			}
			store.flush();

			File[] batchFiles = tempDir.listFiles(
					(dir, name) -> name.startsWith("batch_"));
			Assert.assertNotNull(batchFiles);
			Assert.assertTrue(
					"Should have multiple batch files, got " + batchFiles.length,
					batchFiles.length > 1);

			for (int i = 0; i < n; i++) {
				String value = store.get("multi-" + i);
				Assert.assertNotNull(
						"Record multi-" + i + " should exist", value);
				Assert.assertTrue(value.startsWith(payload));
			}
		}
	}

	private static final long DEFAULT_MEM = ProtobufDiskStore.DEFAULT_MAX_MEMORY_BYTES;

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}
}
