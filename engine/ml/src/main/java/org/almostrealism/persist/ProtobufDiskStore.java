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

package org.almostrealism.persist;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.protobuf.Diskstore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * @param <T> the protobuf message type
 */
public class ProtobufDiskStore<T extends Message> implements DiskStore<T> {
	private static final Logger log = Logger.getLogger(ProtobufDiskStore.class.getName());

	/** Default maximum bytes of data to hold in memory. */
	public static final long DEFAULT_MAX_MEMORY_BYTES = 500L * 1024 * 1024;

	/** Default target size per batch file in bytes. */
	public static final int DEFAULT_TARGET_BATCH_SIZE = 4 * 1024 * 1024;

	private final File rootDir;
	private final Parser<T> parser;
	private final long maxMemoryBytes;
	private final int targetBatchSize;

	private final Map<String, RecordPointer> index;
	private final FrequencyCache<Integer, ParsedBatch<T>> batchCache;
	private final AtomicInteger nextBatchId;
	private int pendingBatchId;
	private long pendingBatchBytes;
	private final List<PendingRecord<T>> pendingRecords;
	private boolean closed;

	private Consumer<Integer> loadListener;

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

		this.pendingBatchId = this.nextBatchId.getAndIncrement();
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
	public void put(String id, T record) {
		RecordPointer existing = index.get(id);
		if (existing != null) {
			if (existing.batchId == pendingBatchId && existing.byteOffset == -1) {
				for (int i = 0; i < pendingRecords.size(); i++) {
					if (pendingRecords.get(i).id.equals(id)) {
						PendingRecord<T> old = pendingRecords.remove(i);
						pendingBatchBytes -= old.record.getSerializedSize()
								+ computeVarintSize(old.record.getSerializedSize());
						break;
					}
				}
			}
			index.remove(id);
		}

		pendingRecords.add(new PendingRecord<>(id, record));
		pendingBatchBytes += record.getSerializedSize() + computeVarintSize(record.getSerializedSize());
		index.put(id, new RecordPointer(pendingBatchId, -1));

		if (pendingBatchBytes >= targetBatchSize) {
			flushPendingBatch();
		}
	}

	@Override
	public T get(String id) {
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
	public void delete(String id) {
		RecordPointer pointer = index.remove(id);
		if (pointer == null) {
			return;
		}

		if (pointer.batchId == pendingBatchId && pointer.byteOffset == -1) {
			for (int i = 0; i < pendingRecords.size(); i++) {
				if (pendingRecords.get(i).id.equals(id)) {
					PendingRecord<T> old = pendingRecords.remove(i);
					pendingBatchBytes -= old.record.getSerializedSize()
							+ computeVarintSize(old.record.getSerializedSize());
					break;
				}
			}
		}
	}

	@Override
	public void scan(Consumer<T> visitor) {
		flushPendingBatch();

		for (int batchId : listBatchIds()) {
			ParsedBatch<T> batch = loadBatch(batchId);
			if (batch != null) {
				for (Map.Entry<String, T> entry : batch.records.entrySet()) {
					RecordPointer ptr = index.get(entry.getKey());
					if (ptr != null && ptr.batchId == batchId) {
						visitor.accept(entry.getValue());
					}
				}
			}
		}
	}

	@Override
	public void pairwiseScan(BiConsumer<T, T> pairVisitor) {
		flushPendingBatch();

		List<Integer> batchIds = listBatchIds();
		for (int i = 0; i < batchIds.size(); i++) {
			ParsedBatch<T> batchA = loadBatch(batchIds.get(i));
			if (batchA == null) continue;

			List<T> recordsA = liveRecords(batchA);

			for (int a = 0; a < recordsA.size() - 1; a++) {
				for (int b = a + 1; b < recordsA.size(); b++) {
					pairVisitor.accept(recordsA.get(a), recordsA.get(b));
				}
			}

			for (int j = i + 1; j < batchIds.size(); j++) {
				ParsedBatch<T> batchB = loadBatch(batchIds.get(j));
				if (batchB == null) continue;

				List<T> recordsB = liveRecords(batchB);
				for (T rA : recordsA) {
					for (T rB : recordsB) {
						pairVisitor.accept(rA, rB);
					}
				}
			}
		}
	}

	@Override
	public int size() {
		return index.size();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;

		if (!pendingRecords.isEmpty()) {
			flushPendingBatch();
		} else {
			saveIndex();
		}

		List<Integer> cachedIds = new ArrayList<>();
		batchCache.forEach((k, v) -> cachedIds.add(k));
		for (Integer id : cachedIds) {
			batchCache.evict(id);
		}
		index.clear();
	}

	/**
	 * Flush any pending records to disk and save the index.
	 */
	public void flush() {
		flushPendingBatch();
		saveIndex();
	}

	/**
	 * Return the number of batches currently held in the in-memory cache.
	 *
	 * @return cached batch count
	 */
	public int getCachedBatchCount() {
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

	private List<T> liveRecords(ParsedBatch<T> batch) {
		List<T> result = new ArrayList<>();
		for (Map.Entry<String, T> entry : batch.records.entrySet()) {
			RecordPointer ptr = index.get(entry.getKey());
			if (ptr != null && ptr.batchId == batch.batchId) {
				result.add(entry.getValue());
			}
		}
		return result;
	}

	private void flushPendingBatch() {
		if (pendingRecords.isEmpty()) {
			return;
		}

		Map<String, T> parsedRecords = new HashMap<>();
		File file = batchFile(pendingBatchId);

		try (FileOutputStream fos = new FileOutputStream(file)) {
			long offset = 0;
			for (PendingRecord<T> pending : pendingRecords) {
				index.put(pending.id, new RecordPointer(pendingBatchId, offset));
				pending.record.writeDelimitedTo(fos);
				long written = computeVarintSize(pending.record.getSerializedSize())
						+ pending.record.getSerializedSize();
				offset += written;
				parsedRecords.put(pending.id, pending.record);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to write batch file " + file, e);
		}

		ParsedBatch<T> parsed = new ParsedBatch<>(pendingBatchId, parsedRecords);
		batchCache.put(pendingBatchId, parsed);

		pendingRecords.clear();
		pendingBatchBytes = 0;
		pendingBatchId = nextBatchId.getAndIncrement();

		saveIndex();
	}

	private ParsedBatch<T> loadBatch(int batchId) {
		ParsedBatch<T> cached = batchCache.get(batchId);
		if (cached != null) {
			return cached;
		}

		File file = batchFile(batchId);
		if (!file.exists()) {
			return null;
		}

		if (loadListener != null) {
			loadListener.accept(batchId);
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

	private File batchFile(int batchId) {
		return new File(rootDir, String.format("batch_%04d.bin", batchId));
	}

	private File indexFile() {
		return new File(rootDir, "index.bin");
	}

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

		File file = indexFile();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			builder.build().writeTo(fos);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to save index", e);
		}
	}

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
		final int batchId;
		final long byteOffset;

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
		final String id;
		final T record;

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
		final int batchId;
		final Map<String, T> records;

		ParsedBatch(int batchId, Map<String, T> records) {
			this.batchId = batchId;
			this.records = records;
		}
	}
}
