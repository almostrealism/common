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

import com.google.protobuf.ByteString;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.protobuf.Diskstore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
 * A general-purpose key-value store that persists records on disk as
 * protobuf batch files, with automatic in-memory caching via
 * {@link FrequencyCache}.
 *
 * <p>Records are grouped into batch files of configurable size. An
 * in-memory index maps each record ID to its batch ID. The
 * {@link FrequencyCache} holds parsed batches keyed by batch ID,
 * evicting least-valuable batches when the memory budget is exceeded.</p>
 *
 * @param <T> the record type
 */
public class ProtobufDiskStore<T> implements DiskStore<T> {
	private static final Logger log = Logger.getLogger(ProtobufDiskStore.class.getName());

	/** Default maximum bytes of data to hold in memory. */
	public static final long DEFAULT_MAX_MEMORY_BYTES = 500L * 1024 * 1024;

	/** Default target size per batch file in bytes. */
	public static final int DEFAULT_TARGET_BATCH_SIZE = 4 * 1024 * 1024;

	private final File rootDir;
	private final RecordCodec<T> codec;
	private final long maxMemoryBytes;
	private final int targetBatchSize;

	private final Map<String, Integer> index;
	private final FrequencyCache<Integer, ParsedBatch<T>> batchCache;
	private final AtomicInteger nextBatchId;
	private int pendingBatchId;
	private int pendingBatchBytes;
	private final List<Diskstore.DiskStoreRecord> pendingRecords;

	private BiConsumer<Integer, Diskstore.DiskStoreBatch> loadListener;

	/**
	 * Open or create a disk store at the given directory.
	 *
	 * @param rootDir   directory for batch files and index
	 * @param codec     serialization strategy for records
	 */
	public ProtobufDiskStore(File rootDir, RecordCodec<T> codec) {
		this(rootDir, codec, DEFAULT_MAX_MEMORY_BYTES, DEFAULT_TARGET_BATCH_SIZE);
	}

	/**
	 * Open or create a disk store with custom memory and batch settings.
	 *
	 * @param rootDir         directory for batch files and index
	 * @param codec           serialization strategy for records
	 * @param maxMemoryBytes  maximum bytes of record data to hold in memory
	 * @param targetBatchSize target byte size per batch file
	 */
	public ProtobufDiskStore(File rootDir, RecordCodec<T> codec,
							 long maxMemoryBytes, int targetBatchSize) {
		this.rootDir = rootDir;
		this.codec = codec;
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

		Diskstore.DiskStoreIndex savedIndex = loadIndex();
		if (savedIndex != null) {
			this.nextBatchId = new AtomicInteger(savedIndex.getNextBatchId());
			for (Map.Entry<String, Diskstore.DiskStoreRecordLocation> entry
					: savedIndex.getLocationsMap().entrySet()) {
				this.index.put(entry.getKey(), entry.getValue().getBatchId());
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
	 * @param listener callback receiving (batchId, batch)
	 */
	public void setLoadListener(BiConsumer<Integer, Diskstore.DiskStoreBatch> listener) {
		this.loadListener = listener;
	}

	@Override
	public void put(String id, T record) {
		byte[] payload = codec.encode(record);

		Integer existingBatch = index.get(id);
		if (existingBatch != null) {
			removeFromBatch(id, existingBatch);
		}

		Diskstore.DiskStoreRecord proto = Diskstore.DiskStoreRecord.newBuilder()
				.setId(id)
				.setPayload(ByteString.copyFrom(payload))
				.build();

		pendingRecords.add(proto);
		pendingBatchBytes += proto.getSerializedSize();
		index.put(id, pendingBatchId);

		if (pendingBatchBytes >= targetBatchSize) {
			flushPendingBatch();
		}
	}

	@Override
	public T get(String id) {
		Integer batchId = index.get(id);
		if (batchId == null) {
			return null;
		}

		if (batchId == pendingBatchId) {
			for (Diskstore.DiskStoreRecord record : pendingRecords) {
				if (record.getId().equals(id)) {
					return codec.decode(record.getPayload().toByteArray());
				}
			}
			return null;
		}

		ParsedBatch<T> batch = loadBatch(batchId);
		if (batch == null) {
			return null;
		}

		return batch.records.get(id);
	}

	@Override
	public void delete(String id) {
		Integer batchId = index.remove(id);
		if (batchId == null) {
			return;
		}

		if (batchId == pendingBatchId) {
			pendingRecords.removeIf(r -> r.getId().equals(id));
		} else {
			removeFromBatch(id, batchId);
		}
	}

	@Override
	public void scan(Consumer<T> visitor) {
		flushPendingBatch();

		for (int batchId : listBatchIds()) {
			ParsedBatch<T> batch = loadBatch(batchId);
			if (batch != null) {
				for (Map.Entry<String, T> entry : batch.records.entrySet()) {
					if (index.containsKey(entry.getKey())) {
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

			// Intra-batch pairs
			for (int a = 0; a < recordsA.size() - 1; a++) {
				for (int b = a + 1; b < recordsA.size(); b++) {
					pairVisitor.accept(recordsA.get(a), recordsA.get(b));
				}
			}

			// Inter-batch pairs
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
		flushPendingBatch();
		saveIndex();
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
			if (index.containsKey(entry.getKey())) {
				result.add(entry.getValue());
			}
		}
		return result;
	}

	private void flushPendingBatch() {
		if (pendingRecords.isEmpty()) {
			return;
		}

		Diskstore.DiskStoreBatch batch = Diskstore.DiskStoreBatch.newBuilder()
				.setBatchId(pendingBatchId)
				.addAllRecords(pendingRecords)
				.build();

		writeBatchFile(pendingBatchId, batch);

		ParsedBatch<T> parsed = parseBatch(batch);
		batchCache.put(pendingBatchId, parsed);

		pendingRecords.clear();
		pendingBatchBytes = 0;
		pendingBatchId = nextBatchId.getAndIncrement();

		saveIndex();
	}

	private ParsedBatch<T> loadBatch(int batchId) {
		return batchCache.computeIfAbsent(batchId, id -> {
			File file = batchFile(id);
			if (!file.exists()) {
				return null;
			}

			try (FileInputStream fis = new FileInputStream(file)) {
				Diskstore.DiskStoreBatch protoBatch =
						Diskstore.DiskStoreBatch.parseFrom(fis);

				if (loadListener != null) {
					loadListener.accept(id, protoBatch);
				}

				return parseBatch(protoBatch);
			} catch (IOException e) {
				throw new UncheckedIOException(
						"Failed to load batch " + id, e);
			}
		});
	}

	private ParsedBatch<T> parseBatch(Diskstore.DiskStoreBatch protoBatch) {
		Map<String, T> records = new HashMap<>();
		for (Diskstore.DiskStoreRecord record : protoBatch.getRecordsList()) {
			records.put(record.getId(),
					codec.decode(record.getPayload().toByteArray()));
		}
		return new ParsedBatch<>(protoBatch.getBatchId(), records);
	}

	private void removeFromBatch(String id, int batchId) {
		File file = batchFile(batchId);
		if (!file.exists()) {
			return;
		}

		try (FileInputStream fis = new FileInputStream(file)) {
			Diskstore.DiskStoreBatch existing =
					Diskstore.DiskStoreBatch.parseFrom(fis);

			Diskstore.DiskStoreBatch.Builder builder =
					Diskstore.DiskStoreBatch.newBuilder()
							.setBatchId(batchId);

			for (Diskstore.DiskStoreRecord record : existing.getRecordsList()) {
				if (!record.getId().equals(id)) {
					builder.addRecords(record);
				}
			}

			Diskstore.DiskStoreBatch updated = builder.build();
			writeBatchFile(batchId, updated);

			batchCache.evict(batchId);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to update batch " + batchId, e);
		}
	}

	private void writeBatchFile(int batchId, Diskstore.DiskStoreBatch batch) {
		File file = batchFile(batchId);
		try (FileOutputStream fos = new FileOutputStream(file)) {
			batch.writeTo(fos);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to write batch file " + file, e);
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

		for (Map.Entry<String, Integer> entry : index.entrySet()) {
			builder.putLocations(entry.getKey(),
					Diskstore.DiskStoreRecordLocation.newBuilder()
							.setBatchId(entry.getValue())
							.build());
		}

		File file = indexFile();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			builder.build().writeTo(fos);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to save index", e);
		}
	}

	private Diskstore.DiskStoreIndex loadIndex() {
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
	 * A parsed batch holding deserialized records keyed by ID.
	 *
	 * @param <T> the record type
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
