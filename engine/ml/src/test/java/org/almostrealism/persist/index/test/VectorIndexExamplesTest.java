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

package org.almostrealism.persist.index.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.index.HnswIndex;
import org.almostrealism.persist.index.ProtobufDiskStore;
import org.almostrealism.persist.index.SearchResult;
import org.almostrealism.persist.index.SimilarityMetric;
import org.almostrealism.persist.test.TestRecordProto;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.almostrealism.persist.index.test.DiskStoreTestSupport.deleteRecursively;
import static org.almostrealism.persist.index.test.DiskStoreTestSupport.gaussianValues;
import static org.almostrealism.persist.index.test.DiskStoreTestSupport.gaussianVector;
import static org.almostrealism.persist.index.test.DiskStoreTestSupport.makeRecord;
import static org.almostrealism.persist.index.test.DiskStoreTestSupport.vec;

/**
 * Drift guard for the vector-index internals page ({@code docs/internals/vector-index.md}).
 * Every complete code example on that page is taken from a method of this class, and each
 * method asserts the behavioral claim the page makes about its example: a planted nearest
 * neighbor is recalled at top-1 across a capacity growth, a batch of candidates scored against
 * one query through {@link SimilarityMetric#similarities} yields the expected cosine
 * similarities, search results survive a store save/reload unchanged, and a change of index
 * model excludes stored vectors until they are re-embedded.
 *
 * <p>The model-keyed rebuild is exercised at the {@link ProtobufDiskStore} layer — the
 * {@code hnsw.model} marker and the {@link ProtobufDiskStore#setIndexModel} /
 * {@link ProtobufDiskStore#insertEmbedding} primitives it rests on. The re-embedding of
 * stored features done above it by {@code ProtobufWaveDetailsStore} is guarded by that
 * class's own tests in {@code studio/compose}.</p>
 */
public class VectorIndexExamplesTest extends TestSuiteBase {

	/** Dimensionality of the planted-neighbor example; large enough that random vectors are far apart. */
	private static final int DIMENSION = 64;

	/** Number of distractor vectors; more than {@link HnswIndex#INITIAL_CAPACITY}, so the store grows. */
	private static final int DISTRACTORS = 600;

	/** Temporary directory for store files. */
	private File tempDir;

	/**
	 * Creates a temporary directory before each test.
	 *
	 * @throws Exception if directory creation fails
	 */
	@Before
	public void setUp() throws Exception {
		tempDir = Files.createTempDirectory("vector-index-examples").toFile();
	}

	/** Removes the temporary directory after each test. */
	@After
	public void tearDown() {
		deleteRecursively(tempDir);
	}

	/**
	 * Page section "The index as a computation graph": a slightly perturbed copy of the
	 * query, hidden among hundreds of random vectors, is the top-1 result, and remains so
	 * after the contiguous store has grown past its initial capacity (doubling and
	 * recompiling the whole-store score evaluable).
	 */
	@Test(timeout = 120000)
	public void plantedNeighborIsRecalledAtTopOne() {
		Random random = new Random(7);
		HnswIndex index = new HnswIndex(DIMENSION);

		for (int i = 0; i < DISTRACTORS; i++) {
			index.insert("distractor-" + i, gaussianVector(DIMENSION, random));
		}

		double[] query = gaussianValues(DIMENSION, random);
		double[] planted = new double[DIMENSION];
		for (int i = 0; i < DIMENSION; i++) {
			planted[i] = query[i] + 0.05 * random.nextGaussian();
		}
		index.insert("planted", vec(planted));

		List<HnswIndex.IdScore> results =
				index.search(vec(query), 5);

		Assert.assertEquals(DISTRACTORS + 1, index.size());
		Assert.assertEquals(5, results.size());
		Assert.assertEquals("planted", results.get(0).id);
		Assert.assertTrue("The planted neighbor should be nearly parallel to the query",
				results.get(0).score > 0.95f);
		Assert.assertTrue("Random distractors should score well below the planted neighbor",
				results.get(1).score < 0.7f);
		index.destroy();
	}

	/**
	 * Page section "The index as a computation graph": scoring a batch of candidates
	 * against one query through {@link SimilarityMetric#similarities} — for cosine, a
	 * {@code matmul} of L2-normalized rows — evaluated once at the boundary produces the
	 * expected cosine similarity for every candidate. This asserts the numerical result of
	 * the batch-scoring producer; it does not observe how many native dispatches the
	 * evaluation makes.
	 */
	@Test(timeout = 30000)
	public void wholeBatchScoringMatchesCosineSimilarities() {
		SimilarityMetric metric = SimilarityMetric.COSINE;

		PackedCollection candidates = new PackedCollection(3, 2);
		candidates.setFrom(0, metric.normalize(vec(3.0, 4.0)), 0, 2);
		candidates.setFrom(2, metric.normalize(vec(0.0, 2.0)), 0, 2);
		candidates.setFrom(4, metric.normalize(vec(-1.0, 0.0)), 0, 2);
		PackedCollection query = metric.normalize(vec(1.0, 0.0));

		PackedCollection scores = metric.similarities(cp(candidates), cp(query)).evaluate();

		Assert.assertEquals(3, scores.getMemLength());
		Assert.assertEquals(0.6, scores.toDouble(0), 1e-5);
		Assert.assertEquals(0.0, scores.toDouble(1), 1e-5);
		Assert.assertEquals(-1.0, scores.toDouble(2), 1e-5);
	}

	/**
	 * Page section "Storage and persistence" and "Save and load": records stored with
	 * vectors are searchable through {@link ProtobufDiskStore#search}, and the same query
	 * returns the same records in the same order, with the same similarities, after the
	 * store is closed and reopened from {@code hnsw.bin}.
	 */
	@Test(timeout = 60000)
	public void searchResultsSurviveReload() {
		Random random = new Random(11);
		PackedCollection query = gaussianVector(16, random);
		List<SearchResult<TestRecordProto.TestRecord>> before;

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			for (int i = 0; i < 40; i++) {
				store.put("rec-" + i, makeRecord("rec-" + i, "content-" + i, i),
						gaussianVector(16, random));
			}
			store.put("plain", makeRecord("plain", "no-vector", -1));

			before = store.search(query, 5);
		}

		Assert.assertTrue(new File(tempDir, "index.bin").exists());
		Assert.assertTrue(new File(tempDir, "hnsw.bin").exists());
		Assert.assertTrue(new File(tempDir, "batch_0000.bin").exists());

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			List<SearchResult<TestRecordProto.TestRecord>> after = reopened.search(query, 5);

			Assert.assertEquals(5, before.size());
			Assert.assertEquals(before.size(), after.size());
			for (int i = 0; i < before.size(); i++) {
				Assert.assertEquals(before.get(i).getId(), after.get(i).getId());
				Assert.assertEquals(before.get(i).getRecord(), after.get(i).getRecord());
				Assert.assertEquals(before.get(i).getSimilarity(),
						after.get(i).getSimilarity(), 1e-5f);
			}
			Assert.assertEquals(41, reopened.size());
			Assert.assertEquals(40, reopened.indexedEmbeddingCount());
		}
	}

	/**
	 * Page section "Save and load": {@link HnswIndex#save} and
	 * {@link HnswIndex#load} round-trip the vectors, the soft-deleted state and the
	 * search results of a standalone index.
	 */
	@Test(timeout = 60000)
	public void standaloneIndexRoundTrips() {
		Random random = new Random(3);
		Path file = tempDir.toPath().resolve("standalone-hnsw.bin");

		HnswIndex index = new HnswIndex(32);
		for (int i = 0; i < 50; i++) {
			index.insert("v-" + i, gaussianVector(32, random));
		}
		index.remove("v-7");
		index.save(file);

		HnswIndex loaded = HnswIndex.load(file, SimilarityMetric.COSINE);
		PackedCollection query = gaussianVector(32, random);
		List<HnswIndex.IdScore> original = index.search(query, 10);
		List<HnswIndex.IdScore> restored = loaded.search(query, 10);

		Assert.assertEquals(49, loaded.size());
		Assert.assertEquals(50, loaded.totalSize());
		Assert.assertFalse(loaded.contains("v-7"));
		Assert.assertEquals(original.size(), restored.size());
		for (int i = 0; i < original.size(); i++) {
			Assert.assertEquals(original.get(i).id, restored.get(i).id);
			Assert.assertEquals(original.get(i).score, restored.get(i).score, 1e-5f);
		}

		index.destroy();
		loaded.destroy();
	}

	/**
	 * Page section "Model-keyed rebuild": declaring the model already recorded is a
	 * no-op; declaring a different model discards the index (the stored records remain
	 * readable but none is searchable) and reports that a rebuild is needed; re-inserting
	 * vectors for the new model with {@link ProtobufDiskStore#insertEmbedding} brings
	 * those records back into search, while records not re-embedded stay excluded. The
	 * {@code hnsw.model} marker records the owning model across a reopen.
	 */
	@Test(timeout = 60000)
	public void modelChangeExcludesUntilReembedded() throws Exception {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertTrue(store.setIndexModel("model-a"));
			store.put("x", makeRecord("x", "first", 1), vec(1.0, 0.0, 0.0));
			store.put("y", makeRecord("y", "second", 2), vec(0.0, 1.0, 0.0));

			Assert.assertFalse("Declaring the recorded model is a no-op",
					store.setIndexModel("model-a"));
			Assert.assertEquals(2, store.indexedEmbeddingCount());

			Assert.assertTrue("A different model discards the index",
					store.setIndexModel("model-b"));
			Assert.assertEquals(0, store.indexedEmbeddingCount());
			Assert.assertTrue(store.search(vec(1.0, 0.0, 0.0), 10).isEmpty());
			Assert.assertEquals("first", store.get("x").getContent());

			store.insertEmbedding("x", vec(0.0, 0.0, 0.0, 1.0));

			List<SearchResult<TestRecordProto.TestRecord>> results =
					store.search(vec(0.0, 0.0, 0.0, 1.0), 10);
			Assert.assertEquals(1, results.size());
			Assert.assertEquals("x", results.get(0).getId());
			Assert.assertFalse("A record not re-embedded stays excluded",
					store.hasIndexedEmbedding("y"));
		}

		Assert.assertEquals("model-b",
				Files.readString(new File(tempDir, "hnsw.model").toPath()).trim());

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertFalse("The reopened index still belongs to the recorded model",
					reopened.setIndexModel("model-b"));
			Assert.assertTrue(reopened.hasIndexedEmbedding("x"));
			Assert.assertFalse(reopened.hasIndexedEmbedding("y"));
		}
	}

	/**
	 * Page section "Model-keyed rebuild": the model marker is written the moment a model
	 * is declared, while the index is written only on flush or close. A store whose marker
	 * outlived its index reports a rebuild when the same model is declared again, rather
	 * than treating the empty index as current.
	 */
	@Test(timeout = 30000)
	public void markerWithoutIndexReportsRebuild() {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertTrue(store.setIndexModel("model-a"));
			store.put("x", makeRecord("x", "unindexed", 1));
		}

		Assert.assertTrue(new File(tempDir, "hnsw.model").exists());
		Assert.assertFalse(new File(tempDir, "hnsw.bin").exists());

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened =
					 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
			Assert.assertTrue("Same model with no index loaded must report a rebuild",
					reopened.setIndexModel("model-a"));
		}
	}
}
