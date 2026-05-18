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

package org.almostrealism.studio.persistence;

import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.index.DiskStore;
import org.almostrealism.persist.index.ProtobufDiskStore;
import org.almostrealism.persist.index.SearchResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link WaveDetailsStore} implementation backed by a
 * {@link ProtobufDiskStore} using {@link Audio.WaveDetailData} as
 * the protobuf message type.
 *
 * <p>This class bridges the {@code engine/audio} and {@code engine/ml}
 * modules by converting between {@link WaveDetails} (domain objects)
 * and {@link Audio.WaveDetailData} (protobuf messages). It delegates
 * all persistence, caching, and HNSW indexing to the underlying
 * {@link ProtobufDiskStore}.</p>
 *
 * @see WaveDetailsStore
 * @see ProtobufDiskStore
 * @see AudioLibraryPersistence
 */
public class ProtobufWaveDetailsStore implements WaveDetailsStore {

	/** Default target size per batch file in bytes. */
	public static final int DEFAULT_TARGET_BATCH_SIZE =
			ProtobufDiskStore.DEFAULT_TARGET_BATCH_SIZE;

	/** The underlying disk store that handles batched serialization and index management. */
	private final ProtobufDiskStore<Audio.WaveDetailData> diskStore;

	/**
	 * Creates a store at the given directory with default settings.
	 *
	 * @param rootDir directory for batch files and index
	 */
	public ProtobufWaveDetailsStore(File rootDir) {
		this.diskStore = new ProtobufDiskStore<>(rootDir, Audio.WaveDetailData.parser());
	}

	/**
	 * Creates a store at the given directory with custom memory and batch settings.
	 *
	 * @param rootDir        directory for batch files and index
	 * @param maxMemoryBytes maximum bytes of record data to hold in memory
	 * @param targetBatchSize target byte size per batch file
	 */
	public ProtobufWaveDetailsStore(File rootDir, long maxMemoryBytes, int targetBatchSize) {
		this.diskStore = new ProtobufDiskStore<>(
				rootDir, Audio.WaveDetailData.parser(), maxMemoryBytes, targetBatchSize);
	}

	/**
	 * Returns the underlying {@link ProtobufDiskStore} for direct access
	 * to HNSW search and other store-level operations.
	 *
	 * @return the backing disk store
	 */
	public ProtobufDiskStore<Audio.WaveDetailData> getDiskStore() {
		return diskStore;
	}

	/**
	 * Set a listener that is notified each time a batch is loaded from
	 * disk. Useful for instrumentation in tests.
	 *
	 * @param listener callback receiving the batch ID
	 */
	public void setLoadListener(Consumer<Integer> listener) {
		diskStore.setLoadListener(listener);
	}

	@Override
	public WaveDetails get(String identifier) {
		Audio.WaveDetailData data = diskStore.get(identifier);
		if (data == null) return null;
		return AudioLibraryPersistence.decode(data);
	}

	@Override
	public void put(String identifier, WaveDetails details) {
		Audio.WaveDetailData data = AudioLibraryPersistence.encode(details, false);
		diskStore.put(identifier, data);
	}

	@Override
	public void put(String identifier, WaveDetails details, PackedCollection embeddingVector) {
		Audio.WaveDetailData data = AudioLibraryPersistence.encode(details, false);
		diskStore.put(identifier, data, embeddingVector);
	}

	@Override
	public boolean containsKey(String identifier) {
		return diskStore.containsKey(identifier);
	}

	@Override
	public Set<String> allIdentifiers() {
		return diskStore.allIds();
	}

	@Override
	public int size() {
		return diskStore.size();
	}

	@Override
	public List<NeighborResult> searchNeighbors(PackedCollection queryVector, int topK) {
		List<SearchResult<Audio.WaveDetailData>> results = diskStore.search(queryVector, topK);
		List<NeighborResult> neighbors = new ArrayList<>(results.size());
		for (SearchResult<Audio.WaveDetailData> result : results) {
			neighbors.add(new NeighborResult(result.getId(), result.getSimilarity()));
		}
		return neighbors;
	}

	@Override
	public boolean hasEmbedding(String identifier) {
		return diskStore.hasIndexedEmbedding(identifier);
	}

	@Override
	public void insertEmbedding(String identifier, PackedCollection embeddingVector) {
		diskStore.insertEmbedding(identifier, embeddingVector);
	}

	@Override
	public int indexedEmbeddingCount() {
		return diskStore.indexedEmbeddingCount();
	}

	@Override
	public void flush() {
		diskStore.flush();
	}

	@Override
	public void close() {
		diskStore.close();
	}
}
