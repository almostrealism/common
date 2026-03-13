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

import org.almostrealism.persist.ProtobufDiskStore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link ProtobufDiskStore} verifying round-trip persistence,
 * memory cap enforcement, pairwise scan correctness, and disk I/O behavior.
 *
 * <p>Uses {@link TestRecordProto.TestRecord} as a concrete protobuf message
 * type — no wrapper messages or double serialization.</p>
 */
public class ProtobufDiskStoreTest extends TestSuiteBase {

	private File tempDir;

	@Before
	public void setUp() throws Exception {
		tempDir = Files.createTempDirectory("diskstore-test").toFile();
	}

	@After
	public void tearDown() {
		deleteRecursively(tempDir);
	}

	/** Insert a record, retrieve it, verify equality. */
	@Test(timeout = 30000)
	public void putGetRoundTrip() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			TestRecordProto.TestRecord record = TestRecordProto.TestRecord.newBuilder()
					.setId("key1")
					.setContent("hello world")
					.setValue(42)
					.build();
			store.put("key1", record);

			TestRecordProto.TestRecord result = store.get("key1");
			Assert.assertNotNull(result);
			Assert.assertEquals("hello world", result.getContent());
			Assert.assertEquals(42, result.getValue());
		}
	}

	/** Insert, delete, verify get returns null. */
	@Test(timeout = 30000)
	public void deleteRemovesRecord() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "value1", 1));
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
	@Test(timeout = 30000)
	public void persistenceAcrossRestart() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("a", makeRecord("a", "alpha", 1));
			store.put("b", makeRecord("b", "bravo", 2));
			store.put("c", makeRecord("c", "charlie", 3));
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store2 =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertEquals(3, store2.size());
			Assert.assertEquals("alpha", store2.get("a").getContent());
			Assert.assertEquals("bravo", store2.get("b").getContent());
			Assert.assertEquals("charlie", store2.get("c").getContent());
		}
	}

	/**
	 * Insert records exceeding the memory budget and verify the
	 * FrequencyCache size stays within the configured capacity.
	 */
	@Test(timeout = 30000)
	public void memoryCap() {
		int batchSize = 1024;
		long maxMemory = 4096;
		int maxBatches = (int) (maxMemory / batchSize);

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = new ProtobufDiskStore<>(
				tempDir, TestRecordProto.TestRecord.parser(), maxMemory, batchSize)) {

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < batchSize; i++) {
				sb.append('X');
			}
			String largeContent = sb.toString();

			int totalRecords = maxBatches * 4;
			for (int i = 0; i < totalRecords; i++) {
				store.put("rec-" + i, makeRecord("rec-" + i, largeContent + "-" + i, i));
			}

			store.flush();

			Assert.assertTrue(
					"Cached batch count " + store.getCachedBatchCount()
							+ " should not exceed capacity " + maxBatches,
					store.getCachedBatchCount() <= maxBatches);

			Assert.assertEquals(totalRecords, store.size());

			for (int i = 0; i < totalRecords; i++) {
				TestRecordProto.TestRecord value = store.get("rec-" + i);
				Assert.assertNotNull("Record rec-" + i + " should exist", value);
				Assert.assertTrue(value.getContent().startsWith(largeContent));
			}
		}
	}

	/**
	 * Insert N records, pairwise scan, verify exactly N*(N-1)/2
	 * pairs visited.
	 */
	@Test(timeout = 30000)
	public void pairwiseScanVisitsAllPairs() {
		int batchSize = 256;
		long maxMemory = 4096;

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = new ProtobufDiskStore<>(
				tempDir, TestRecordProto.TestRecord.parser(), maxMemory, batchSize)) {

			int n = 10;
			for (int i = 0; i < n; i++) {
				store.put("item-" + i, makeRecord("item-" + i, "value-" + i, i));
			}

			Set<String> pairs = new HashSet<>();
			store.pairwiseScan((a, b) -> {
				String keyA = a.getId();
				String keyB = b.getId();
				String key = keyA.compareTo(keyB) < 0 ? keyA + "|" + keyB : keyB + "|" + keyA;
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
	@Test(timeout = 30000)
	public void pairwiseScanDiskIO() {
		int batchSize = 128;
		long maxMemory = 4096;

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = new ProtobufDiskStore<>(
				tempDir, TestRecordProto.TestRecord.parser(), maxMemory, batchSize)) {

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 100; i++) {
				sb.append('Y');
			}
			String payload = sb.toString();

			for (int i = 0; i < 30; i++) {
				store.put("r-" + i, makeRecord("r-" + i, payload + "-" + i, i));
			}
			store.flush();

			AtomicInteger loadCount = new AtomicInteger(0);
			store.setLoadListener(batchId -> loadCount.incrementAndGet());

			store.pairwiseScan((a, b) -> { });

			File[] batchFiles = tempDir.listFiles(
					(dir, name) -> name.startsWith("batch_"));
			int numBatches = batchFiles == null ? 0 : batchFiles.length;
			int maxExpectedLoads = numBatches * numBatches;
			Assert.assertTrue(
					"Batch loads (" + loadCount.get()
							+ ") should be bounded by O(N^2) where N is batch count,"
							+ " max expected: " + maxExpectedLoads,
					loadCount.get() <= maxExpectedLoads);
		}
	}

	/** Insert N records, scan, verify all N visited. */
	@Test(timeout = 30000)
	public void scanVisitsAllRecords() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			int n = 20;
			Set<String> expected = new HashSet<>();
			for (int i = 0; i < n; i++) {
				String content = "scan-val-" + i;
				store.put("scan-" + i, makeRecord("scan-" + i, content, i));
				expected.add(content);
			}

			List<String> visited = new ArrayList<>();
			store.scan(record -> visited.add(record.getContent()));

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
	@Test(timeout = 30000)
	public void multipleRecordsAcrossBatches() {
		int batchSize = 128;

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = new ProtobufDiskStore<>(
				tempDir, TestRecordProto.TestRecord.parser(), DEFAULT_MEM, batchSize)) {

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 80; i++) {
				sb.append('Z');
			}
			String payload = sb.toString();

			int n = 50;
			for (int i = 0; i < n; i++) {
				store.put("multi-" + i, makeRecord("multi-" + i, payload + "-" + i, i));
			}
			store.flush();

			File[] batchFiles = tempDir.listFiles(
					(dir, name) -> name.startsWith("batch_"));
			Assert.assertNotNull(batchFiles);
			Assert.assertTrue(
					"Should have multiple batch files, got " + batchFiles.length,
					batchFiles.length > 1);

			for (int i = 0; i < n; i++) {
				TestRecordProto.TestRecord value = store.get("multi-" + i);
				Assert.assertNotNull(
						"Record multi-" + i + " should exist", value);
				Assert.assertTrue(value.getContent().startsWith(payload));
			}
		}
	}

	/** Verify that getting a non-existent key returns null. */
	@Test(timeout = 30000)
	public void getNonExistentKeyReturnsNull() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertNull(store.get("does-not-exist"));
		}
	}

	/** Verify that deleting a non-existent key is a no-op. */
	@Test(timeout = 30000)
	public void deleteNonExistentKeyIsNoOp() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "value1", 1));
			store.delete("does-not-exist");
			Assert.assertEquals(1, store.size());
			Assert.assertNotNull(store.get("key1"));
		}
	}

	/** Verify that replacing an existing pending record returns the new value. */
	@Test(timeout = 30000)
	public void putReplacesPendingRecord() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "original", 1));
			store.put("key1", makeRecord("key1", "replaced", 2));

			Assert.assertEquals(1, store.size());
			TestRecordProto.TestRecord result = store.get("key1");
			Assert.assertNotNull(result);
			Assert.assertEquals("replaced", result.getContent());
			Assert.assertEquals(2, result.getValue());
		}
	}

	/** Verify that replacing a flushed record returns the new value. */
	@Test(timeout = 30000)
	public void putReplacesFlushedRecord() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "original", 1));
			store.flush();
			store.put("key1", makeRecord("key1", "replaced", 2));

			Assert.assertEquals(1, store.size());
			TestRecordProto.TestRecord result = store.get("key1");
			Assert.assertNotNull(result);
			Assert.assertEquals("replaced", result.getContent());
			Assert.assertEquals(2, result.getValue());
		}
	}

	/** Verify that scan visits the replaced record only once with the new value. */
	@Test(timeout = 30000)
	public void scanAfterReplaceVisitsNewValueOnce() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "original", 1));
			store.flush();
			store.put("key1", makeRecord("key1", "replaced", 2));

			List<String> visited = new ArrayList<>();
			store.scan(record -> visited.add(record.getContent()));

			Assert.assertEquals(1, visited.size());
			Assert.assertEquals("replaced", visited.get(0));
		}
	}

	/** Verify empty store operations. */
	@Test(timeout = 30000)
	public void emptyStoreOperations() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertEquals(0, store.size());
			Assert.assertNull(store.get("anything"));

			List<TestRecordProto.TestRecord> scanned = new ArrayList<>();
			store.scan(scanned::add);
			Assert.assertTrue(scanned.isEmpty());

			AtomicInteger pairCount = new AtomicInteger(0);
			store.pairwiseScan((a, b) -> pairCount.incrementAndGet());
			Assert.assertEquals(0, pairCount.get());
		}
	}

	/** Verify that a deleted key can be re-inserted. */
	@Test(timeout = 30000)
	public void reinsertAfterDelete() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "first", 1));
			store.flush();
			store.delete("key1");
			Assert.assertNull(store.get("key1"));

			store.put("key1", makeRecord("key1", "second", 2));
			TestRecordProto.TestRecord result = store.get("key1");
			Assert.assertNotNull(result);
			Assert.assertEquals("second", result.getContent());
			Assert.assertEquals(1, store.size());
		}
	}

	/** Verify that closing a store twice does not throw. */
	@Test(timeout = 30000)
	public void closeTwiceIsIdempotent() {
		ProtobufDiskStore<TestRecordProto.TestRecord> store =
				new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser());
		store.put("key1", makeRecord("key1", "value1", 1));
		store.close();
		store.close();
	}

	/** Verify that a deleted record stays deleted after reopening the store. */
	@Test(timeout = 30000)
	public void deletePersistsAcrossRestart() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("a", makeRecord("a", "alpha", 1));
			store.put("b", makeRecord("b", "bravo", 2));
			store.flush();
			store.delete("a");
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store2 =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertEquals(1, store2.size());
			Assert.assertNull(store2.get("a"));
			Assert.assertEquals("bravo", store2.get("b").getContent());
		}
	}

	/** Verify that pairwise scan on a single record produces zero pairs. */
	@Test(timeout = 30000)
	public void singleRecordPairwiseScanProducesZeroPairs() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("only", makeRecord("only", "alone", 1));

			AtomicInteger pairCount = new AtomicInteger(0);
			store.pairwiseScan((a, b) -> pairCount.incrementAndGet());
			Assert.assertEquals(0, pairCount.get());
		}
	}

	/** Verify that calling flush on an empty store is a no-op. */
	@Test(timeout = 30000)
	public void flushEmptyStoreIsNoOp() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.flush();
			Assert.assertEquals(0, store.size());

			File[] batchFiles = tempDir.listFiles(
					(dir, name) -> name.startsWith("batch_"));
			Assert.assertTrue(
					"No batch files should be created for empty flush",
					batchFiles == null || batchFiles.length == 0);
		}
	}

	/** Verify getCacheCapacity returns the correct computed value. */
	@Test(timeout = 30000)
	public void cacheCapacityMatchesConfiguration() {
		int batchSize = 512;
		long maxMemory = 4096;
		int expectedCapacity = (int) (maxMemory / batchSize);

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = new ProtobufDiskStore<>(
				tempDir, TestRecordProto.TestRecord.parser(), maxMemory, batchSize)) {
			Assert.assertEquals(expectedCapacity, store.getCacheCapacity());
		}
	}

	/** Verify getCacheCapacity has a minimum of 2. */
	@Test(timeout = 30000)
	public void cacheCapacityMinimumIsTwo() {
		long maxMemory = 1;
		int batchSize = 4096;

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = new ProtobufDiskStore<>(
				tempDir, TestRecordProto.TestRecord.parser(), maxMemory, batchSize)) {
			Assert.assertEquals(2, store.getCacheCapacity());
		}
	}

	/** Verify that a corrupted index file is handled gracefully and the store starts fresh. */
	@Test(timeout = 30000)
	public void corruptedIndexFileStartsFresh() throws Exception {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "value1", 1));
		}

		File indexFile = new File(tempDir, "index.bin");
		Assert.assertTrue("Index file should exist", indexFile.exists());

		try (FileOutputStream fos = new FileOutputStream(indexFile)) {
			fos.write(new byte[]{0x7F, 0x7F, 0x7F, 0x7F, 0x7F});
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store2 =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertEquals(
					"Store should start fresh with corrupted index",
					0, store2.size());
		}
	}

	/** Verify that malformed batch file names in the directory are ignored. */
	@Test(timeout = 30000)
	public void malformedBatchFileNamesAreIgnored() throws Exception {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("key1", makeRecord("key1", "value1", 1));
			store.flush();
		}

		File malformedFile = new File(tempDir, "batch_notanumber.bin");
		Assert.assertTrue(malformedFile.createNewFile());

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store2 =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertEquals(1, store2.size());
			Assert.assertEquals("value1", store2.get("key1").getContent());

			List<String> scanned = new ArrayList<>();
			store2.scan(record -> scanned.add(record.getContent()));
			Assert.assertEquals(1, scanned.size());
			Assert.assertEquals("value1", scanned.get(0));
		}
	}

	/** Verify that flush persists deletions without requiring close/reopen. */
	@Test(timeout = 30000)
	public void flushPersistsDeletions() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("a", makeRecord("a", "alpha", 1));
			store.put("b", makeRecord("b", "bravo", 2));
			store.flush();

			store.delete("a");
			store.flush();
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store2 =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertEquals(1, store2.size());
			Assert.assertNull(store2.get("a"));
			Assert.assertEquals("bravo", store2.get("b").getContent());
		}
	}

	private static TestRecordProto.TestRecord makeRecord(String id, String content, int value) {
		return TestRecordProto.TestRecord.newBuilder()
				.setId(id)
				.setContent(content)
				.setValue(value)
				.build();
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
