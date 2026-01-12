/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.persistence.LibraryDestination;
import org.almostrealism.io.Console;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A container for audio data that can be referenced by file path, key, or
 * in-memory wave data.
 *
 * <p>{@code SoundData} provides a unified representation for audio samples
 * that may exist as files on disk, in-memory data, or both. It supports:</p>
 * <ul>
 *   <li>File-based audio with optional stem files</li>
 *   <li>In-memory {@link WaveData} with library context</li>
 *   <li>Key-based identification for deduplication</li>
 *   <li>Conversion to {@link WaveDataProvider} for audio processing</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>From a file:</h3>
 * <pre>{@code
 * SoundData sound = new SoundData("/path/to/audio.wav");
 * WaveDataProvider provider = sound.toProvider();
 * }</pre>
 *
 * <h3>From a NoteAudioProvider:</h3>
 * <pre>{@code
 * SoundData sound = SoundData.create(library, noteAudioProvider);
 * if (sound != null) {
 *     // Use the sound data...
 * }
 * }</pre>
 *
 * @see SoundDataHub
 * @see SoundDataListener
 * @see WaveData
 * @see WaveDataProvider
 */
public class SoundData {
	private LibraryDestination library;
	private String file;
	private List<String> stemFiles;

	private String key;
	private WaveData data;

	/**
	 * Creates an empty sound data container.
	 */
	public SoundData() { this(null); }

	/**
	 * Creates sound data from a file path.
	 *
	 * @param file the path to the audio file
	 */
	public SoundData(String file) {
		this(file, (List<String>) null);
	}

	/**
	 * Creates sound data from a file path with optional stem files.
	 *
	 * @param file      the path to the main audio file
	 * @param stemFiles optional list of stem file paths
	 */
	public SoundData(String file, List<String> stemFiles) {
		setFile(file);
		setStemFiles(stemFiles);
	}

	/**
	 * Creates sound data from in-memory wave data with library context.
	 *
	 * @param library the library destination for temporary file creation
	 * @param key     the unique identifier for this audio
	 * @param data    the in-memory wave data
	 */
	protected SoundData(LibraryDestination library,
						String key, WaveData data) {
		setLibrary(library);
		setKey(key);
		setData(data);
	}

	/**
	 * Returns the library destination for temporary file management.
	 *
	 * @return the library destination, or {@code null} if not set
	 */
	public LibraryDestination getLibrary() { return library; }

	/**
	 * Sets the library destination for temporary file management.
	 *
	 * @param library the library destination
	 */
	public void setLibrary(LibraryDestination library) { this.library = library; }

	/**
	 * Returns the file path for this audio data.
	 *
	 * <p>If no file path is set but in-memory data exists, a temporary file
	 * will be created via the library destination.</p>
	 *
	 * @return the file path, or {@code null} if unavailable
	 */
	public String getFile() {
		if (file == null && getData() != null) {
			file = Optional.ofNullable(library.getTemporaryWave(getKey(), getData()))
					.map(File::getAbsolutePath)
					.orElse(null);
		}

		return file;
	}

	/**
	 * Sets the file path for this audio data.
	 *
	 * @param file the file path
	 */
	public void setFile(String file) { this.file = file; }

	/**
	 * Returns the list of stem file paths associated with this audio.
	 *
	 * <p>Stems are individual component tracks (e.g., vocals, drums) that
	 * make up the main audio.</p>
	 *
	 * @return the list of stem file paths, or {@code null} if none
	 */
	public List<String> getStemFiles() { return stemFiles; }

	/**
	 * Sets the list of stem file paths.
	 *
	 * @param stemFiles the stem file paths
	 */
	public void setStemFiles(List<String> stemFiles) { this.stemFiles = stemFiles; }

	/**
	 * Returns the unique identifier for this audio data.
	 *
	 * @return the key, or {@code null} if not set
	 */
	public String getKey() { return key; }

	/**
	 * Sets the unique identifier for this audio data.
	 *
	 * @param key the key
	 */
	public void setKey(String key) { this.key = key; }

	/**
	 * Returns the in-memory wave data.
	 *
	 * @return the wave data, or {@code null} if not available in memory
	 */
	public WaveData getData() { return data; }

	/**
	 * Sets the in-memory wave data.
	 *
	 * @param data the wave data
	 */
	public void setData(WaveData data) { this.data = data; }

	/**
	 * Creates a {@link WaveDataProvider} for accessing this audio data.
	 *
	 * @return a file-based wave data provider
	 */
	public WaveDataProvider toProvider() {
		return new FileWaveDataProvider(getFile());
	}

	/**
	 * Compares this sound data with another for equality.
	 *
	 * <p>If both objects have keys, comparison is based on key and stem files.
	 * Otherwise, comparison is based on file path.</p>
	 *
	 * @param o the object to compare with
	 * @return {@code true} if equal
	 */
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SoundData soundData)) return false;

		if (getKey() != null && soundData.getKey() != null) {
			return Objects.equals(getKey(), soundData.getKey()) &&
					Objects.equals(getStemFiles(), soundData.getStemFiles());
		}

		return Objects.equals(getFile(), soundData.getFile());
	}

	/**
	 * Returns the hash code for this sound data.
	 *
	 * <p>Hash code is based on key if available, otherwise on file path.</p>
	 *
	 * @return the hash code
	 */
	@Override
	public int hashCode() {
		return getKey() == null ? Objects.hash(getFile()) : Objects.hashCode(getKey());
	}

	/**
	 * Creates sound data from a wave data provider.
	 *
	 * <p>If the provider is a {@link FileWaveDataProvider}, the file path is extracted.
	 * Otherwise, only the key is preserved.</p>
	 *
	 * @param provider the wave data provider
	 * @return a new SoundData instance, or {@code null} if provider is null
	 */
	public static SoundData create(WaveDataProvider provider) {
		if (provider == null) {
			return null;
		}

		if (provider instanceof FileWaveDataProvider) {
			return new SoundData(((FileWaveDataProvider) provider).getResourcePath());
		}

		// TODO  There are probable better solutions for other providers
		return new SoundData(null, provider.getKey(), null);
	}

	/**
	 * Creates sound data from a note audio provider with library context.
	 *
	 * <p>This method validates that the audio's sample rate matches the system
	 * sample rate ({@link OutputLine#sampleRate}). If the sample rates don't match,
	 * a warning is logged and {@code null} is returned.</p>
	 *
	 * @param library the library destination for temporary file management
	 * @param note    the note audio provider
	 * @return a new SoundData instance, or {@code null} if the audio is invalid
	 */
	public static SoundData create(LibraryDestination library, NoteAudioProvider note) {
		if (note.getProvider() == null) {
			return null;
		}

		try {
			WaveData data = note.getProvider().get();
			if (data == null) return null;

			if (data.getSampleRate() == OutputLine.sampleRate) {
				return new SoundData(library, note.getProvider().getKey(), data);
			} else {
				Console.root().features(SoundData.class)
						.warn("Sample rate of " + data.getSampleRate() +
							" does not match required sample rate of " + OutputLine.sampleRate);
				return null;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Creates sound data from library context, key, and wave data.
	 *
	 * @param library the library destination
	 * @param key     the unique identifier
	 * @param data    the wave data
	 * @return a new SoundData instance
	 */
	public static SoundData create(LibraryDestination library, String key, WaveData data) {
		return new SoundData(library, key, data);
	}
}
