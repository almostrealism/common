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

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.protobuf.Diskstore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A general-purpose key-value store that persists native protobuf messages
 * on disk as length-delimited batch files, with automatic in-memory caching
 * via {@link FrequencyCache}.
 *
 * <p>Records of type {@code T} are written directly using
 * {@link Message#writeDelimitedTo} and read back using
 * {@link Parser#parseDelimitedFrom} — no wrapper messages, no double
 * serialization. Batch files contain sequential length-delimited records
 * of the user's own message type.</p>
 *
 * <p>A separate protobuf index file maps each record ID to its batch file
 * and byte offset within that file.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The store may be used from several threads, for example one that adds
 * records while another periodically flushes. Every operation on the records
 * (the pending batch, the record index and the batch cache) holds this
 * store's monitor. The vector index is guarded by a separate lock, which is
 * only ever acquired after the store's monitor and never before it, so
 * vector insertion and the vector-index save do not block record access.
 * {@link #scan} and {@link #pairwiseScan} hold the monitor only while they
 * load each batch, not while the visitor runs.</p>
 *
 * <h2>Crash safety</h2>
 * <p>A record is durable once a flush (automatic, {@link #flush()} or
 * {@link #close()}) has written its batch file and then committed the record
 * index. The batch file is written and synced before the index refers to it,
 * and the index is written to a temporary file, synced, and moved over
 * {@code index.bin} atomically, so an interruption at any point leaves the
 * previously committed index intact. A batch file the committed index does
 * not refer to (one left partial by an interrupted flush) is never read, and
 * batch numbers are never reused for a file that already exists. Records
 * still pending when the process ends are lost. The vector index is saved
 * only on {@link #flush()} and {@link #close()}, so it may omit vectors for
 * records that an automatic flush made durable; a caller that can recompute
 * them restores them with {@link #insertEmbedding}, guided by
 * {@link #hasIndexedEmbedding}.</p>
 *
 * @param <T> the protobuf message type
 */
public class ProtobufDiskStore<T extends Message> implements DiskStore<T> {
	/** Logger for this class. */
	private static final Logger log = Logger.getLogger(ProtobufDiskStore.class.getName());

	/** Default maximum bytes of data to hold in memory. */
	public static final long DEFAULT_MAX_MEMORY_BYTES = 500L * 1024 * 1024;

	/** Default target size per batch file in bytes. */
	public static final int DEFAULT_TARGET_BATCH_SIZE = 4 * 1024 * 1024;

	/** Root directory where batch files and the index file are stored. */
	private final File rootDir;

	/** Protobuf parser used to deserialize records from batch files. */
	private final Parser<T> parser;

	/** Maximum bytes of deserialized record data to keep in the in-memory batch cache. */
	private final long maxMemoryBytes;

	/** Target byte size for each batch file before a new batch is started. */
	private final int targetBatchSize;

	/** Filename of the persisted HNSW vector index within the root directory. */
	private static final String HNSW_FILE_NAME = "hnsw.bin";

	/** File beside the HNSW index recording the model whose vectors it holds. */
	private static final String HNSW_MODEL_FILE_NAME = "hnsw.model";

	/** Maps each record ID to its location (batch ID + byte offset) on disk. */
	private final Map<String, RecordPointer> index;

	/** LRU-like cache of recently accessed deserialized batches, keyed by batch ID. */
	private final FrequencyCache<Integer, ParsedBatch<T>> batchCache;

	/** Monotonically increasing counter used to assign unique batch file IDs. */
	private final AtomicInteger nextBatchId;

	/** Batch ID for records that have been added but not yet flushed to disk. */
	private int pendingBatchId;

	/** Estimated byte size of all pending (unflushed) records. */
	private long pendingBatchBytes;

	/** In-memory buffer of records waiting to be flushed to the current pending batch. */
	private final List<PendingRecord<T>> pendingRecords;

	/** Whether this store has been closed; no further mutations are permitted after closing. */
	private volatile boolean closed;

	/** Optional HNSW vector index for approximate nearest-neighbor search over stored vectors. */
	private HnswIndex hnswIndex;

	/**
	 * Guards every access to {@link #hnswIndex}. The index wraps native memory
	 * that {@link #setIndexModel} and {@link #close} release with
	 * {@link HnswIndex#destroy()}; serializing all reads, writes and the
	 * destruction on this single monitor keeps a model change (which can run on
	 * a different thread than the one inserting or searching) from racing an
	 * in-flight operation into a use-after-destroy or a lost update.
	 */
	private final Object hnswLock = new Object();

	/**
	 * The model whose vectors {@link #hnswIndex} holds, as recorded beside it on
	 * disk, or {@code null} if none is recorded (including an index written
	 * before models were recorded).
	 */
	private String indexModel;

	/** Optional callback invoked each time a batch file is loaded from disk, receiving the batch ID. */
	private volatile Consumer<Integer> loadListener;

	/**
	 * Open or create a disk store at the given directory.
	 *
	 * @param rootDir directory for batch files and index
	 * @param parser  protobuf parser for deserializing records (e.g. {@code MyRecord.parser()})
	 */
	public ProtobufDiskStore(File rootDir, Parser<T> parser) {
		this(rootDir, parser, DEFAULT_MAX_MEMORY_BYTES, DEFAULT_TARGET_BATCH_SIZE);
	}

	/**
	 * Open or create a disk store with custom memory and batch settings.
	 *
	 * @param rootDir         directory for batch files and index
	 * @param parser          protobuf parser for deserializing records
	 * @param maxMemoryBytes  maximum bytes of record data to hold in memory
	 * @param targetBatchSize target byte size per batch file
	 */
	public ProtobufDiskStore(File rootDir, Parser<T> parser,
							 long maxMemoryBytes, int targetBatchSize) {
		this.rootDir = rootDir;
		this.parser = parser;
		this.maxMemoryBytes = maxMemoryBytes;
		this.targetBatchSize = targetBatchSize;
		this.index = new HashMap<>();
		this.pendingRecords = new ArrayList<>();
		this.pendingBatchBytes = 0;

		if (!rootDir.exists() && !rootDir.mkdirs()) {
			throw new UncheckedIOException(
					new IOException("Cannot create store directory: " + rootDir));
		}

		int cacheCapacity = Math.max(2, (int) (maxMemoryBytes / targetBatchSize));
		this.batchCache = new FrequencyCache<>(cacheCapacity, 0.3);

		Diskstore.DiskStoreIndex savedIndex = loadIndexFromDisk();
		if (savedIndex != null) {
			this.nextBatchId = new AtomicInteger(savedIndex.getNextBatchId());
			for (Map.Entry<String, Diskstore.DiskStoreRecordLocation> entry
					: savedIndex.getLocationsMap().entrySet()) {
				this.index.put(entry.getKey(),
						new RecordPointer(entry.getValue().getBatchId(),
								entry.getValue().getByteOffset()));
			}
		} else {
			this.nextBatchId = new AtomicInteger(0);
		}

		// Never assign a batch number whose file already exists: an index that was
		// lost, or that predates a partially written batch, must not lead a new
		// batch to overwrite records that are still on disk.
		List<Integer> existingBatchIds = listBatchIds();
		if (!existingBatchIds.isEmpty()) {
			int lastExisting = existingBatchIds.get(existingBatchIds.size() - 1);
			this.nextBatchId.accumulateAndGet(lastExisting + 1, Math::max);
		}

		this.pendingBatchId = this.nextBatchId.getAndIncrement();

		Path hnswFile = new File(rootDir, HNSW_FILE_NAME).toPath();
		HnswIndex loaded = HnswIndex.load(hnswFile, SimilarityMetric.COSINE);
		if (loaded != null) {
			this.hnswIndex = loaded;
		}

		Path modelFile = new File(rootDir, HNSW_MODEL_FILE_NAME).toPath();
		try {
			this.indexModel = Files.exists(modelFile) ? Files.readString(modelFile).trim() : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Declares the model whose vectors the HNSW index holds. Vectors from
	 * different models cannot share an index: their dimensions usually differ,
	 * and even when they match, similarity between them means nothing. So when
	 * the declared model is not the one recorded beside the index (or none is
	 * recorded), the index is discarded, in memory and on disk, and the next
	 * vector insertion starts a new one. Declaring the recorded model while its
	 * index is present is a no-op.
	 *
	 * <p>The model marker is persisted the moment it is declared, while the index
	 * itself is only written on flush or close, so an interrupted run can reopen
	 * with the marker recorded and no index loaded. A declaration of the recorded
	 * model is therefore reported as a rebuild when no index is present, rather
	 * than left as a no-op that would strand an empty index.</p>
	 *
	 * <p>Because a discarded index is only repopulated as vectors for the declared
	 * model arrive, records already complete for that model would never re-enter
	 * the index on their own. The returned flag lets the caller detect a reset and
	 * backfill those records (this store keeps only opaque records and cannot
	 * re-embed them itself).</p>
	 *
	 * @param model the model identifier, or {@code null} to leave the index as it is
	 * @return {@code true} if the index must be rebuilt for the declared model
	 *         (because it was discarded, or because the recorded model has no index
	 *         loaded), {@code false} if the call was a no-op
	 */
	public boolean setIndexModel(String model) {
		synchronized (hnswLock) {
			if (closed) {
				throw new IllegalStateException("Cannot change the index model of a closed store");
			}

			if (model == null) return false;

			if (model.equals(indexModel)) {
				// The recorded model is unchanged, but the marker outlives the index
				// across an interrupted flush, so signal a rebuild when no index is
				// loaded; there is nothing to discard or rewrite in that case.
				return hnswIndex == null;
			}

			if (hnswIndex != null) {
				log.info("Discarding HNSW index of " + hnswIndex.size() + " vectors from model "
						+ indexModel + "; it will be rebuilt from " + model + " vectors");
				hnswIndex.destroy();
				hnswIndex = null;
			}

			try {
				Files.deleteIfExists(new File(rootDir, HNSW_FILE_NAME).toPath());
				Files.writeString(new File(rootDir, HNSW_MODEL_FILE_NAME).toPath(), model);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			indexModel = model;
			return true;
		}
	}

	/**
	 * Set a listener that is notified each time a batch is loaded from
	 * disk. Useful for instrumentation in tests.
	 *
	 * @param listener callback receiving the batch ID
	 */
	public void setLoadListener(Consumer<Integer> listener) {
		this.loadListener = listener;
	}

	@Override
	public synchronized void put(String id, T record) {
		if (closed) {
			throw new IllegalStateException("Cannot put into a closed store");
		}

		RecordPointer existing = index.get(id);
		if (existing != null) {
			if (existing.batchId == pendingBatchId && existing.byteOffset == -1) {
				removePendingRecord(id);
			}
			index.remove(id);
		}

		pendingRecords.add(new PendingRecord<>(id, record));
		pendingBatchBytes += record.getSerializedSize() + computeVarintSize(record.getSerializedSize());
		index.put(id, new RecordPointer(pendingBatchId, -1));

		synchronized (hnswLock) {
			if (hnswIndex != null && hnswIndex.contains(id)) {
				hnswIndex.remove(id);
			}
		}

		if (pendingBatchBytes >= targetBatchSize) {
			flushPendingBatch();
		}
	}

	@Override
	public void put(String id, T record, PackedCollection vector) {
		put(id, record);
		synchronized (hnswLock) {
			ensureHnswIndex(vector.getMemLength());
			hnswIndex.insert(id, vector);
		}
	}

	/**
	 * Insert (or replace) a vector entry in the HNSW index for the given
	 * identifier without rewriting the underlying record. Intended for
	 * backfill scenarios where the record is already persisted but its
	 * vector is missing from the index.
	 *
	 * @param id     the record identifier
	 * @param vector the embedding vector to index
	 */
	public void insertEmbedding(String id, PackedCollection vector) {
		// TODO(review): unlike put(), this does not check `closed`; since close() now
		// nulls hnswIndex, calling this after close() silently creates a fresh, empty
		// index via ensureHnswIndex() instead of failing, discarding prior data.
		synchronized (hnswLock) {
			ensureHnswIndex(vector.getMemLength());
			if (hnswIndex.contains(id)) {
				hnswIndex.remove(id);
			}
			hnswIndex.insert(id, vector);
		}
	}

	/**
	 * Check whether the HNSW index has a non-deleted entry for the given
	 * identifier.
	 *
	 * @param id the record identifier
	 * @return {@code true} if a vector entry is indexed for this identifier
	 */
	public boolean hasIndexedEmbedding(String id) {
		synchronized (hnswLock) {
			return hnswIndex != null && hnswIndex.contains(id);
		}
	}

	/**
	 * Returns the number of indexed (non-deleted) vectors in the HNSW
	 * index, or {@code 0} if the index has not been initialized.
	 *
	 * @return the number of indexed embeddings
	 */
	public int indexedEmbeddingCount() {
		synchronized (hnswLock) {
			return hnswIndex == null ? 0 : hnswIndex.size();
		}
	}

	@Override
	public List<SearchResult<T>> search(PackedCollection queryVector, int topK) {
		List<HnswIndex.IdScore> candidates;
		synchronized (hnswLock) {
			// A query of another dimensionality comes from another model than the
			// indexed vectors, so nothing in the index is comparable to it (the
			// empty-result-on-width-mismatch contract is documented on DiskStore.search).
			if (hnswIndex == null || hnswIndex.size() == 0
					|| hnswIndex.getDimension() != queryVector.getMemLength()) {
				return new ArrayList<>();
			}

			candidates = hnswIndex.search(queryVector, topK);
		}

		List<SearchResult<T>> results = new ArrayList<>(candidates.size());

		for (HnswIndex.IdScore candidate : candidates) {
			T record = get(candidate.id);
			if (record != null) {
				results.add(new SearchResult<>(candidate.id, record, candidate.score));
			}
		}

		results.sort(Comparator.comparingDouble(
				(SearchResult<T> r) -> r.getSimilarity()).reversed());
		return results;
	}

	@Override
	public synchronized T get(String id) {
		RecordPointer pointer = index.get(id);
		if (pointer == null) {
			return null;
		}

		if (pointer.batchId == pendingBatchId && pointer.byteOffset == -1) {
			for (PendingRecord<T> pending : pendingRecords) {
				if (pending.id.equals(id)) {
					return pending.record;
				}
			}
			return null;
		}

		ParsedBatch<T> batch = loadBatch(pointer.batchId);
		if (batch == null) {
			return null;
		}

		return batch.records.get(id);
	}

	@Override
	public synchronized void delete(String id) {
		RecordPointer pointer = index.remove(id);
		if (pointer == null) {
			return;
		}

		if (pointer.batchId == pendingBatchId && pointer.byteOffset == -1) {
			removePendingRecord(id);
		}

		synchronized (hnswLock) {
			if (hnswIndex != null) {
				hnswIndex.remove(id);
			}
		}
	}

	@Override
	public void scan(Consumer<T> visitor) {
		for (int batchId : flushAndListReferencedBatches()) {
			liveRecords(batchId).forEach(visitor);
		}
	}

	@Override
	public void pairwiseScan(BiConsumer<T, T> pairVisitor) {
		List<Integer> batchIds = flushAndListReferencedBatches();
		for (int i = 0; i < batchIds.size(); i++) {
			List<T> recordsA = liveRecords(batchIds.get(i));

			for (int a = 0; a < recordsA.size() - 1; a++) {
				for (int b = a + 1; b < recordsA.size(); b++) {
					pairVisitor.accept(recordsA.get(a), recordsA.get(b));
				}
			}

			for (int j = i + 1; j < batchIds.size(); j++) {
				List<T> recordsB = liveRecords(batchIds.get(j));
				for (T rA : recordsA) {
					for (T rB : recordsB) {
						pairVisitor.accept(rA, rB);
					}
				}
			}
		}
	}

	@Override
	public synchronized boolean containsKey(String id) {
		return index.containsKey(id);
	}

	@Override
	public synchronized Set<String> allIds() {
		return Collections.unmodifiableSet(new HashSet<>(index.keySet()));
	}

	@Override
	public synchronized int size() {
		return index.size();
	}

	@Override
	public void close() {
		synchronized (this) {
			if (closed) {
				return;
			}

			flushOrSaveIndex();
			closed = true;

			List<Integer> cachedIds = new ArrayList<>();
			batchCache.forEach((k, v) -> cachedIds.add(k));
			for (Integer id : cachedIds) {
				batchCache.evict(id);
			}
			index.clear();
		}

		synchronized (hnswLock) {
			if (hnswIndex != null) {
				saveHnswIndex();
				hnswIndex.destroy();
				hnswIndex = null;
			}
		}
	}

	/**
	 * Flush any pending records to disk and save the index.
	 * If there are pending records, they are written to a new batch
	 * file and the index is saved once. If there are no pending records,
	 * only the index is saved (to persist any deletions since the last flush).
	 *
	 * <p>Has no effect if the store has been {@linkplain #close() closed}.</p>
	 */
	public void flush() {
		synchronized (this) {
			if (closed) {
				return;
			}

			flushOrSaveIndex();
		}

		saveHnswIndex();
	}

	/**
	 * Persist the record side of the store to disk. Flushes pending records
	 * if any exist, otherwise saves just the index. Must be called holding
	 * this store's monitor; the caller saves the HNSW vector index separately.
	 */
	private void flushOrSaveIndex() {
		if (!pendingRecords.isEmpty()) {
			flushPendingBatch();
		} else {
			saveIndex();
		}
	}

	/**
	 * Lazily initialize the HNSW index on first vector insertion.
	 *
	 * @param dimension the vector dimensionality
	 */
	private void ensureHnswIndex(int dimension) {
		if (hnswIndex == null) {
			hnswIndex = new HnswIndex(dimension);
		}
	}

	/**
	 * Save the HNSW index to disk if it exists.
	 */
	private void saveHnswIndex() {
		// TODO(review): hnsw.bin is still rewritten in place; consider the tmp+sync+atomic move used for index.bin
		synchronized (hnswLock) {
			if (hnswIndex != null) {
				hnswIndex.save(new File(rootDir, HNSW_FILE_NAME).toPath());
			}
		}
	}

	/**
	 * Return the number of batches currently held in the in-memory cache.
	 *
	 * @return cached batch count
	 */
	public synchronized int getCachedBatchCount() {
		return batchCache.size();
	}

	/**
	 * Return the configured cache capacity (max number of batches in memory).
	 *
	 * @return cache capacity
	 */
	public int getCacheCapacity() {
		return Math.max(2, (int) (maxMemoryBytes / targetBatchSize));
	}

	/**
	 * Remove a pending record by ID and adjust {@link #pendingBatchBytes}.
	 * No-op if the record is not found among pending records.
	 */
	private void removePendingRecord(String id) {
		for (int i = 0; i < pendingRecords.size(); i++) {
			if (pendingRecords.get(i).id.equals(id)) {
				PendingRecord<T> old = pendingRecords.remove(i);
				pendingBatchBytes -= old.record.getSerializedSize()
						+ computeVarintSize(old.record.getSerializedSize());
				return;
			}
		}
	}

	/**
	 * Return the list of live (non-deleted, non-replaced) records in the
	 * given batch, loading it if necessary. A record is live if its current
	 * index pointer still references this batch.
	 *
	 * @param batchId the batch to read
	 * @return the live records, empty if the batch file does not exist
	 */
	private synchronized List<T> liveRecords(int batchId) {
		List<T> result = new ArrayList<>();
		ParsedBatch<T> batch = loadBatch(batchId);
		if (batch == null) return result;

		for (Map.Entry<String, T> entry : batch.records.entrySet()) {
			RecordPointer ptr = index.get(entry.getKey());
			if (ptr != null && ptr.batchId == batchId) {
				result.add(entry.getValue());
			}
		}
		return result;
	}

	/**
	 * Flush any pending records and return the IDs of the batches the record
	 * index refers to, sorted ascending. Batch files on disk that no record
	 * refers to (fully superseded batches, or a batch left partially written
	 * by an interrupted flush) are not included.
	 *
	 * @return the referenced batch IDs
	 */
	private synchronized List<Integer> flushAndListReferencedBatches() {
		flushPendingBatch();

		Set<Integer> referenced = new HashSet<>();
		for (RecordPointer pointer : index.values()) {
			referenced.add(pointer.batchId);
		}

		List<Integer> batchIds = new ArrayList<>(referenced);
		batchIds.sort(Integer::compareTo);
		return batchIds;
	}

	/**
	 * Write all pending records to a new batch file, update the index
	 * pointers with real byte offsets, cache the parsed batch, and
	 * prepare a new pending batch ID. No-op if there are no pending records.
	 * Must be called holding this store's monitor.
	 *
	 * <p>The batch file is written and synced completely before any index
	 * pointer refers to it. If the write fails, the records stay pending and
	 * readable, and a later flush writes them again.</p>
	 */
	private void flushPendingBatch() {
		if (pendingRecords.isEmpty()) {
			return;
		}

		Map<String, T> parsedRecords = new HashMap<>();
		Map<String, RecordPointer> pointers = new HashMap<>();
		File file = batchFile(pendingBatchId);

		try (FileOutputStream fileOut = new FileOutputStream(file);
			 OutputStream out = decorateOutput(file, fileOut)) {
			long offset = 0;
			for (PendingRecord<T> pending : pendingRecords) {
				pointers.put(pending.id, new RecordPointer(pendingBatchId, offset));
				pending.record.writeDelimitedTo(out);
				long written = computeVarintSize(pending.record.getSerializedSize())
						+ pending.record.getSerializedSize();
				offset += written;
				parsedRecords.put(pending.id, pending.record);
			}

			out.flush();
			fileOut.getFD().sync();
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to write batch file " + file, e);
		}

		index.putAll(pointers);
		ParsedBatch<T> parsed = new ParsedBatch<>(pendingBatchId, parsedRecords);
		batchCache.put(pendingBatchId, parsed);

		pendingRecords.clear();
		pendingBatchBytes = 0;
		pendingBatchId = nextBatchId.getAndIncrement();

		saveIndex();
	}

	/**
	 * Load a batch from the cache or disk. Returns {@code null} if the
	 * batch file does not exist.
	 */
	private ParsedBatch<T> loadBatch(int batchId) {
		ParsedBatch<T> cached = batchCache.get(batchId);
		if (cached != null) {
			return cached;
		}

		File file = batchFile(batchId);
		if (!file.exists()) {
			return null;
		}

		Consumer<Integer> listener = loadListener;
		if (listener != null) {
			listener.accept(batchId);
		}

		Map<String, T> records = new HashMap<>();
		Map<Long, String> offsets = buildOffsetMap(batchId);

		try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
			long position = 0;
			T message = parser.parseDelimitedFrom(is);
			while (message != null) {
				String recordId = offsets.get(position);
				if (recordId != null) {
					records.put(recordId, message);
				}
				long messageSize = computeVarintSize(message.getSerializedSize())
						+ message.getSerializedSize();
				position += messageSize;
				message = parser.parseDelimitedFrom(is);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load batch " + batchId, e);
		}

		ParsedBatch<T> parsed = new ParsedBatch<>(batchId, records);
		batchCache.put(batchId, parsed);
		return parsed;
	}

	/**
	 * Build a map from byte offset to record ID for a given batch,
	 * enabling O(1) lookup of which record starts at each position.
	 */
	private Map<Long, String> buildOffsetMap(int batchId) {
		Map<Long, String> offsets = new HashMap<>();
		for (Map.Entry<String, RecordPointer> entry : index.entrySet()) {
			if (entry.getValue().batchId == batchId) {
				offsets.put(entry.getValue().byteOffset, entry.getKey());
			}
		}
		return offsets;
	}

	/**
	 * Compute the number of bytes needed to encode a varint.
	 */
	private static int computeVarintSize(int value) {
		int size = 0;
		while (true) {
			if ((value & ~0x7F) == 0) {
				return size + 1;
			}
			size++;
			value >>>= 7;
		}
	}

	/** Return the file path for the given batch ID. */
	private File batchFile(int batchId) {
		return new File(rootDir, String.format("batch_%04d.bin", batchId));
	}

	/** Return the file path for the index file. */
	private File indexFile() {
		return new File(rootDir, "index.bin");
	}

	/** Return the path of the temporary file the index is written to before it replaces the index file. */
	private File pendingIndexFile() {
		return new File(rootDir, "index.bin.tmp");
	}

	/** List all batch IDs by scanning the root directory, sorted ascending. */
	private List<Integer> listBatchIds() {
		List<Integer> batchIds = new ArrayList<>();
		File[] files = rootDir.listFiles(
				(dir, name) -> name.startsWith("batch_") && name.endsWith(".bin"));
		if (files != null) {
			for (File file : files) {
				String name = file.getName();
				String numStr = name.substring("batch_".length(),
						name.length() - ".bin".length());
				try {
					batchIds.add(Integer.parseInt(numStr));
				} catch (NumberFormatException ignored) {
					log.warning("Ignoring malformed batch file: " + name);
				}
			}
		}
		batchIds.sort(Integer::compareTo);
		return batchIds;
	}

	/**
	 * Persist the in-memory index to the index file on disk. The index is
	 * written to a temporary file, synced, and then moved over the index file
	 * atomically, so an interruption leaves either the previous index or the
	 * new one, never a truncated file. Must be called holding this store's
	 * monitor.
	 */
	private void saveIndex() {
		Diskstore.DiskStoreIndex.Builder builder =
				Diskstore.DiskStoreIndex.newBuilder()
						.setNextBatchId(nextBatchId.get());

		for (Map.Entry<String, RecordPointer> entry : index.entrySet()) {
			builder.putLocations(entry.getKey(),
					Diskstore.DiskStoreRecordLocation.newBuilder()
							.setBatchId(entry.getValue().batchId)
							.setByteOffset(entry.getValue().byteOffset)
							.build());
		}

		File pending = pendingIndexFile();
		try {
			try (FileOutputStream fileOut = new FileOutputStream(pending);
				 OutputStream out = decorateOutput(pending, fileOut)) {
				builder.build().writeTo(out);
				out.flush();
				fileOut.getFD().sync();
			}

			Files.move(pending.toPath(), indexFile().toPath(),
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to save index", e);
		}
	}

	/**
	 * Returns the stream through which a batch file or the index file is
	 * written. The store writes through the returned stream, flushes it, and
	 * then syncs the file's descriptor to the storage device before anything
	 * refers to the file, so a later write that depends on it (the index
	 * referring to a batch, or the index replacing its predecessor) cannot
	 * reach the device first. By default the file's own stream is returned;
	 * subclasses may wrap it, for example to observe or interrupt a write.
	 *
	 * @param file the file being written
	 * @param out  the stream writing to the file
	 * @return the stream the store writes through
	 */
	protected OutputStream decorateOutput(File file, OutputStream out) {
		return out;
	}

	/** Load the index from disk, returning {@code null} if absent or corrupted. */
	private Diskstore.DiskStoreIndex loadIndexFromDisk() {
		File file = indexFile();
		if (!file.exists()) {
			return null;
		}

		try (FileInputStream fis = new FileInputStream(file)) {
			return Diskstore.DiskStoreIndex.parseFrom(fis);
		} catch (IOException e) {
			log.warning("Failed to load index, starting fresh: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Points to a record's location on disk: which batch file and
	 * the byte offset within that file.
	 */
	static class RecordPointer {
		/** ID of the batch file containing this record. */
		final int batchId;

		/**
		 * Byte offset of this record within the batch file,
		 * or {@code -1} if the record is still in the pending (unflushed) buffer.
		 */
		final long byteOffset;

		/**
		 * Creates a record pointer.
		 *
		 * @param batchId    Batch file identifier
		 * @param byteOffset Byte offset within the batch, or {@code -1} if pending
		 */
		RecordPointer(int batchId, long byteOffset) {
			this.batchId = batchId;
			this.byteOffset = byteOffset;
		}
	}

	/**
	 * A record buffered in memory before being flushed to a batch file.
	 *
	 * @param <T> the protobuf message type
	 */
	static class PendingRecord<T extends Message> {
		/** Record identifier. */
		final String id;

		/** The protobuf message waiting to be flushed. */
		final T record;

		/**
		 * Creates a pending record entry.
		 *
		 * @param id     Record identifier
		 * @param record The protobuf message to persist
		 */
		PendingRecord(String id, T record) {
			this.id = id;
			this.record = record;
		}
	}

	/**
	 * A parsed batch holding deserialized records keyed by ID.
	 *
	 * @param <T> the protobuf message type
	 */
	static class ParsedBatch<T> {
		/** The batch file identifier this parsed batch corresponds to. */
		final int batchId;

		/** All deserialized records from this batch, keyed by record ID. */
		final Map<String, T> records;

		/**
		 * Creates a parsed batch.
		 *
		 * @param batchId Batch file identifier
		 * @param records Deserialized records keyed by ID
		 */
		ParsedBatch(int batchId, Map<String, T> records) {
			this.batchId = batchId;
			this.records = records;
		}
	}
}
