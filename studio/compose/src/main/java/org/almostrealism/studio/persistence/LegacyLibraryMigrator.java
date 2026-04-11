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

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsJob;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Streams legacy {@link AudioLibraryPersistence} batch files into a
 * store-backed {@link AudioLibrary} via the existing
 * {@link WaveDetailsJob} infrastructure.
 *
 * <p>Each legacy record is decoded one at a time, submitted as a
 * migration job (which computes its embedding and writes it to the
 * store), and then released from memory. A configurable
 * {@code maxInFlight} cap prevents the heap from growing unboundedly
 * during migration.</p>
 *
 * <p>Migration is idempotent: records already present in the store
 * are skipped. An interrupted migration resumes from where it left
 * off on the next launch.</p>
 *
 * @see AudioLibrary#submitMigrationJob(WaveDetails, double)
 * @see AudioLibraryPersistence
 */
public class LegacyLibraryMigrator implements ConsoleFeatures {

	/** Default maximum number of migration jobs in flight. */
	public static final int DEFAULT_MAX_IN_FLIGHT = 512;

	/** The target library to migrate records into. */
	private final AudioLibrary library;
	/** The legacy batch-file destination to read from. */
	private final LibraryDestination legacyDestination;
	/** The new store to check for already-migrated records. */
	private final ProtobufWaveDetailsStore store;
	/** Maximum number of migration jobs in flight at once. */
	private final int maxInFlight;

	/**
	 * Creates a migrator.
	 *
	 * @param library            the target library (must be store-backed)
	 * @param legacyDestination  the legacy batch-file destination
	 * @param store              the new store to check for existing records
	 * @param maxInFlight        maximum migration jobs in flight at once
	 */
	public LegacyLibraryMigrator(AudioLibrary library,
								 LibraryDestination legacyDestination,
								 ProtobufWaveDetailsStore store,
								 int maxInFlight) {
		this.library = library;
		this.legacyDestination = legacyDestination;
		this.store = store;
		this.maxInFlight = maxInFlight;
	}

	/**
	 * Returns true if the legacy destination has batch files to migrate.
	 *
	 * @param destination the legacy library destination
	 * @return true if {@code PREFIX_0.bin} exists
	 */
	public static boolean hasLegacyData(LibraryDestination destination) {
		return destination.fileIterator().hasNext();
	}

	/**
	 * Returns the list of legacy batch file paths.
	 *
	 * @param destination the legacy library destination
	 * @return list of existing batch file paths
	 */
	public static List<String> legacyFiles(LibraryDestination destination) {
		List<String> files = new ArrayList<>();
		destination.fileIterator().forEachRemaining(files::add);
		return files;
	}

	/**
	 * Returns the total size in bytes of legacy batch files.
	 *
	 * @param files list of file paths
	 * @return total bytes
	 */
	public static long totalBytes(List<String> files) {
		long total = 0;
		for (String path : files) {
			File f = new File(path);
			if (f.exists()) total += f.length();
		}
		return total;
	}

	/**
	 * Starts migration on a background thread and returns a future
	 * that completes with the result.
	 *
	 * @return a future carrying the migration result
	 */
	public CompletableFuture<Result> migrateAsync() {
		CompletableFuture<Result> future = new CompletableFuture<>();

		Thread producer = new Thread(() -> {
			try {
				Result result = migrate();
				future.complete(result);
			} catch (Exception e) {
				warn("Legacy migration failed", e);
				future.completeExceptionally(e);
			}
		}, "legacy-library-migrator");
		producer.setDaemon(true);
		producer.start();

		return future;
	}

	/**
	 * Performs the migration synchronously. Called on the producer thread.
	 */
	private Result migrate() {
		AtomicInteger inFlight = new AtomicInteger(0);
		int migrated = 0;
		int skipped = 0;
		int failed = 0;

		List<String> files = legacyFiles(legacyDestination);
		long totalBytes = totalBytes(files);

		Supplier<InputStream> inputSupplier = legacyDestination.in();
		InputStream input = inputSupplier.get();

		while (input != null) {
			try {
				Audio.AudioLibraryData data = Audio.AudioLibraryData
						.newBuilder().mergeFrom(input).build();

				for (Audio.WaveDetailData detailData : data.getInfoMap().values()) {
					WaveDetails details;
					try {
						details = AudioLibraryPersistence.decode(detailData, false);
					} catch (Exception e) {
						failed++;
						continue;
					}

					String identifier = details.getIdentifier();
					if (identifier == null || identifier.isBlank()) {
						failed++;
						continue;
					}

					if (store.containsKey(identifier)) {
						skipped++;
						continue;
					}

					// Back-pressure: wait until in-flight drops below cap
					while (inFlight.get() >= maxInFlight) {
						try {
							Thread.sleep(50);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return new Result(migrated, skipped, failed, files, totalBytes);
						}
					}

					inFlight.incrementAndGet();
					WaveDetailsJob job = library.submitMigrationJob(
							details, AudioLibrary.BACKGROUND_PRIORITY);
					job.getFuture().thenRun(inFlight::decrementAndGet);
					migrated++;
				}
			} catch (IOException e) {
				warn("Error reading legacy batch", e);
				failed++;
			} finally {
				try {
					input.close();
				} catch (IOException ignored) { }
			}

			input = inputSupplier.get();
		}

		// Wait for remaining in-flight jobs to finish
		while (inFlight.get() > 0) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		log("Legacy migration complete: " + migrated + " migrated, "
				+ skipped + " skipped, " + failed + " failed");

		return new Result(migrated, skipped, failed, files, totalBytes);
	}

	/**
	 * Result of a legacy library migration.
	 *
	 * @param migratedCount number of records successfully migrated
	 * @param skippedCount  number of records skipped (already in store)
	 * @param failedCount   number of records that failed to decode
	 * @param legacyFiles   list of legacy batch file paths
	 * @param totalBytes    total size of legacy files in bytes
	 */
	public record Result(int migratedCount, int skippedCount, int failedCount,
						 List<String> legacyFiles, long totalBytes) {

		/**
		 * Deletes all legacy batch files.
		 *
		 * @return the number of files successfully deleted
		 */
		public int deleteLegacyFiles() {
			int deleted = 0;
			for (String path : legacyFiles) {
				if (new File(path).delete()) deleted++;
			}
			return deleted;
		}
	}
}
