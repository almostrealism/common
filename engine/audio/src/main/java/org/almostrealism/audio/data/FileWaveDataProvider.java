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

package org.almostrealism.audio.data;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.io.Console;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A wave data provider that loads audio from WAV files on the filesystem.
 *
 * <p>FileWaveDataProvider is the primary implementation for file-based audio loading.
 * It lazily loads file metadata (sample rate, frame count, channels) on first access
 * and caches the loaded {@link WaveData} for subsequent requests. File identity is
 * determined by MD5 hash of the file contents.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Lazy loading of file metadata and audio data</li>
 *   <li>Automatic caching of loaded data per hardware context</li>
 *   <li>MD5-based content identification for deduplication</li>
 *   <li>Corrupt file tracking to avoid repeated load failures</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FileWaveDataProvider provider = new FileWaveDataProvider("/path/to/audio.wav");
 * WaveData data = provider.get();
 * double duration = provider.getDuration();
 * int sampleRate = provider.getSampleRate();
 * }</pre>
 *
 * @see WaveDataProvider
 * @see WaveData
 */
public class FileWaveDataProvider extends WaveDataProviderAdapter implements PathResource {
	/** Paths of files that failed to load; these are skipped on subsequent access attempts. */
	private static final List<String> corruptFiles = new ArrayList<>();

	/** Cache mapping file paths to their MD5-based identifier strings. */
	private static final Map<String, String> identifiers = new HashMap<>(); // TODO  Use FrequencyCache

	/** Cached sample rate in Hz; lazily loaded from the WAV file header. */
	private Integer sampleRate;

	/** Cached total frame count; lazily loaded from the WAV file header. */
	private Integer count;

	/** Cached channel count; lazily loaded from the WAV file header. */
	private Integer channels;

	/** Cached duration in seconds; lazily loaded from the WAV file header. */
	private Double duration;

	/** Filesystem path to the WAV file. */
	private String resourcePath;

	/** Creates an uninitialized FileWaveDataProvider; resourcePath must be set before use. */
	public FileWaveDataProvider() { }

	/**
	 * Creates a FileWaveDataProvider for the given file.
	 *
	 * @param file the WAV file to load
	 */
	public FileWaveDataProvider(File file) {
		this(file.getPath());
	}

	/**
	 * Creates a FileWaveDataProvider for the given file path.
	 *
	 * @param resourcePath filesystem path to the WAV file; must not be null
	 */
	public FileWaveDataProvider(String resourcePath) {
		if (resourcePath == null) {
			throw new IllegalArgumentException();
		} else if (resourcePath.contains(":")) {
			warn("Potentially invalid resource path (" + resourcePath + ")",
					new RuntimeException());
		}

		setResourcePath(resourcePath);
	}

	@Override
	public String getKey() { return getResourcePath(); }

	@Override
	public String getResourcePath() {
		return resourcePath;
	}

	/**
	 * Sets the filesystem path to the WAV file, clearing the cached key.
	 *
	 * @param resourcePath the new file path
	 */
	public void setResourcePath(String resourcePath) {
		clearKey(resourcePath);
		this.resourcePath = resourcePath;
	}

	@Override
	public String getIdentifier() {
		return identifiers.computeIfAbsent(getKey(), k -> {
			try (InputStream is = Files.newInputStream(Paths.get(getKey()))) {
				return DigestUtils.md5Hex(is);
			} catch (IOException e) {
				return null;
			}
		});
	}

	@Override
	public int getSampleRate() {
		if (corruptFiles.contains(getResourcePath())) return 0;

		if (sampleRate == null) {
			try (WavFile w = WavFile.openWavFile(new File(resourcePath))) {
				long count = w.getNumFrames();
				if (count > Integer.MAX_VALUE) throw new UnsupportedOperationException();
				if (w.getSampleRate() > Integer.MAX_VALUE) throw new UnsupportedOperationException();
				this.sampleRate = (int) w.getSampleRate();
			} catch (IOException e) {
				corruptFiles.add(getResourcePath());
				throw new RuntimeException(e);
			}
		}

		return sampleRate;
	}

	@Override
	public long getCountLong() {
		if (corruptFiles.contains(getResourcePath())) return 0;

		if (count == null) {
			try (WavFile w = WavFile.openWavFile(new File(resourcePath))) {
				if (w.getNumFrames() > Integer.MAX_VALUE) throw new UnsupportedOperationException();
				this.count = (int) w.getNumFrames();
			} catch (IOException e) {
				corruptFiles.add(getResourcePath());
				throw new RuntimeException(e);
			}
		}

		return count;
	}

	@Override
	public double getDuration() {
		if (corruptFiles.contains(getResourcePath())) return 0.0;

		if (duration == null) {
			try (WavFile w = WavFile.openWavFile(new File(resourcePath))) {
				this.duration = w.getDuration();
			} catch (IOException e) {
				corruptFiles.add(getResourcePath());
				throw new RuntimeException(e);
			}
		}

		return duration;
	}

	@Override
	public int getChannelCount() {
		if (corruptFiles.contains(getResourcePath())) return 0;

		if (channels == null) {
			try (WavFile w = WavFile.openWavFile(new File(resourcePath))) {
				this.channels = w.getNumChannels();
			} catch (IOException e) {
				corruptFiles.add(getResourcePath());
				throw new RuntimeException(e);
			}
		}

		return channels;
	}

	/**
	 * Loads the WAV file and returns its audio data.
	 *
	 * @return the loaded WaveData, or null if the file is marked corrupt
	 * @throws RuntimeException wrapping any IOException encountered during loading
	 */
	@Override
	protected WaveData load() {
		if (corruptFiles.contains(getResourcePath())) return null;

		try {
			if (WaveOutput.enableVerbose)
				log("Loading " + resourcePath);

			return WaveData.load(new File(resourcePath));
		} catch (IOException e) {
			corruptFiles.add(getResourcePath());
			throw new RuntimeException(e);
		}
	}

	@Override
	public Console console() {
		return CellFeatures.console;
	}
}
