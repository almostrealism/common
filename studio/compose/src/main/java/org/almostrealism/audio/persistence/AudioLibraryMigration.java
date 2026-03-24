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

package org.almostrealism.audio.persistence;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Migrates audio library data from the legacy {@link AudioLibraryPersistence}
 * batch format to the new {@link ProtobufWaveDetailsStore} format.
 *
 * <p>The legacy format stores {@link Audio.AudioLibraryData} wrapper messages
 * in files named {@code PREFIX_0.bin}, {@code PREFIX_1.bin}, etc. The new
 * format uses a {@link org.almostrealism.persist.index.ProtobufDiskStore} with
 * length-delimited {@link Audio.WaveDetailData} records, a separate index
 * file, and an optional HNSW vector index.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AudioLibraryMigration.migrate(
 *     Path.of("/path/to/library"),    // old PREFIX
 *     Path.of("/path/to/store_dir")); // new store directory
 * }</pre>
 *
 * <p>Migration is idempotent: if the target directory already exists and
 * contains records, new records are added without duplicating existing ones.
 * The old batch files are never modified or deleted.</p>
 *
 * @see AudioLibraryPersistence
 * @see ProtobufWaveDetailsStore
 */
public class AudioLibraryMigration {

	/**
	 * Migrates all {@link WaveDetails} records from old
	 * {@link AudioLibraryPersistence} batch files into a new
	 * {@link ProtobufWaveDetailsStore}.
	 *
	 * <p>For each record with complete feature data, a mean-pooled embedding
	 * vector is computed and stored alongside the record in the HNSW index.</p>
	 *
	 * @param oldDataPrefix path prefix for legacy protobuf files
	 *                      (e.g. {@code /path/to/library} for
	 *                       {@code library_0.bin}, {@code library_1.bin}, ...)
	 * @param newStoreDir   directory for the new store
	 * @return the number of records migrated
	 */
	public static int migrate(Path oldDataPrefix, Path newStoreDir) {
		String prefix = oldDataPrefix.toString();
		File storeDir = newStoreDir.toFile();

		File firstFile = new File(prefix + "_0.bin");
		if (!firstFile.exists()) {
			Console.root().features(AudioLibraryMigration.class)
					.warn("No legacy data found at " + prefix + "_0.bin");
			return 0;
		}

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);
		int migrated = 0;

		try {
			Supplier<InputStream> inputSupplier = new LibraryDestination(prefix).in();
			InputStream input = inputSupplier.get();

			while (input != null) {
				try {
					Audio.AudioLibraryData data = Audio.AudioLibraryData
							.newBuilder().mergeFrom(input).build();

					for (Audio.WaveDetailData detailData : data.getInfoMap().values()) {
						WaveDetails details = AudioLibraryPersistence.decode(detailData);
						String identifier = details.getIdentifier();

						if (identifier == null || identifier.isBlank()) continue;
						if (store.containsKey(identifier)) continue;

						PackedCollection embedding =
								AudioLibrary.computeEmbeddingVector(details);

						if (embedding != null) {
							store.put(identifier, details, embedding);
						} else {
							store.put(identifier, details);
						}

						migrated++;
					}
				} finally {
					input.close();
				}

				input = inputSupplier.get();
			}
		} catch (IOException e) {
			throw new RuntimeException("Migration failed", e);
		} finally {
			store.close();
		}

		Console.root().features(AudioLibraryMigration.class)
				.log("Migrated " + migrated + " records from " + prefix
						+ " to " + storeDir);
		return migrated;
	}
}
