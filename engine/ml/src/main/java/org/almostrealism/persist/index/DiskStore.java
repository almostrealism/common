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

package org.almostrealism.persist.index;

import org.almostrealism.collect.PackedCollection;

import java.io.Closeable;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A general-purpose key-value store backed by protobuf files on disk.
 * Records are cached in memory via {@link io.almostrealism.util.FrequencyCache}
 * and automatically swapped to/from disk as needed.
 *
 * <p>Total dataset size may reach 10 GB while at most 500 MB of data
 * is held in memory at any time.</p>
 *
 * @param <T> the record type
 */
public interface DiskStore<T> extends Closeable {

	/**
	 * Store a record. If a record with the same ID already exists,
	 * it is replaced.
	 *
	 * @param id     unique identifier for the record
	 * @param record the record to store
	 */
	void put(String id, T record);

	/**
	 * Retrieve a record by ID. If the record is not in the in-memory
	 * cache, it is loaded from disk automatically.
	 *
	 * @param id the record identifier
	 * @return the record, or {@code null} if not found
	 */
	T get(String id);

	/**
	 * Delete a record by ID. Removes it from the index so that it
	 * is no longer returned by {@link #get}, {@link #scan}, or
	 * {@link #pairwiseScan}. The underlying bytes remain in the
	 * batch file until compaction (not yet implemented).
	 *
	 * @param id the record identifier
	 */
	void delete(String id);

	/**
	 * Iterate over every record in the store, invoking the visitor
	 * for each one. Records are loaded batch-by-batch from disk.
	 *
	 * @param visitor the callback for each record
	 */
	void scan(Consumer<T> visitor);

	/**
	 * Visit every unordered pair of distinct records exactly once.
	 * Uses a block-based algorithm that loads batches in an order
	 * designed to minimize cache thrashing and unnecessary disk I/O.
	 *
	 * @param pairVisitor the callback for each pair
	 */
	void pairwiseScan(BiConsumer<T, T> pairVisitor);

	/**
	 * Store a record along with its embedding vector. Records stored
	 * with a vector are searchable via {@link #search}. The vector is
	 * normalized by the configured {@link SimilarityMetric} at insertion time.
	 *
	 * @param id     unique identifier for the record
	 * @param record the record to store
	 * @param vector the embedding vector as a {@link PackedCollection}
	 */
	void put(String id, T record, PackedCollection vector);

	/**
	 * Search for the top-K most similar records to the given query vector.
	 * Only records that were stored with a vector are included in the results.
	 *
	 * @param queryVector the query embedding vector as a {@link PackedCollection}
	 * @param topK        maximum number of results to return
	 * @return results ordered by descending similarity, or an empty list
	 *         if no vectors are stored
	 */
	List<SearchResult<T>> search(PackedCollection queryVector, int topK);

	/**
	 * Check whether a record with the given ID exists in the store.
	 *
	 * @param id the record identifier
	 * @return {@code true} if the record exists
	 */
	boolean containsKey(String id);

	/**
	 * Return the set of all record IDs in the store.
	 *
	 * @return an unmodifiable set of all record identifiers
	 */
	Set<String> allIds();

	/**
	 * Return the total number of records in the store.
	 *
	 * @return record count
	 */
	int size();

	/**
	 * Close this store, flushing any pending records and releasing
	 * resources. Does not throw checked exceptions — I/O failures
	 * are wrapped in {@link java.io.UncheckedIOException}.
	 */
	@Override
	void close();
}
