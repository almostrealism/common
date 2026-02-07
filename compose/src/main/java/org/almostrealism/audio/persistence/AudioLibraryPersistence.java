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

package org.almostrealism.audio.persistence;

import io.almostrealism.code.Precision;
import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.persistence.CollectionEncoder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
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
 * for (WaveDetails d : library.getAllDetails()) {
 *     WaveDataProvider provider = library.find(d.getIdentifier());
 *     String filePath = provider != null ? provider.getKey() : "unknown";
 * }
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
 */
public class AudioLibraryPersistence {
	/** Maximum bytes per batch file before starting a new file. */
	public static int batchSize = Integer.MAX_VALUE / 2;

	public static Consumer<WaveDetails> saveWaveDetails(String destination) {
		return details -> {
			try {
				saveWaveDetails(details, destination);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};
	}

	public static void saveWaveDetails(WaveDetails details, String destination) throws IOException {
		File f = new File(destination);
		if (f.isDirectory()) {
			f = new File(f, Objects.requireNonNull(details.getIdentifier()) + ".bin");
		}

		encode(details, true).writeTo(new FileOutputStream(f));
	}

	public static WaveDetails loadWaveDetails(String source) throws IOException {
		return decode(Audio.WaveDetailData.newBuilder().mergeFrom(new FileInputStream(source)).build());
	}

	public static void saveLibrary(AudioLibrary library, String dataPrefix) {
		try (LibraryDestination.Writer out = new LibraryDestination(dataPrefix).out()) {
			saveLibrary(library, out);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void saveLibrary(AudioLibrary library, Supplier<OutputStream> out) throws IOException {
		saveLibrary(library, false, out);
	}

	public static void saveLibrary(AudioLibrary library, boolean includeAudio, Supplier<OutputStream> out) throws IOException {
		Audio.AudioLibraryData.Builder data = Audio.AudioLibraryData.newBuilder();
		List<WaveDetails> details = new ArrayList<>(library.getAllDetails());

		int byteCount = 0;

		for (int i = 0; i < details.size(); i++) {
			if (byteCount > batchSize || i == details.size() - 1) {
				OutputStream o = out.get();

				try {
					data.build().writeTo(o);
					data = Audio.AudioLibraryData.newBuilder();
					byteCount = 0;
					o.flush();
				} finally {
					o.close();
				}
			}

			Audio.WaveDetailData d = encode(details.get(i), includeAudio);
			byteCount += d.getSerializedSize();
			data.putInfo(d.getIdentifier(), d);
		}
	}

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

	public static AudioLibrary loadLibrary(File root, int sampleRate, String dataPrefix) {
		try {
			return loadLibrary(root, sampleRate, new LibraryDestination(dataPrefix).in());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void loadLibrary(AudioLibrary library, String dataPrefix) {
		try {
			loadLibrary(library, new LibraryDestination(dataPrefix).in());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static AudioLibrary loadLibrary(File root, int sampleRate, Supplier<InputStream> in) throws IOException {
		return loadLibrary(new AudioLibrary(root, sampleRate), in);
	}

	public static AudioLibrary loadLibrary(AudioLibrary library, Supplier<InputStream> in) throws IOException {
		InputStream input = in.get();

		while (input != null) {
			Audio.AudioLibraryData data = Audio.AudioLibraryData.newBuilder().mergeFrom(input).build();

			for (Audio.WaveDetailData d : data.getInfoMap().values()) {
				WaveDetails details = decode(d);

				if (details.getIdentifier() == null) {
					Console.root().features(AudioLibraryPersistence.class)
							.warn("Missing identifier for details (" +
									details.getFrameCount() + " frames)");
				} else {
					library.include(details);
				}
			}

			input = in.get();
		}

		return library;
	}

	public static List<Audio.WaveDetailData> loadRecording(String key, String dataPrefix) {
		try {
			return loadRecording(key, new LibraryDestination(dataPrefix), false);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static List<Audio.WaveDetailData> loadRecordingGroup(String key, String dataPrefix) {
		try {
			return loadRecording(key, new LibraryDestination(dataPrefix), true);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static List<Audio.WaveDetailData> loadRecording(String key, LibraryDestination destination, boolean group) throws IOException {
		return loadRecording(key, destination.in(), group);
	}

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

	public static Set<String> listRecordings(String dataPrefix) {
		try {
			return listRecordings(new LibraryDestination(dataPrefix), false);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static Set<String> listRecordings(LibraryDestination destination, boolean group) throws IOException {
		return listRecordings(destination.in(), group);
	}

	public static Map<String, Set<String>> listRecordingsGrouped(LibraryDestination destination,
																 boolean includeSilent) throws IOException {
		return listRecordingsGrouped(destination.in(), includeSilent);
	}

	public static Set<String> listRecordings(Supplier<InputStream> in, boolean group) throws IOException {
		if (group) {
			return listRecordingsGrouped(in, true).keySet();
		} else {
			return listRecordingsFlat(in);
		}
	}

	public static Map<String, Set<String>> listRecordingsGrouped(Supplier<InputStream> in,
																 boolean includeSilent) throws IOException {
		return listRecordings(in, includeSilent, null);
	}

	public static Set<String> listRecordingsFlat(Supplier<InputStream> in) throws IOException {
		Set<String> keys = new HashSet<>();
		listRecordings(in, true, keys::add);
		return keys;
	}

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

	public static Audio.WaveDetailData encode(WaveDetails details, boolean includeAudio) {
		return encode(details, includeAudio ? Precision.FP32 : null);
	}

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

	public static WaveDetails decode(Audio.WaveDetailData data) {
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
		details.getSimilarities().putAll(data.getSimilaritiesMap());
		if (data.hasData()) details.setData(CollectionEncoder.decode(data.getData()));
		return details;
	}

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
