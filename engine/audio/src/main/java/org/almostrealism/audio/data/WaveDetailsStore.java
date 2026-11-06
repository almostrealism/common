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

package org.almostrealism.audio.data;

import org.almostrealism.collect.PackedCollection;

import java.io.Closeable;
import java.util.List;
import java.util.Set;

/**
 * Abstraction for persisting and retrieving {@link WaveDetails} records,
 * optionally supporting vector-based nearest neighbor search.
 *
 * <p>This interface decouples {@link org.almostrealism.audio.AudioLibrary}
 * from any specific persistence implementation. Implementations may use
 * protobuf disk stores, in-memory maps, or other backends.</p>
 *
 * <p>When an embedding vector is provided via {@link #put(String, WaveDetails, PackedCollection)},
 * the implementation may index it for approximate nearest neighbor search
 * accessible through {@link #searchNeighbors(PackedCollection, int)}.</p>
 *
 * @see WaveDetails
 * @see org.almostrealism.audio.AudioLibrary
 */
public interface WaveDetailsStore extends Closeable {

	/**
	 * Retrieve a {@link WaveDetails} by its content identifier.
	 * Implementations should load from disk if not in memory.
	 *
	 * @param identifier the content identifier (MD5 hash)
	 * @return the details, or {@code null} if not found
	 */
	WaveDetails get(String identifier);

	/**
	 * Store a {@link WaveDetails} record without an embedding vector.
	 *
	 * @param identifier the content identifier
	 * @param details    the details to store
	 */
	void put(String identifier, WaveDetails details);

	/**
	 * Store a {@link WaveDetails} record with an embedding vector for
	 * nearest neighbor indexing.
	 *
	 * @param identifier      the content identifier
	 * @param details         the details to store
	 * @param embeddingVector the embedding vector for similarity search
	 */
	void put(String identifier, WaveDetails details, PackedCollection embeddingVector);

	/**
	 * Remove the record (and any indexed embedding) for the given identifier.
	 * Implementations should treat removal of an absent identifier as a no-op.
	 *
	 * @param identifier the content identifier to remove
	 */
	void delete(String identifier);

	/**
	 * Check whether a record with the given identifier exists.
	 *
	 * @param identifier the content identifier
	 * @return {@code true} if the record exists
	 */
	boolean containsKey(String identifier);

	/**
	 * Return all stored identifiers.
	 *
	 * @return an unmodifiable set of all identifiers in the store
	 */
	Set<String> allIdentifiers();

	/**
	 * Return the number of stored records.
	 *
	 * @return record count
	 */
	int size();

	/**
	 * Search for the nearest neighbors of the given query vector.
	 *
	 * @param queryVector the query embedding vector
	 * @param topK        maximum number of results
	 * @return results ordered by descending similarity, or empty if
	 *         no vectors are indexed
	 */
	List<NeighborResult> searchNeighbors(PackedCollection queryVector, int topK);

	/**
	 * Check whether the nearest-neighbor index has an embedding for the
	 * given identifier. Implementations that do not maintain a vector
	 * index should return {@code false}.
	 *
	 * @param identifier the content identifier
	 * @return {@code true} if a vector entry is indexed for this identifier
	 */
	boolean hasEmbedding(String identifier);

	/**
	 * Insert (or replace) a vector entry in the nearest-neighbor index for
	 * the given identifier without rewriting the underlying record. This
	 * is intended for backfill scenarios where the record is already
	 * persisted with valid feature data but the corresponding vector is
	 * missing from the index. Implementations that do not maintain a
	 * vector index may treat this as a no-op.
	 *
	 * @param identifier      the content identifier
	 * @param embeddingVector the embedding vector to index
	 */
	void insertEmbedding(String identifier, PackedCollection embeddingVector);

	/**
	 * Declare the model whose embeddings the nearest-neighbor index holds.
	 * Embeddings from different models cannot share an index, so when the
	 * declared model differs from the one the index was built from, the index
	 * is discarded and rebuilt as embeddings for the declared model arrive.
	 * Declaring the same model again is normally a no-op, but it may still
	 * rebuild the index when the model was recorded yet its index is missing
	 * (for example after a run was interrupted between recording the model and
	 * writing the index), so callers should not rely on an unconditional no-op.
	 * Declaring {@code null} is always a no-op. Implementations that do not
	 * maintain a vector index may ignore this; the default implementation does
	 * nothing.
	 *
	 * @param modelIdentifier the model producing the embeddings, or {@code null}
	 */
	default void setEmbeddingModel(String modelIdentifier) {
	}

	/**
	 * Return the number of indexed vectors in the nearest-neighbor index.
	 * Implementations that do not maintain a vector index should return
	 * {@code 0}.
	 *
	 * @return the number of indexed embeddings
	 */
	int indexedEmbeddingCount();

	/**
	 * Return the feature model the store's contents were last fully validated
	 * against, as persisted by {@link #setValidatedModel(String)}, or
	 * {@code null} if none was recorded. A library seeds its completeness
	 * bookkeeping from this so that reopening a store under an unchanged model
	 * does not have to read every record to confirm it is complete. An index
	 * written before the model was recorded returns {@code null} here, which
	 * forces that confirming scan. Implementations that do not persist this
	 * marker return {@code null}. Because the marker is only an optimization
	 * hint, an implementation that cannot read it degrades to {@code null}
	 * (forcing the scan) rather than throwing, so a library can open over it.
	 *
	 * @return the last-validated feature model, or {@code null}
	 */
	default String getValidatedModel() {
		return null;
	}

	/**
	 * Record the feature model the store's contents have been fully validated
	 * against, so a later reopen can skip the per-record completeness scan when
	 * the model is unchanged. Callers record this only once a refresh has
	 * finished recomputing every record that lacked the model's features, so an
	 * interrupted run leaves the previous marker in place and the next launch
	 * rescans. Passing {@code null} clears the marker. Implementations that do
	 * not persist this marker may ignore it; the default does nothing. Failing
	 * to persist the marker costs only a rescan on the next launch, so an
	 * implementation degrades rather than throwing into the refresh that records it.
	 *
	 * @param modelIdentifier the validated feature model, or {@code null} to clear
	 */
	default void setValidatedModel(String modelIdentifier) {
	}

	/**
	 * Flush pending data to disk without closing the store.
	 */
	void flush();

	/**
	 * Close the store, flushing any pending data and releasing resources.
	 */
	@Override
	void close();

	/**
	 * Result of a nearest neighbor search.
	 *
	 * @param identifier the content identifier of the neighbor
	 * @param similarity the similarity score (higher is more similar)
	 */
	record NeighborResult(String identifier, float similarity) {}
}
