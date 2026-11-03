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

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link ProtobufWaveDetailsStore}, focused on how the
 * nearest-neighbor index tracks the declared embedding model.
 *
 * @see ProtobufWaveDetailsStore
 */
public class ProtobufWaveDetailsStoreTest extends TestSuiteBase {

	/**
	 * Builds a {@link WaveDetails} carrying features for a single model, with the
	 * active model set so {@link WaveDetails#meanPooledEmbedding()} resolves them.
	 * The feature data is two frames of three bins, so the mean-pooled embedding
	 * is three-dimensional.
	 *
	 * @param id    the record identifier
	 * @param model the model the features belong to
	 * @param scale a per-record multiplier distinguishing one record's features
	 * @return the populated details
	 */
	private WaveDetails detailsFor(String id, String model, double scale) {
		WaveDetails details = new WaveDetails(id, 44100);
		details.setFreqData(new PackedCollection(1));
		details.putFeatures(new WaveFeatures(model, 44100.0, 1, 3, 2,
				new PackedCollection(2, 3).fill(
						scale, scale + 1, scale + 2,
						scale + 3, scale + 4, scale + 5)));
		details.setActiveModelIdentifier(model);
		return details;
	}

	/**
	 * Stores a record with the given details and the embedding pooled from its
	 * active model's features, declaring that model to the store first.
	 *
	 * @param store   the store to put into
	 * @param details the record details
	 */
	private void putWithEmbedding(WaveDetailsStore store, WaveDetails details) {
		store.put(details.getIdentifier(), details, details.meanPooledEmbedding());
	}

	/**
	 * A model change discards the index, and declaring a model rebuilds it from
	 * exactly the stored records that already hold that model's features — the
	 * records that would otherwise never re-enter the index because nothing
	 * queues them for recomputation. Records for other models stay out.
	 */
	@Test(timeout = 60000)
	public void modelChangeRebuildsIndexFromStoredFeatures() throws IOException {
		Path tempDir = Files.createTempDirectory("protobuf-wave-store-test");
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(tempDir.toFile());

		try {
			putWithEmbedding(store, detailsFor("a", "m1", 1.0));
			putWithEmbedding(store, detailsFor("b", "m1", 2.0));
			Assert.assertEquals("both m1 records indexed", 2, store.indexedEmbeddingCount());

			// Stored without an embedding: present as a record, absent from the index.
			store.put("c", detailsFor("c", "m2", 3.0));
			Assert.assertEquals("record stored without an embedding is not indexed",
					2, store.indexedEmbeddingCount());

			// Switching to m2 discards the m1 index and rebuilds it from the only
			// record holding m2 features.
			store.setEmbeddingModel("m2");
			Assert.assertEquals("m2 index rebuilt from the one m2 record",
					1, store.indexedEmbeddingCount());
			Assert.assertTrue("the m2 record is indexed", store.hasEmbedding("c"));
			Assert.assertFalse("m1 records stay out of the m2 index", store.hasEmbedding("a"));
			Assert.assertEquals("records are retained", 3, store.size());

			// Switching back rebuilds from the m1 records, even though neither was
			// recomputed — they were complete for m1 the whole time.
			store.setEmbeddingModel("m1");
			Assert.assertEquals("m1 index rebuilt from stored m1 features",
					2, store.indexedEmbeddingCount());
			Assert.assertTrue("rebuilt m1 index holds an m1 record", store.hasEmbedding("a"));
			Assert.assertTrue("rebuilt m1 index holds an m1 record", store.hasEmbedding("b"));
			Assert.assertFalse("the m2 record stays out of the m1 index", store.hasEmbedding("c"));
		} finally {
			store.close();
		}
	}

	/**
	 * Declaring a model for the first time on a store whose records are already
	 * complete for it must index those records, rather than leaving an empty
	 * index because nothing will be recomputed.
	 */
	@Test(timeout = 60000)
	public void firstDeclarationIndexesAlreadyCompleteRecords() throws IOException {
		Path tempDir = Files.createTempDirectory("protobuf-wave-store-first");
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(tempDir.toFile());

		try {
			// Persist records without ever indexing them (no embedding supplied).
			store.put("a", detailsFor("a", "m1", 1.0));
			store.put("b", detailsFor("b", "m1", 2.0));
			Assert.assertEquals("nothing indexed yet", 0, store.indexedEmbeddingCount());

			store.setEmbeddingModel("m1");
			Assert.assertEquals("first declaration backfills complete records",
					2, store.indexedEmbeddingCount());
		} finally {
			store.close();
		}
	}

	/**
	 * The model marker is persisted the moment it is declared, while the index is
	 * only written on flush or close, so a run interrupted after the marker is
	 * written reopens with the model recorded but no index loaded. Re-declaring
	 * that same model must rebuild the index from the stored records rather than
	 * leaving it empty, since nothing else would ever repopulate it.
	 */
	@Test(timeout = 60000)
	public void sameModelRebuildsIndexWhenIndexMissing() throws IOException {
		Path tempDir = Files.createTempDirectory("protobuf-wave-store-missing");
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(tempDir.toFile());

		try {
			putWithEmbedding(store, detailsFor("a", "m1", 1.0));
			putWithEmbedding(store, detailsFor("b", "m1", 2.0));
			Assert.assertEquals("both m1 records indexed", 2, store.indexedEmbeddingCount());
		} finally {
			store.close();
		}

		// Simulate an interrupted flush: the model marker survives, the index does not.
		Assert.assertTrue("index file removed", new File(tempDir.toFile(), "hnsw.bin").delete());

		ProtobufWaveDetailsStore reopened = new ProtobufWaveDetailsStore(tempDir.toFile());
		try {
			Assert.assertEquals("index did not load", 0, reopened.indexedEmbeddingCount());

			reopened.setEmbeddingModel("m1");
			Assert.assertEquals("same model rebuilds the index from stored features",
					2, reopened.indexedEmbeddingCount());
			Assert.assertTrue("rebuilt index holds an m1 record", reopened.hasEmbedding("a"));
			Assert.assertTrue("rebuilt index holds an m1 record", reopened.hasEmbedding("b"));
		} finally {
			reopened.close();
		}
	}
}
