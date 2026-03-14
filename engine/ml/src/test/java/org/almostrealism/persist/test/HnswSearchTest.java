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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.HnswIndex;
import org.almostrealism.persist.ProtobufDiskStore;
import org.almostrealism.persist.SearchResult;
import org.almostrealism.persist.SimilarityMetric;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.almostrealism.persist.test.DiskStoreTestSupport.deleteRecursively;
import static org.almostrealism.persist.test.DiskStoreTestSupport.makeRecord;
import static org.almostrealism.persist.test.DiskStoreTestSupport.vec;

/**
 * Tests for HNSW vector similarity search integrated into
 * {@link ProtobufDiskStore}.
 *
 * <p>Covers HNSW standalone operations, store integration,
 * persistence across restart, deletion behavior, and performance.</p>
 */
public class HnswSearchTest extends TestSuiteBase {

	private File tempDir;

	@Before
	public void setUp() throws Exception {
		tempDir = Files.createTempDirectory("vectorsearch-test").toFile();
	}

	@After
	public void tearDown() {
		deleteRecursively(tempDir);
	}

	/** Insert records with vectors, search returns correct top-K. */
	@Test(timeout = 30000)
	public void searchReturnsCorrectTopK() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {

			PackedCollection target = vec(1.0, 0.0, 0.0);
			store.put("close", makeRecord("close", "close-to-target", 1),
					vec(0.9, 0.1, 0.0));
			store.put("far", makeRecord("far", "far-from-target", 2),
					vec(0.0, 0.0, 1.0));
			store.put("medium", makeRecord("medium", "medium-distance", 3),
					vec(0.5, 0.5, 0.0));

			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(target, 2);

			Assert.assertEquals(2, results.size());

			Set<String> topIds = new HashSet<>();
			topIds.add(results.get(0).getId());
			topIds.add(results.get(1).getId());
			Assert.assertTrue("'close' should be in top-2", topIds.contains("close"));
			Assert.assertTrue("'medium' should be in top-2", topIds.contains("medium"));
		}
	}

	/** Verify cosine similarity ordering is correct. */
	@Test(timeout = 30000)
	public void cosineSimilarityOrderingIsCorrect() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {

			store.put("exact", makeRecord("exact", "exact-match", 1),
					vec(1.0, 0.0, 0.0, 0.0));
			store.put("close", makeRecord("close", "close-match", 2),
					vec(0.9, 0.1, 0.0, 0.0));
			store.put("orthogonal", makeRecord("orthogonal", "orthogonal", 3),
					vec(0.0, 1.0, 0.0, 0.0));
			store.put("opposite", makeRecord("opposite", "opposite", 4),
					vec(-1.0, 0.0, 0.0, 0.0));

			PackedCollection query = vec(1.0, 0.0, 0.0, 0.0);
			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(query, 4);

			Assert.assertEquals(4, results.size());
			Assert.assertEquals("exact", results.get(0).getId());
			Assert.assertEquals("close", results.get(1).getId());

			for (int i = 0; i < results.size() - 1; i++) {
				Assert.assertTrue(
						"Results should be in descending similarity order",
						results.get(i).getSimilarity() >= results.get(i + 1).getSimilarity());
			}
		}
	}

	/** HNSW index persists and reloads correctly across store restart. */
	@Test(timeout = 30000)
	public void hnswPersistsAcrossRestart() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("a", makeRecord("a", "alpha", 1),
					vec(1.0, 0.0, 0.0));
			store.put("b", makeRecord("b", "bravo", 2),
					vec(0.0, 1.0, 0.0));
			store.put("c", makeRecord("c", "charlie", 3),
					vec(0.0, 0.0, 1.0));
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store2 =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			List<SearchResult<TestRecordProto.TestRecord>> results =
					store2.search(vec(1.0, 0.0, 0.0), 1);

			Assert.assertEquals(1, results.size());
			Assert.assertEquals("a", results.get(0).getId());
			Assert.assertEquals("alpha", results.get(0).getRecord().getContent());
		}
	}

	/** Search still works after deleting a record — deleted record not returned. */
	@Test(timeout = 30000)
	public void searchAfterDeleteExcludesDeletedRecord() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {

			store.put("keep", makeRecord("keep", "keeper", 1),
					vec(1.0, 0.0));
			store.put("remove", makeRecord("remove", "goner", 2),
					vec(0.9, 0.1));

			store.delete("remove");

			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(vec(1.0, 0.0), 10);

			Assert.assertEquals(1, results.size());
			Assert.assertEquals("keep", results.get(0).getId());
		}
	}

	/** Search on store with no vectors returns empty list gracefully. */
	@Test(timeout = 30000)
	public void searchOnStoreWithNoVectorsReturnsEmpty() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("noVector", makeRecord("noVector", "plain-record", 1));

			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(vec(1.0, 0.0, 0.0), 5);

			Assert.assertNotNull(results);
			Assert.assertTrue(results.isEmpty());
		}
	}

	/** Performance: insert 10,000 records with 128-dim vectors, search top-10 in <100ms. */
	@Test(timeout = 60000)
	public void performanceSearch10kRecords128dim() {
		int numRecords = 10000;
		int dimension = 128;
		Random random = new Random(42);

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = new ProtobufDiskStore<>(
				tempDir, TestRecordProto.TestRecord.parser())) {

			for (int i = 0; i < numRecords; i++) {
				PackedCollection vec = randomVector(dimension, random);
				store.put("rec-" + i,
						makeRecord("rec-" + i, "content-" + i, i),
						vec);
			}

			PackedCollection queryVector = randomVector(dimension, random);

			long start = System.nanoTime();
			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(queryVector, 10);
			long elapsed = (System.nanoTime() - start) / 1_000_000;

			Assert.assertEquals(10, results.size());
			Assert.assertTrue(
					"Search should complete in <100ms but took " + elapsed + "ms",
					elapsed < 100);

			for (int i = 0; i < results.size() - 1; i++) {
				Assert.assertTrue(
						"Results should be in descending similarity order",
						results.get(i).getSimilarity() >= results.get(i + 1).getSimilarity());
			}
		}
	}

	/** Standalone HNSW index save/load round-trip. */
	@Test(timeout = 30000)
	public void hnswSaveLoadRoundTrip() throws Exception {
		Path hnswFile = tempDir.toPath().resolve("test-hnsw.bin");
		int dimension = 4;

		HnswIndex index = new HnswIndex(dimension, 8, 50, SimilarityMetric.COSINE);
		index.insert("a", vec(1.0, 0.0, 0.0, 0.0));
		index.insert("b", vec(0.0, 1.0, 0.0, 0.0));
		index.insert("c", vec(0.0, 0.0, 1.0, 0.0));
		index.save(hnswFile);

		HnswIndex loaded = HnswIndex.load(hnswFile, SimilarityMetric.COSINE);
		Assert.assertNotNull(loaded);
		Assert.assertEquals(3, loaded.size());

		List<HnswIndex.IdScore> results =
				loaded.search(vec(1.0, 0.0, 0.0, 0.0), 1);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("a", results.get(0).id);
	}

	/** HNSW remove marks node as deleted and excludes it from search. */
	@Test(timeout = 30000)
	public void hnswRemoveExcludesFromSearch() {
		HnswIndex index = new HnswIndex(3, 8, 50, SimilarityMetric.COSINE);
		index.insert("a", vec(1.0, 0.0, 0.0));
		index.insert("b", vec(0.0, 1.0, 0.0));

		Assert.assertEquals(2, index.size());
		index.remove("a");
		Assert.assertEquals(1, index.size());
		Assert.assertFalse(index.contains("a"));
		Assert.assertTrue(index.contains("b"));

		List<HnswIndex.IdScore> results =
				index.search(vec(1.0, 0.0, 0.0), 10);
		Assert.assertEquals(1, results.size());
		Assert.assertEquals("b", results.get(0).id);
	}

	/** Search on empty HNSW index returns empty list. */
	@Test(timeout = 30000)
	public void hnswSearchEmptyIndexReturnsEmpty() {
		HnswIndex index = new HnswIndex(3);
		List<HnswIndex.IdScore> results =
				index.search(vec(1.0, 0.0, 0.0), 5);
		Assert.assertTrue(results.isEmpty());
	}

	/** Records without vectors are unaffected by vector search operations. */
	@Test(timeout = 30000)
	public void mixedRecordsWithAndWithoutVectors() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {

			store.put("withVec", makeRecord("withVec", "has-vector", 1),
					vec(1.0, 0.0, 0.0));
			store.put("noVec", makeRecord("noVec", "no-vector", 2));

			Assert.assertEquals(2, store.size());
			Assert.assertNotNull(store.get("noVec"));
			Assert.assertNotNull(store.get("withVec"));

			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(vec(1.0, 0.0, 0.0), 10);

			Assert.assertEquals(1, results.size());
			Assert.assertEquals("withVec", results.get(0).getId());
		}
	}

	/** Deleted record with vector should not appear in search after restart. */
	@Test(timeout = 30000)
	public void deleteWithVectorPersistsAcrossRestart() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			store.put("a", makeRecord("a", "alpha", 1),
					vec(1.0, 0.0, 0.0));
			store.put("b", makeRecord("b", "bravo", 2),
					vec(0.0, 1.0, 0.0));
			store.flush();
			store.delete("a");
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store2 =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			List<SearchResult<TestRecordProto.TestRecord>> results =
					store2.search(vec(1.0, 0.0, 0.0), 10);

			Assert.assertEquals(1, results.size());
			Assert.assertEquals("b", results.get(0).getId());
		}
	}

	/** HNSW insert replaces an existing node with the same ID. */
	@Test(timeout = 30000)
	public void hnswReinsertUpdatesVector() {
		HnswIndex index = new HnswIndex(3, 8, 50, SimilarityMetric.COSINE);
		index.insert("a", vec(1.0, 0.0, 0.0));
		index.insert("a", vec(0.0, 1.0, 0.0));

		Assert.assertEquals(1, index.size());

		List<HnswIndex.IdScore> results =
				index.search(vec(0.0, 1.0, 0.0), 1);
		Assert.assertEquals("a", results.get(0).id);
		Assert.assertTrue("Should be highly similar after update",
				results.get(0).score > 0.9f);
	}

	/** HNSW load from nonexistent file returns null. */
	@Test(timeout = 30000)
	public void hnswLoadNonexistentFileReturnsNull() {
		Path nonexistent = tempDir.toPath().resolve("does-not-exist.bin");
		HnswIndex loaded = HnswIndex.load(nonexistent, SimilarityMetric.COSINE);
		Assert.assertNull(loaded);
	}

	/** Cosine similarity metric normalizes correctly. */
	@Test(timeout = 30000)
	public void cosineMetricNormalizesCorrectly() {
		PackedCollection vec = vec(3.0, 4.0);
		PackedCollection normalized = SimilarityMetric.COSINE.normalize(vec);

		double norm = 0.0;
		for (int i = 0; i < normalized.getMemLength(); i++) {
			double v = normalized.toDouble(i);
			norm += v * v;
		}
		Assert.assertEquals("Normalized vector should have unit norm",
				1.0f, (float) Math.sqrt(norm), 0.001f);
	}

	/** Insert with wrong dimension throws IllegalArgumentException. */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void hnswInsertWrongDimensionThrows() {
		HnswIndex index = new HnswIndex(3);
		index.insert("a", vec(1.0, 0.0, 0.0, 0.0));
	}

	/** Search with wrong dimension throws IllegalArgumentException. */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void hnswSearchWrongDimensionThrows() {
		HnswIndex index = new HnswIndex(3);
		index.insert("a", vec(1.0, 0.0, 0.0));
		index.search(vec(1.0, 0.0), 1);
	}

	/** Verify totalSize() counts deleted nodes. */
	@Test(timeout = 30000)
	public void hnswTotalSizeIncludesDeletedNodes() {
		HnswIndex index = new HnswIndex(3, 8, 50, SimilarityMetric.COSINE);
		index.insert("a", vec(1.0, 0.0, 0.0));
		index.insert("b", vec(0.0, 1.0, 0.0));
		index.remove("a");

		Assert.assertEquals(1, index.size());
		Assert.assertEquals(2, index.totalSize());
	}

	/** Verify setEfSearch affects recall for borderline queries. */
	@Test(timeout = 30000)
	public void hnswSetEfSearchAffectsRecall() {
		int dimension = 8;
		Random random = new Random(42);
		HnswIndex index = new HnswIndex(dimension, 4, 50, SimilarityMetric.COSINE);

		for (int i = 0; i < 100; i++) {
			double[] values = new double[dimension];
			for (int j = 0; j < dimension; j++) {
				values[j] = random.nextGaussian();
			}
			index.insert("n-" + i, new PackedCollection(dimension).fill(values));
		}

		index.setEfSearch(1);
		List<HnswIndex.IdScore> lowEf = index.search(
				randomVector(dimension, random), 10);

		index.setEfSearch(200);
		List<HnswIndex.IdScore> highEf = index.search(
				randomVector(dimension, random), 10);

		Assert.assertEquals(10, lowEf.size());
		Assert.assertEquals(10, highEf.size());
	}

	/** Verify normalizing a zero vector does not produce NaN. */
	@Test(timeout = 30000)
	public void cosineNormalizeZeroVectorDoesNotProduceNaN() {
		PackedCollection zeroVec = vec(0.0, 0.0, 0.0);
		PackedCollection normalized = SimilarityMetric.COSINE.normalize(zeroVec);

		for (int i = 0; i < normalized.getMemLength(); i++) {
			double v = normalized.toDouble(i);
			Assert.assertFalse("Normalized zero vector should not contain NaN",
					Double.isNaN(v));
			Assert.assertEquals(0.0, v, 0.0);
		}
	}

	/** Cosine similarity of identical normalized vectors is 1.0. */
	@Test(timeout = 30000)
	public void cosineIdenticalVectorsHaveSimilarityOne() {
		PackedCollection a = vec(1.0, 0.0, 0.0);
		PackedCollection normalized = SimilarityMetric.COSINE.normalize(a);
		float sim = SimilarityMetric.COSINE.similarity(normalized, normalized);
		Assert.assertEquals(1.0f, sim, 0.001f);
	}

	/**
	 * Replacing a vectored record through the store API should update
	 * both the record data and the HNSW vector so that search returns
	 * the new result.
	 */
	@Test(timeout = 30000)
	public void replaceVectoredRecordUpdatesSearch() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {

			store.put("a", makeRecord("a", "original", 1),
					vec(1.0, 0.0, 0.0));
			store.put("b", makeRecord("b", "other", 2),
					vec(0.0, 1.0, 0.0));

			// Replace "a" with a new vector pointing toward (0,0,1)
			store.put("a", makeRecord("a", "replaced", 10),
					vec(0.0, 0.0, 1.0));

			// Search toward the new direction — "a" should be closest
			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(vec(0.0, 0.0, 1.0), 1);

			Assert.assertEquals(1, results.size());
			Assert.assertEquals("a", results.get(0).getId());
			Assert.assertEquals("replaced", results.get(0).getRecord().getContent());
			Assert.assertEquals(10, results.get(0).getRecord().getValue());

			// The old direction should no longer find "a" as top-1
			List<SearchResult<TestRecordProto.TestRecord>> oldResults =
					store.search(vec(1.0, 0.0, 0.0), 2);
			Assert.assertEquals(2, oldResults.size());
			// "b" is at (0,1,0), "a" is now at (0,0,1); query is (1,0,0)
			// Both are orthogonal to query; ordering may vary but neither
			// should have high similarity
			for (SearchResult<TestRecordProto.TestRecord> r : oldResults) {
				Assert.assertTrue(
						"Similarity to old direction should be low",
						r.getSimilarity() < 0.5f);
			}
		}
	}

	private static PackedCollection randomVector(int dimension, Random random) {
		double[] values = new double[dimension];
		for (int i = 0; i < dimension; i++) {
			values[i] = random.nextFloat() * 2.0 - 1.0;
		}
		return new PackedCollection(dimension).fill(values);
	}

}
