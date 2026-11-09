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
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.persist.index.DiskStore;
import org.almostrealism.persist.index.ProtobufDiskStore;
import org.almostrealism.persist.index.SearchResult;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * <p>The validated-model marker is a sidecar file beside the batch files. Every
 * {@code put} clears it before writing, under the same lock that
 * {@link #setValidatedModel(String, Predicate)} holds while it tests completeness
 * and writes the marker, so no record can be stored that the marker vouches for
 * without having been tested.</p>
 *
 * @see WaveDetailsStore
 * @see ProtobufDiskStore
 * @see AudioLibraryPersistence
 */
public class ProtobufWaveDetailsStore implements WaveDetailsStore, ConsoleFeatures {

	/** Default target size per batch file in bytes. */
	public static final int DEFAULT_TARGET_BATCH_SIZE =
			ProtobufDiskStore.DEFAULT_TARGET_BATCH_SIZE;

	/** Name of the sidecar file recording the model the store was last validated against. */
	private static final String VALIDATED_MODEL_FILE_NAME = "complete.model";

	/** The underlying disk store that handles batched serialization and index management. */
	private final ProtobufDiskStore<Audio.WaveDetailData> diskStore;

	/** Directory holding the batch files, index, and the validated-model marker. */
	private final File rootDir;

	/** Guards the validated-model marker against concurrent record writes. */
	private final Object markerLock = new Object();

	/**
	 * Creates a store at the given directory with default settings.
	 *
	 * @param rootDir directory for batch files and index
	 */
	public ProtobufWaveDetailsStore(File rootDir) {
		this.rootDir = rootDir;
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
		this.rootDir = rootDir;
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
		synchronized (markerLock) {
			clearValidatedModel();
			diskStore.put(identifier, data);
		}
	}

	@Override
	public void put(String identifier, WaveDetails details, PackedCollection embeddingVector) {
		// The embedding is pooled from the active model's features
		setEmbeddingModel(details.getActiveModelIdentifier());
		Audio.WaveDetailData data = AudioLibraryPersistence.encode(details, false);
		synchronized (markerLock) {
			clearValidatedModel();
			diskStore.put(identifier, data, embeddingVector);
		}
	}

	@Override
	public void delete(String identifier) {
		diskStore.delete(identifier);
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
	public void setEmbeddingModel(String modelIdentifier) {
		if (diskStore.setIndexModel(modelIdentifier) && modelIdentifier != null) {
			rebuildIndex(modelIdentifier);
		}
	}

	/**
	 * Rebuilds the nearest-neighbor index from the stored records after a model
	 * change has discarded it. Every record that already holds features for the
	 * declared model is re-embedded from those features and re-inserted, so
	 * records complete for the model are searchable immediately rather than only
	 * after a recomputation that will never be triggered for them. Records
	 * without the declared model's features are left out; they are indexed when
	 * their embeddings are computed and stored.
	 *
	 * @param modelIdentifier the model whose embeddings the index now holds
	 */
	private void rebuildIndex(String modelIdentifier) {
		for (String identifier : diskStore.allIds()) {
			WaveDetails details = get(identifier);
			if (details == null || details.getFeatures(modelIdentifier) == null) continue;

			details.setActiveModelIdentifier(modelIdentifier);
			PackedCollection embedding = details.meanPooledEmbedding();
			if (embedding != null) diskStore.insertEmbedding(identifier, embedding);
		}
	}

	@Override
	public int indexedEmbeddingCount() {
		return diskStore.indexedEmbeddingCount();
	}

	@Override
	public String getValidatedModel() {
		Path marker = markerPath();
		if (!Files.exists(marker)) return null;

		try {
			String model = Files.readString(marker).trim();
			return model.isEmpty() ? null : model;
		} catch (IOException e) {
			// Degrade to a rescan rather than aborting the library open that reads this.
			warn("Unable to read validated-model marker at " + marker, e);
			return null;
		}
	}

	@Override
	public void setValidatedModel(String modelIdentifier, Predicate<Set<String>> complete) {
		synchronized (markerLock) {
			if (modelIdentifier == null || !complete.test(allIdentifiers())) {
				clearValidatedModel();
				return;
			}

			// The marker must never vouch for a record that is not yet on disk.
			diskStore.flush();

			Path marker = markerPath();
			Path pending = marker.resolveSibling(VALIDATED_MODEL_FILE_NAME + ".tmp");
			try {
				Files.writeString(pending, modelIdentifier);
				Files.move(pending, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				// A marker that is not written only costs a rescan next launch.
				warn("Unable to persist validated-model marker at " + marker, e);
			}
		}
	}

	/**
	 * Deletes the validated-model marker, if present. Must be called holding
	 * {@link #markerLock}. The file is checked on every call rather than cached,
	 * because the marker is shared by every store opened on the directory.
	 * Failure is thrown rather than logged: a marker that survives would let
	 * the next launch trust records it never vouched for.
	 *
	 * @throws UncheckedIOException if the marker exists and cannot be deleted
	 */
	private void clearValidatedModel() {
		try {
			Files.deleteIfExists(markerPath());
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to clear validated-model marker at " + markerPath(), e);
		}
	}

	/** Returns the path of the validated-model marker file. */
	private Path markerPath() {
		return new File(rootDir, VALIDATED_MODEL_FILE_NAME).toPath();
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
