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
 * <p>{@link #flush()} and {@link #close()} take that same lock, so a periodic
 * save on one thread and record writes on another are serialized at this
 * level as well as inside the {@link ProtobufDiskStore}.</p>
 *
 * <p>The nearest-neighbor index is saved only on {@link #flush()} and
 * {@link #close()}, while records also become durable through the disk
 * store's automatic batch flushes. After an interrupted run, the index on disk
 * can therefore lack records that are stored complete for its model. The first
 * time a model is declared through {@link #setEmbeddingModel(String)} after the
 * store is opened, every stored record that holds that model's features but
 * has no indexed embedding is embedded and indexed, which closes that gap.</p>
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

	/**
	 * Serializes every change to the stored records, the nearest-neighbor
	 * index's model and the validated-model marker: record writes and
	 * deletions, a model declaration together with the backfill it triggers,
	 * the marker update, and flush and close.
	 */
	private final Object writeLock = new Object();

	/**
	 * The model for which every stored record holding its features has been
	 * confirmed to be in the nearest-neighbor index since this store was
	 * opened, or {@code null} if no model has been reconciled yet.
	 */
	private volatile String reconciledModel;

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
		synchronized (writeLock) {
			clearValidatedModel();
			diskStore.put(identifier, data);
		}
	}

	@Override
	public void put(String identifier, WaveDetails details, PackedCollection embeddingVector) {
		Audio.WaveDetailData data = AudioLibraryPersistence.encode(details, false);
		synchronized (writeLock) {
			// The embedding is pooled from the active model's features
			setEmbeddingModel(details.getActiveModelIdentifier());
			clearValidatedModel();
			diskStore.put(identifier, data, embeddingVector);
		}
	}

	@Override
	public void delete(String identifier) {
		synchronized (writeLock) {
			diskStore.delete(identifier);
		}
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
		// TODO(review): not under writeLock, so it can interleave with setEmbeddingModel's model switch/backfill or a put of the same id
		diskStore.insertEmbedding(identifier, embeddingVector);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The declaration and the backfill it triggers run as one operation
	 * under the same lock as every record write, so a concurrent declaration of
	 * another model cannot interleave its vectors with this one's, and a
	 * concurrent write cannot replace a record between the backfill reading its
	 * features and indexing the embedding pooled from them.</p>
	 */
	@Override
	public void setEmbeddingModel(String modelIdentifier) {
		synchronized (writeLock) {
			boolean reset = diskStore.setIndexModel(modelIdentifier);
			if (modelIdentifier == null) return;

			if (reset || !modelIdentifier.equals(reconciledModel)) {
				indexMissingEmbeddings(modelIdentifier);
				reconciledModel = modelIdentifier;
			}
		}
	}

	/**
	 * Indexes every stored record that holds features for the declared model
	 * but has no embedding in the nearest-neighbor index. This rebuilds the
	 * index after a model change has discarded it (when no record is indexed),
	 * and on the first declaration after opening it adds the records that were
	 * made durable by an automatic batch flush after the index was last saved.
	 * Each such record is re-embedded from its stored features and inserted, so
	 * records complete for the model are searchable immediately rather than only
	 * after a recomputation that will never be triggered for them. Records
	 * without the declared model's features are left out; they are indexed when
	 * their embeddings are computed and stored. Only records missing from the
	 * index are read. Must be called holding {@link #writeLock}.
	 *
	 * @param modelIdentifier the model whose embeddings the index holds
	 */
	private void indexMissingEmbeddings(String modelIdentifier) {
		for (String identifier : diskStore.allIds()) {
			if (diskStore.hasIndexedEmbedding(identifier)) continue;

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
		synchronized (writeLock) {
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
	 * {@link #writeLock}. The file is checked on every call rather than cached,
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
		synchronized (writeLock) {
			diskStore.flush();
		}
	}

	@Override
	public void close() {
		synchronized (writeLock) {
			diskStore.close();
		}
	}
}
