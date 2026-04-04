/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Precision;
import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.persist.assets.CollectionEncoder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Provides serialization and deserialization of {@link AudioLibrary} data to/from
 * Protocol Buffer format.
 *
 * <p>AudioLibraryPersistence handles saving and loading library metadata (including
 * analyzed audio features and similarity scores) to protobuf files. The data is stored
 * in batched files with a prefix pattern (e.g., {@code library_0.bin}, {@code library_1.bin}).</p>
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Identifier-based storage</b>: {@link WaveDetails} are keyed by their content
 *       identifier (MD5 hash), not file path. File paths are resolved at runtime via
 *       {@link AudioLibrary#find(String)}.</li>
 *   <li><b>Batched files</b>: Large libraries are split across multiple files to avoid
 *       memory issues during serialization.</li>
 * </ul>
 *
 * <h2>Saving a Library</h2>
 * <pre>{@code
 * AudioLibrary library = ...;
 * AudioLibraryPersistence.saveLibrary(library, "/path/to/library");
 * // Creates: /path/to/library_0.bin, /path/to/library_1.bin, etc.
 * }</pre>
 *
 * <h2>Loading a Library</h2>
 * <pre>{@code
 * // Option 1: Load into existing library with file tree
 * AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);
 * AudioLibraryPersistence.loadLibrary(library, "/path/to/library");
 *
 * // Now library.find(identifier) can resolve file paths
 * library.allDetails().forEach(d -> {
 *     WaveDataProvider provider = library.find(d.getIdentifier());
 *     String filePath = provider != null ? provider.getKey() : "unknown";
 * });
 *
 * // Option 2: Load with file tree in one call
 * AudioLibrary library = AudioLibraryPersistence.loadLibrary(
 *     new File("/path/to/samples"), 44100, "/path/to/library");
 * }</pre>
 *
 * <h2>Data Stored in Protobuf</h2>
 * <p>Each {@link WaveDetails} record includes:</p>
 * <ul>
 *   <li>Identifier (content hash) - used as the key</li>
 *   <li>Audio metadata (sample rate, channels, frame count)</li>
 *   <li>Frequency analysis data (FFT results)</li>
 *   <li>Feature data (for similarity computation)</li>
 *   <li>Pre-computed similarity scores to other samples</li>
 * </ul>
 *
 * <h2>File Path Resolution</h2>
 * <p><b>Important:</b> The protobuf does NOT store file paths. The identifier stored
 * is the MD5 hash of file contents. To resolve an identifier to a file path:</p>
 * <ol>
 *   <li>Load the library with a file tree (directory of audio files)</li>
 *   <li>Call {@link AudioLibrary#find(String)} with the identifier</li>
 *   <li>Call {@link org.almostrealism.audio.data.WaveDataProvider#getKey()} on the result</li>
 * </ol>
 *
 * @see AudioLibrary
 * @see LibraryDestination
 * @see WaveDetails
 *
 * @deprecated Use {@link ProtobufWaveDetailsStore} for new code.
 *             This class is retained for reading legacy batch files
 *             and for migration via {@link AudioLibraryMigration}.
 */
@Deprecated
public class AudioLibraryPersistence {
	/** Maximum bytes per batch file before starting a new file. */
	public static int batchSize = Integer.MAX_VALUE / 2;

	/** Maximum number of loadSingleDetail calls allowed within the rate window. */
	static final int SINGLE_DETAIL_MAX_CALLS = 10;

	/** Rate window in milliseconds for loadSingleDetail call tracking. */
	static final long SINGLE_DETAIL_RATE_WINDOW_MS = 60_000;

	/**
	 * Timestamps of recent {@link #loadSingleDetail} calls, used to detect
	 * runaway loops that should be using a {@link org.almostrealism.audio.data.WaveDetailsStore} instead.
	 */
	private static final Deque<Long> singleDetailCallTimestamps = new ArrayDeque<>();

	/**
	 * Returns a consumer that saves each {@link WaveDetails} to the given destination.
	 *
	 * @param destination directory or file path for persisted wave detail data
	 * @return a consumer that calls {@link #saveWaveDetails(WaveDetails, String)}
	 */
	public static Consumer<WaveDetails> saveWaveDetails(String destination) {
		return details -> {
			try {
				saveWaveDetails(details, destination);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};
	}

	/**
	 * Saves a single {@link WaveDetails} to the given file or directory.
	 * If {@code destination} is a directory, the file is named using the details identifier.
	 *
	 * @param details     the wave details to serialize
	 * @param destination file path or directory path
	 * @throws IOException if writing fails
	 */
	public static void saveWaveDetails(WaveDetails details, String destination) throws IOException {
		File f = new File(destination);
		if (f.isDirectory()) {
			f = new File(f, Objects.requireNonNull(details.getIdentifier()) + ".bin");
		}

		encode(details, true).writeTo(new FileOutputStream(f));
	}

	/**
	 * Loads a single {@link WaveDetails} from the given protobuf file.
	 *
	 * @param source path to the serialized wave detail file
	 * @return the decoded WaveDetails
	 * @throws IOException if reading or parsing fails
	 */
	public static WaveDetails loadWaveDetails(String source) throws IOException {
		return decode(Audio.WaveDetailData.newBuilder().mergeFrom(new FileInputStream(source)).build());
	}

	/**
	 * Saves the library to protobuf files at the given prefix.
	 *
	 * <p>Before writing, this method verifies that all known identifiers can be
	 * loaded (either from cache or via the details loader). If loadable entries
	 * are fewer than the total known identifiers, saving would destroy the
	 * unloadable entries on disk, so an {@link IllegalStateException} is thrown
	 * instead.</p>
	 *
	 * @param library    the library to save
	 * @param dataPrefix path prefix for protobuf output files
	 * @throws IllegalStateException if saving would lose data because some
	 *         entries cannot be loaded from cache or disk
	 */
	public static void saveLibrary(AudioLibrary library, String dataPrefix) {
		int identifierCount = library.getAllIdentifiers().size();
		long loadableCount = library.allDetails().count();
		if (loadableCount < identifierCount) {
			throw new IllegalStateException(
					"Refusing to save: only " + loadableCount + " of " +
					identifierCount + " entries are loadable. " +
					"Configure a details loader via setDetailsLoader() before saving " +
					"to prevent data loss.");
		}

		try (LibraryDestination.Writer out = new LibraryDestination(dataPrefix).out()) {
			saveLibrary(library, out);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Saves the library to protobuf files using the given output stream supplier,
	 * without embedding raw audio data.
	 *
	 * @param library the library to save
	 * @param out     supplier of output streams for each batch file
	 * @throws IOException if writing fails
	 */
	public static void saveLibrary(AudioLibrary library, Supplier<OutputStream> out) throws IOException {
		saveLibrary(library, false, out);
	}

	/**
	 * Saves the library to protobuf files using the given output stream supplier.
	 *
	 * @param library      the library to save
	 * @param includeAudio whether to embed raw PCM audio in each entry
	 * @param out          supplier of output streams for each batch file
	 * @throws IOException if writing fails
	 */
	public static void saveLibrary(AudioLibrary library, boolean includeAudio, Supplier<OutputStream> out) throws IOException {
		Audio.AudioLibraryData.Builder data = Audio.AudioLibraryData.newBuilder();
		List<WaveDetails> details = library.allDetails().toList();

		int byteCount = 0;

		if (details.isEmpty() && library.getPrototypeIndex() != null) {
			data.setPrototypeIndex(encodePrototypeIndex(library.getPrototypeIndex()));
			writeBatch(data, out);
			return;
		}

		for (int i = 0; i < details.size(); i++) {
			Audio.WaveDetailData d = encode(details.get(i), includeAudio);
			byteCount += d.getSerializedSize();
			data.putInfo(d.getIdentifier(), d);

			if (byteCount > batchSize || i == details.size() - 1) {
				if (i == details.size() - 1 && library.getPrototypeIndex() != null) {
					data.setPrototypeIndex(encodePrototypeIndex(library.getPrototypeIndex()));
				}

				writeBatch(data, out);
				data = Audio.AudioLibraryData.newBuilder();
				byteCount = 0;
			}
		}
	}

	/**
	 * Saves a list of wave recordings to protobuf files via the given output stream supplier.
	 * Data is flushed in batches to limit memory usage.
	 *
	 * @param recordings the recordings to save
	 * @param out        supplier of output streams for each batch file
	 * @throws IOException if writing fails
	 */
	public static void saveRecordings(List<Audio.WaveRecording> recordings, Supplier<OutputStream> out) throws IOException {
		Audio.AudioLibraryData.Builder data = Audio.AudioLibraryData.newBuilder();

		int byteCount = 0;

		for (int i = 0; i < recordings.size(); i++) {
			Audio.WaveRecording d = recordings.get(i);
			byteCount += d.getSerializedSize();
			data.addRecordings(d);

			if (byteCount > batchSize || i == recordings.size() - 1) {
				OutputStream o = out.get();

				try {
					data.build().writeTo(o);
					Console.root.features(AudioLibraryPersistence.class)
							.log("Wrote " + data.getRecordingsCount() +
									" recordings (" + byteCount + " bytes)");

					data = Audio.AudioLibraryData.newBuilder();
					byteCount = 0;
					o.flush();
				} finally {
					o.close();
				}
			}
		}
	}

	/**
	 * Creates a new {@link AudioLibrary} from a samples directory and loads
	 * pre-computed data from protobuf files at the given prefix.
	 *
	 * <p>Automatically configures the details loader so evicted cache entries
	 * can be reloaded from disk on demand.</p>
	 *
	 * @param root       directory containing audio sample files
	 * @param sampleRate target sample rate for analysis
	 * @param dataPrefix path prefix for protobuf files
	 * @return the populated AudioLibrary
	 */
	public static AudioLibrary loadLibrary(File root, int sampleRate, String dataPrefix) {
		try {
			AudioLibrary library = loadLibrary(root, sampleRate, new LibraryDestination(dataPrefix).in());
			library.setDetailsLoader(createDetailsLoader(dataPrefix));
			return library;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Loads library data from protobuf files at the given prefix and automatically
	 * configures the {@link AudioLibrary#setDetailsLoader(Function) detailsLoader}
	 * so that entries evicted from the in-memory cache can be reloaded on demand.
	 *
	 * <p>This is the preferred entry point for loading a library. Without the
	 * automatic loader wiring, evicted entries become unreachable and operations
	 * like {@link AudioLibrary#allDetails()} or {@link #saveLibrary(AudioLibrary, String)}
	 * silently operate on a subset, potentially destroying data on disk.</p>
	 *
	 * @param library    the library to populate
	 * @param dataPrefix path prefix for protobuf files (e.g. {@code /path/to/library})
	 */
	public static void loadLibrary(AudioLibrary library, String dataPrefix) {
		try {
			loadLibrary(library, new LibraryDestination(dataPrefix).in());
			library.setDetailsLoader(createDetailsLoader(dataPrefix));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Creates a new {@link AudioLibrary} for the given root and sample rate, then loads
	 * data from the given input stream supplier.
	 *
	 * @param root       directory containing audio sample files
	 * @param sampleRate target sample rate
	 * @param in         supplier of input streams for each batch file
	 * @return the populated library
	 * @throws IOException if reading fails
	 */
	public static AudioLibrary loadLibrary(File root, int sampleRate, Supplier<InputStream> in) throws IOException {
		return loadLibrary(new AudioLibrary(root, sampleRate), in);
	}

	/**
	 * Loads data from the given input stream supplier into the library, including similarities.
	 *
	 * @param library the library to populate
	 * @param in      supplier of input streams for each batch file
	 * @return the populated library
	 * @throws IOException if reading fails
	 */
	public static AudioLibrary loadLibrary(AudioLibrary library, Supplier<InputStream> in) throws IOException {
		return loadLibrary(library, in, true);
	}

	/**
	 * Loads library data from protobuf batch files into the given {@link AudioLibrary}.
	 *
	 * <p>When {@code includeSimilarities} is false, similarity maps are skipped during
	 * decoding. This dramatically reduces memory usage for large libraries where each
	 * entry may store pairwise similarities to every other entry (N² growth). Similarities
	 * can be recomputed on demand via {@link AudioLibrary#computeSimilarities}.</p>
	 *
	 * @param library             the library to populate
	 * @param in                  supplier of input streams for each batch file
	 * @param includeSimilarities whether to load similarity maps from protobuf
	 * @return the populated library
	 * @throws IOException if reading from the input stream fails
	 */
	public static AudioLibrary loadLibrary(AudioLibrary library, Supplier<InputStream> in,
										   boolean includeSimilarities) throws IOException {
		InputStream input = in.get();

		while (input != null) {
			Audio.AudioLibraryData data = Audio.AudioLibraryData.newBuilder().mergeFrom(input).build();

			for (Audio.WaveDetailData d : data.getInfoMap().values()) {
				WaveDetails details = decode(d, includeSimilarities);

				if (details.getIdentifier() == null) {
					Console.root().features(AudioLibraryPersistence.class)
							.warn("Missing identifier for details (" +
									details.getFrameCount() + " frames)");
				} else {
					library.include(details);
				}
			}

			if (data.hasPrototypeIndex()) {
				library.setPrototypeIndex(decodePrototypeIndex(data.getPrototypeIndex()));
			}

			input = in.get();
		}

		return library;
	}

	/**
	 * Loads wave detail data for a single recording by key from the given data prefix.
	 *
	 * @param key        the recording key to look up
	 * @param dataPrefix path prefix for protobuf library files
	 * @return ordered list of wave detail data for the recording
	 */
	public static List<Audio.WaveDetailData> loadRecording(String key, String dataPrefix) {
		try {
			return loadRecording(key, new LibraryDestination(dataPrefix), false);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Loads wave detail data for a recording group by group key from the given data prefix.
	 *
	 * @param key        the group key to look up
	 * @param dataPrefix path prefix for protobuf library files
	 * @return ordered list of wave detail data for the group
	 */
	public static List<Audio.WaveDetailData> loadRecordingGroup(String key, String dataPrefix) {
		try {
			return loadRecording(key, new LibraryDestination(dataPrefix), true);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Loads wave detail data for a recording or recording group from the given destination.
	 *
	 * @param key         the recording or group key
	 * @param destination the library destination to read from
	 * @param group       if {@code true}, match by group key; otherwise match by recording key
	 * @return ordered list of wave detail data
	 * @throws IOException if reading fails
	 */
	public static List<Audio.WaveDetailData> loadRecording(String key, LibraryDestination destination, boolean group) throws IOException {
		return loadRecording(key, destination.in(), group);
	}

	/**
	 * Loads wave detail data for a recording or recording group from the given input stream supplier.
	 *
	 * @param key   the recording or group key
	 * @param in    supplier of input streams for each batch file
	 * @param group if {@code true}, match by group key; otherwise match by recording key
	 * @return ordered list of wave detail data
	 * @throws IOException if reading fails
	 */
	public static List<Audio.WaveDetailData> loadRecording(String key, Supplier<InputStream> in, boolean group) throws IOException {
		InputStream input = in.get();


		TreeSet<Audio.WaveRecording> recordings;

		if (group) {
			recordings = new TreeSet<>(Comparator.comparing(Audio.WaveRecording::getGroupOrderIndex));
		} else {
			recordings = new TreeSet<>(Comparator.comparing(Audio.WaveRecording::getOrderIndex));
		}

		while (input != null) {
			Audio.AudioLibraryData data = Audio.AudioLibraryData.newBuilder().mergeFrom(input).build();

			for (Audio.WaveRecording r : data.getRecordingsList()) {
				if (group && key.equals(r.getGroupKey())) {
					recordings.add(r);
				} else if (!group && key.equals(r.getKey())) {
					recordings.add(r);
				}
			}

			input = in.get();
		}

		return recordings.stream()
				.map(Audio.WaveRecording::getDataList)
				.flatMap(List::stream)
				.toList();
	}

	/**
	 * Returns the set of all recording keys found in the library files at the given prefix.
	 *
	 * @param dataPrefix path prefix for protobuf library files
	 * @return set of recording keys
	 */
	public static Set<String> listRecordings(String dataPrefix) {
		try {
			return listRecordings(new LibraryDestination(dataPrefix), false);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns recording keys or group keys found in the given destination.
	 *
	 * @param destination the library destination to scan
	 * @param group       if {@code true}, return group keys; otherwise return individual recording keys
	 * @return set of matching keys
	 * @throws IOException if reading fails
	 */
	public static Set<String> listRecordings(LibraryDestination destination, boolean group) throws IOException {
		return listRecordings(destination.in(), group);
	}

	/**
	 * Returns a map from group key to the set of recording keys within the group.
	 *
	 * @param destination   the library destination to scan
	 * @param includeSilent whether to include silent recordings
	 * @return map from group key to member recording keys
	 * @throws IOException if reading fails
	 */
	public static Map<String, Set<String>> listRecordingsGrouped(LibraryDestination destination,
																 boolean includeSilent) throws IOException {
		return listRecordingsGrouped(destination.in(), includeSilent);
	}

	/**
	 * Returns recording keys or group keys from the given input stream supplier.
	 *
	 * @param in    supplier of input streams for each batch file
	 * @param group if {@code true}, return group keys; otherwise return individual keys
	 * @return set of matching keys
	 * @throws IOException if reading fails
	 */
	public static Set<String> listRecordings(Supplier<InputStream> in, boolean group) throws IOException {
		if (group) {
			return listRecordingsGrouped(in, true).keySet();
		} else {
			return listRecordingsFlat(in);
		}
	}

	/**
	 * Returns a map from group key to the set of recording keys within the group
	 * from the given input stream supplier.
	 *
	 * @param in            supplier of input streams for each batch file
	 * @param includeSilent whether to include silent recordings
	 * @return map from group key to member recording keys
	 * @throws IOException if reading fails
	 */
	public static Map<String, Set<String>> listRecordingsGrouped(Supplier<InputStream> in,
																 boolean includeSilent) throws IOException {
		return listRecordings(in, includeSilent, null);
	}

	/**
	 * Returns the flat set of all individual recording keys from the given input stream supplier.
	 *
	 * @param in supplier of input streams for each batch file
	 * @return set of recording keys
	 * @throws IOException if reading fails
	 */
	public static Set<String> listRecordingsFlat(Supplier<InputStream> in) throws IOException {
		Set<String> keys = new HashSet<>();
		listRecordings(in, true, keys::add);
		return keys;
	}

	/**
	 * Scans all batch files and builds a map from group key to member recording keys,
	 * optionally reporting all individual keys via a consumer.
	 *
	 * @param in            supplier of input streams for each batch file
	 * @param includeSilent whether to include silent recordings
	 * @param keys          optional consumer notified of each individual recording key; may be {@code null}
	 * @return map from group key to the set of member recording keys
	 * @throws IOException if reading fails
	 */
	protected static Map<String, Set<String>> listRecordings(Supplier<InputStream> in,
															 boolean includeSilent,
															 Consumer<String> keys) throws IOException {
		InputStream input = in.get();

		Map<String, Set<String>> recordings = new HashMap<>();

		while (input != null) {
			Audio.AudioLibraryData data = Audio.AudioLibraryData
					.newBuilder().mergeFrom(input).build();

			r: for (Audio.WaveRecording r : data.getRecordingsList()) {
				if (!includeSilent && r.hasSilent() && r.getSilent()) {
					continue r;
				}

				if (r.hasGroupKey()) {
					recordings.putIfAbsent(r.getGroupKey(), new HashSet<>());
					recordings.get(r.getGroupKey()).add(r.getKey());
				}

				if (keys != null) {
					keys.accept(r.getKey());
				}
			}

			input = in.get();
		}

		return recordings;
	}

	/**
	 * Loads a single {@link WaveDetails} entry by content identifier from
	 * protobuf library files at the given data prefix.
	 *
	 * <p>This method scans through all batch files (PREFIX_0.bin, PREFIX_1.bin, ...)
	 * and returns the first matching entry. It is intended for on-demand loading
	 * of entries that have been evicted from the in-memory cache.</p>
	 *
	 * @param dataPrefix the path prefix for library protobuf files
	 * @param identifier the content identifier (MD5 hash) to look up
	 * @return the decoded WaveDetails, or null if not found
	 */
	public static WaveDetails loadSingleDetail(String dataPrefix, String identifier) {
		enforceCallRateLimit();
		try {
			long start = System.currentTimeMillis();
			WaveDetails result = loadSingleDetail(new LibraryDestination(dataPrefix).in(), identifier);
			long elapsed = System.currentTimeMillis() - start;
			Console.root().features(AudioLibraryPersistence.class)
					.log("loadSingleDetail(" + identifier + ") took " + elapsed + "ms");
			return result;
		} catch (IOException e) {
			Console.root().features(AudioLibraryPersistence.class)
					.warn("Failed to load WaveDetails for " + identifier + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Loads a single {@link WaveDetails} entry by content identifier from
	 * the given input stream supplier (which yields successive batch files).
	 *
	 * @param in supplier of input streams for each batch file (returns null when exhausted)
	 * @param identifier the content identifier (MD5 hash) to look up
	 * @return the decoded WaveDetails, or null if not found
	 * @throws IOException if reading from the input stream fails
	 */
	public static WaveDetails loadSingleDetail(Supplier<InputStream> in, String identifier) throws IOException {
		InputStream input = in.get();

		while (input != null) {
			try {
				Audio.AudioLibraryData data = Audio.AudioLibraryData.newBuilder().mergeFrom(input).build();
				Audio.WaveDetailData d = data.getInfoMap().get(identifier);
				if (d != null) {
					return decode(d);
				}
			} finally {
				input.close();
			}

			input = in.get();
		}

		return null;
	}

	/**
	 * Enforces a call-rate limit on {@link #loadSingleDetail}. If more than
	 * {@link #SINGLE_DETAIL_MAX_CALLS} calls occur within
	 * {@link #SINGLE_DETAIL_RATE_WINDOW_MS}, an {@link IllegalStateException}
	 * is thrown. This prevents runaway loops where the slow full-scan path
	 * is used instead of a {@link org.almostrealism.audio.data.WaveDetailsStore}.
	 */
	static synchronized void enforceCallRateLimit() {
		long now = System.currentTimeMillis();
		long cutoff = now - SINGLE_DETAIL_RATE_WINDOW_MS;

		while (!singleDetailCallTimestamps.isEmpty()
				&& singleDetailCallTimestamps.peekFirst() < cutoff) {
			singleDetailCallTimestamps.pollFirst();
		}

		singleDetailCallTimestamps.addLast(now);

		if (singleDetailCallTimestamps.size() > SINGLE_DETAIL_MAX_CALLS) {
			throw new IllegalStateException(
					"loadSingleDetail called " + singleDetailCallTimestamps.size()
					+ " times within " + (SINGLE_DETAIL_RATE_WINDOW_MS / 1000)
					+ "s — use a WaveDetailsStore instead of the legacy batch-scan path");
		}
	}

	/** Resets the call-rate tracking for {@link #loadSingleDetail}. Visible for testing. */
	public static synchronized void resetCallRateLimit() {
		singleDetailCallTimestamps.clear();
	}

	/**
	 * Creates a loader function suitable for use with
	 * {@link AudioLibrary#setDetailsLoader(Function)}.
	 *
	 * <p>The returned function loads a single {@link WaveDetails} from the protobuf
	 * files at the given data prefix, scanning all batch files for the requested
	 * identifier.</p>
	 *
	 * @param dataPrefix the path prefix for library protobuf files
	 * @return a function that loads a WaveDetails by identifier from disk
	 */
	public static Function<String, WaveDetails> createDetailsLoader(String dataPrefix) {
		return identifier -> loadSingleDetail(dataPrefix, identifier);
	}

	/**
	 * Encodes a {@link WaveDetails} to its protobuf representation, optionally embedding raw audio.
	 *
	 * @param details      the wave details to encode
	 * @param includeAudio if {@code true}, raw PCM audio is embedded using FP32 precision
	 * @return the serialized protobuf message
	 */
	public static Audio.WaveDetailData encode(WaveDetails details, boolean includeAudio) {
		return encode(details, includeAudio ? Precision.FP32 : null);
	}

	/**
	 * Encodes a {@link WaveDetails} into a protobuf {@link Audio.WaveDetailData},
	 * including raw audio data only when {@code audioPrecision} is non-null.
	 *
	 * @param details        the wave details to encode
	 * @param audioPrecision the numeric precision for audio data, or {@code null} to omit audio
	 * @return the protobuf-encoded wave detail data
	 */
	public static Audio.WaveDetailData encode(WaveDetails details, Precision audioPrecision) {
		Audio.WaveDetailData.Builder data = Audio.WaveDetailData.newBuilder()
				.setSampleRate(details.getSampleRate())
				.setChannelCount(details.getChannelCount())
				.setFrameCount(details.getFrameCount())
				.setSilent(details.isSilent())
				.setPersistent(details.isPersistent())
				.setFreqSampleRate(details.getFreqSampleRate())
				.setFreqBinCount(details.getFreqBinCount())
				.setFreqChannelCount(details.getFreqChannelCount())
				.setFreqFrameCount(details.getFreqFrameCount())
				.setFreqData(CollectionEncoder.encode(details.getFreqData(), Precision.FP32))
				.setFeatureSampleRate(details.getFeatureSampleRate())
				.setFeatureBinCount(details.getFeatureBinCount())
				.setFeatureChannelCount(details.getFeatureChannelCount())
				.setFeatureFrameCount(details.getFeatureFrameCount())
				.setFeatureData(CollectionEncoder.encode(details.getFeatureData(), Precision.FP32))
				.putAllSimilarities(details.getSimilarities());
		if (details.getIdentifier() != null) data.setIdentifier(details.getIdentifier());
		if (audioPrecision != null) {
			data.setData(CollectionEncoder.encode(details.getData(), audioPrecision));
		}

		return data.build();
	}

	/**
	 * Decodes a protobuf {@link Audio.WaveDetailData} into a {@link WaveDetails},
	 * including all similarity data.
	 *
	 * @param data the protobuf data to decode
	 * @return the decoded {@link WaveDetails}
	 */
	public static WaveDetails decode(Audio.WaveDetailData data) {
		return decode(data, true);
	}

	/**
	 * Decodes a protobuf {@link Audio.WaveDetailData} into a {@link WaveDetails}.
	 *
	 * @param data                the protobuf data to decode
	 * @param includeSimilarities whether to populate the similarity map; when false,
	 *                            the map is left empty to avoid N² memory growth
	 * @return the decoded WaveDetails
	 */
	public static WaveDetails decode(Audio.WaveDetailData data, boolean includeSimilarities) {
		WaveDetails details = new WaveDetails(data.getIdentifier().isBlank() ? null : data.getIdentifier());
		details.setSampleRate(data.getSampleRate());
		details.setChannelCount(data.getChannelCount());
		details.setFrameCount(data.getFrameCount());
		details.setSilent(data.getSilent());
		details.setPersistent(data.getPersistent());
		details.setFreqSampleRate(data.getFreqSampleRate());
		details.setFreqBinCount(data.getFreqBinCount());
		details.setFreqChannelCount(data.getFreqChannelCount());
		details.setFreqFrameCount(data.getFreqFrameCount());
		details.setFreqData(CollectionEncoder.decode(data.getFreqData()));
		details.setFeatureSampleRate(data.getFeatureSampleRate());
		details.setFeatureBinCount(data.getFeatureBinCount());
		details.setFeatureChannelCount(data.getFeatureChannelCount());
		details.setFeatureFrameCount(data.getFeatureFrameCount());
		if (data.hasFeatureData()) details.setFeatureData(CollectionEncoder.decode(data.getFeatureData()));
		if (includeSimilarities) details.getSimilarities().putAll(data.getSimilaritiesMap());
		if (data.hasData()) details.setData(CollectionEncoder.decode(data.getData()));
		return details;
	}

	/**
	 * Writes a serialized {@link Audio.AudioLibraryData} to the next output stream
	 * provided by the given supplier and closes the stream.
	 */
	private static void writeBatch(Audio.AudioLibraryData.Builder data, Supplier<OutputStream> out) throws IOException {
		OutputStream o = out.get();

		try {
			data.build().writeTo(o);
			o.flush();
		} finally {
			o.close();
		}
	}

	/**
	 * Encodes a {@link PrototypeIndexData} to its protobuf representation.
	 */
	public static Audio.PrototypeIndex encodePrototypeIndex(PrototypeIndexData index) {
		Audio.PrototypeIndex.Builder builder = Audio.PrototypeIndex.newBuilder()
				.setComputedAt(index.computedAt());

		for (PrototypeIndexData.Community community : index.communities()) {
			builder.addCommunities(Audio.PrototypeCommunity.newBuilder()
					.setPrototypeIdentifier(community.prototypeIdentifier())
					.setCentrality(community.centrality())
					.addAllMemberIdentifiers(community.memberIdentifiers())
					.build());
		}

		return builder.build();
	}

	/**
	 * Decodes a protobuf {@code PrototypeIndex} to its in-memory representation.
	 */
	public static PrototypeIndexData decodePrototypeIndex(Audio.PrototypeIndex proto) {
		List<PrototypeIndexData.Community> communities = proto.getCommunitiesList().stream()
				.map(c -> new PrototypeIndexData.Community(
						c.getPrototypeIdentifier(),
						c.getCentrality(),
						c.getMemberIdentifiersList()))
				.toList();

		return new PrototypeIndexData(proto.getComputedAt(), communities);
	}

	/**
	 * Assembles a {@link WaveData} from a list of sequentially ordered wave detail data entries.
	 *
	 * @param data ordered list of wave detail data to concatenate
	 * @return the assembled WaveData, or {@code null} if the list is empty
	 */
	public static WaveData toWaveData(List<Audio.WaveDetailData> data) {
		if (data == null || data.isEmpty()) return null;

		int totalFrames = data.stream().mapToInt(Audio.WaveDetailData::getFrameCount).sum();

		PackedCollection output = new PackedCollection(totalFrames);
		int cursor = 0;

		for (Audio.WaveDetailData d : data) {
			CollectionEncoder.decode(d.getData(), output, cursor);
			cursor += d.getFrameCount();
		}

		return new WaveData(output.each(), data.get(0).getSampleRate());
	}
}
