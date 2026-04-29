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

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Manages the file-based storage for an audio library, supporting batched
 * sequential write and read of protobuf files at a given path prefix.
 * Files are written to temporary locations first and atomically moved to
 * their final destinations on {@link Writer#close()}.
 */
public class LibraryDestination implements ConsoleFeatures {
	/** Subdirectory name used for temporary files. */
	public static final String TEMP = "temp";

	/** Default subdirectory name for audio sample files. */
	public static final String SAMPLES = "Samples";

	/** Path prefix for all batch files (e.g. {@code /path/to/library}). */
	private final String prefix;

	/** Monotonically increasing index used to generate batch file names. */
	private int index;

	/** When {@code true}, new files are appended directly without using temporary files. */
	private final boolean append;

	/** List of temporary file paths pending a flush to their final destinations. */
	private final List<String> temporaryFiles;

	/**
	 * Creates a destination that writes to files named {@code prefix_N.bin}.
	 *
	 * @param prefix the path prefix for batch files; may be a bare name or a full path
	 */
	public LibraryDestination(String prefix) {
		this(prefix, false);
	}

	/**
	 * Creates a destination with the given path prefix and append mode.
	 *
	 * @param prefix the path prefix for batch files
	 * @param append if {@code true}, files are written directly to their final paths;
	 *               if {@code false}, files are staged as temporary and moved on close
	 */
	public LibraryDestination(String prefix, boolean append) {
		if (prefix.contains("/")) {
			this.prefix = prefix;
		} else {
			this.prefix = SystemUtils.getLocalDestination(prefix);
		}

		this.append = append;
		this.temporaryFiles = new ArrayList<>();
	}

	/**
	 * Returns the next batch file path and increments the index.
	 *
	 * @return the path of the next batch file
	 */
	protected String nextFile() {
		return prefix + "_" + index++ + ".bin";
	}

	/**
	 * Creates and returns the path of the next temporary file, registering it
	 * in the list of pending temporary files.
	 *
	 * @return the temporary file path
	 */
	protected String nextTemporaryFile() {
		String tempFile = getTemporaryPath().resolve("lib_" +
				System.currentTimeMillis() +
				"_" + temporaryFiles.size() + ".tmp").toString();
		temporaryFiles.add(tempFile);
		return tempFile;
	}

	/**
	 * Returns an iterator over all existing batch file paths at this destination's prefix.
	 *
	 * @return an iterator of batch file paths
	 */
	public Iterator<String> files() {
		return new Iterator<>() {
			int idx = 0;

			@Override
			public boolean hasNext() {
				return new File(prefix + "_" + idx + ".bin").exists();
			}

			@Override
			public String next() {
				return prefix + "_" + idx++ + ".bin";
			}
		};
	}

	/**
	 * Returns a supplier of input streams, one per batch file, in order.
	 * Returns {@code null} when all batch files have been consumed.
	 *
	 * @return a supplier of batch file input streams
	 */
	public Supplier<InputStream> in() {
		Iterator<String> all = files();

		return () -> {
			try {
				if (!all.hasNext())
					return null;

				return new FileInputStream(all.next());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};
	}

	/**
	 * Opens a new {@link Writer} for writing batch files to this destination.
	 *
	 * @return a new writer
	 */
	public Writer out() { return new Writer(); }

	/**
	 * Flushes all temporary files to their final destinations by moving them
	 * from temporary locations to the actual expected file paths.
	 * 
	 * @throws IOException if moving any file fails
	 */
	public void flush() throws IOException {
		int finalIndex = 0;
		
		for (String tempFile : temporaryFiles) {
			File temp = new File(tempFile);
			if (!temp.exists()) {
				continue;
			}
			
			String finalFile = prefix + "_" + finalIndex++ + ".bin";
			File finalDest = new File(finalFile);
			
			// Ensure parent directories exist
			if (finalDest.getParentFile() != null) {
				finalDest.getParentFile().mkdirs();
			}
			
			// Move temporary file to final destination
			Files.move(temp.toPath(), finalDest.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
		}
		
		// Update the index to reflect the actual number of files created
		index = finalIndex;
		
		// Clear the temporary files list since they've been moved
		temporaryFiles.clear();
	}

	/**
	 * Discards all temporary files without moving them to final destinations.
	 * This can be used to cancel an operation before flushing.
	 */
	public void discardTemporary() {
		for (String tempFile : temporaryFiles) {
			new File(tempFile).delete();
		}

		temporaryFiles.clear();
	}

	/**
	 * Loads library data from this destination into the given {@link AudioLibrary},
	 * without loading similarity data, and configures a details loader for on-demand access.
	 *
	 * @param library the library to populate
	 */
	public void load(AudioLibrary library) {
		try {
			AudioLibraryPersistence.loadLibrary(library, in(), false);
			library.setDetailsLoader(AudioLibraryPersistence.createDetailsLoader(prefix));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Migrates old-format library data to a {@link ProtobufWaveDetailsStore}
	 * if necessary, then returns the store.
	 *
	 * <p>If old batch files exist ({@code PREFIX_0.bin}) and the store directory
	 * is empty, migration runs first. The returned store should be passed to the
	 * {@link org.almostrealism.audio.AudioLibrary} constructor so that all
	 * lookups use the indexed store instead of the legacy full-scan path.</p>
	 *
	 * @param storeDir directory for the {@link ProtobufWaveDetailsStore}
	 * @return a ready-to-use store, never null
	 */
	public ProtobufWaveDetailsStore migrateAndOpenStore(File storeDir) {
		boolean oldDataExists = new File(prefix + "_0.bin").exists();
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);
		boolean storeEmpty = store.size() == 0;

		if (oldDataExists && storeEmpty) {
			store.close();
			AudioLibraryMigration.migrate(
					Path.of(prefix),
					storeDir.toPath());
			store = new ProtobufWaveDetailsStore(storeDir);
		}

		return store;
	}

	/**
	 * Saves the given {@link AudioLibrary} to this destination's batch files.
	 *
	 * @param library the library to save
	 */
	public void save(AudioLibrary library) {
		try (Writer writer = out()) {
			AudioLibraryPersistence.saveLibrary(library, writer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Loads and returns all {@link Audio.AudioLibraryData} batches from this destination.
	 *
	 * @return list of deserialized library data batches
	 */
	public List<Audio.AudioLibraryData> load() {
		Supplier<InputStream> in = in();
		InputStream input = in.get();

		List<Audio.AudioLibraryData> result = new ArrayList<>();

		try {
			while (input != null) {
				result.add(Audio.AudioLibraryData.newBuilder().mergeFrom(input).build());
				input = in.get();
			}

			return result;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Saves a single {@link Audio.AudioLibraryData} batch to this destination.
	 *
	 * @param data the data to save
	 */
	public void save(Audio.AudioLibraryData data) {
		try (Writer writer = out()) {
			data.writeTo(writer.get());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns the path to the temporary directory used for staging files before flush.
	 *
	 * @return the temporary directory path, creating it if necessary
	 */
	public Path getTemporaryPath() {
		Path p = SystemUtils.getLocalDestination().resolve(TEMP);
		return SystemUtils.ensureDirectoryExists(p);
	}

	/**
	 * Returns a temporary file for the given key and file extension.
	 * If the key contains slashes, it is Base64-encoded for safe use as a filename.
	 *
	 * @param key       the logical key for the file
	 * @param extension the file extension (without the leading dot)
	 * @return the temporary file
	 */
	public File getTemporaryFile(String key, String extension) {
		if (key.contains("/")) {
			key = Base64.getEncoder().encodeToString(key.getBytes());
		}

		return temporary(getTemporaryPath().resolve(key + "." + extension).toFile());
	}

	/**
	 * Opens and returns an output stream to a temporary file for the given key and extension.
	 *
	 * @param key       the logical key for the file
	 * @param extension the file extension (without the leading dot)
	 * @return an output stream to the temporary file
	 */
	public OutputStream getTemporaryDestination(String key, String extension) {
		try {
			return new FileOutputStream(getTemporaryFile(key, extension));
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns a temporary WAV file for the given key, saving the wave data if it does not
	 * already exist.
	 *
	 * @param key  the logical key for the file
	 * @param data the wave data to save if the file does not exist
	 * @return the temporary WAV file, or {@code null} if the file could not be saved
	 */
	public File getTemporaryWave(String key, WaveData data) {
		File f = getTemporaryFile(key, "wav");
		return (f.exists() || data.save(f)) ? f : null;
	}

	/**
	 * Deletes all batch files at this destination and any pending temporary files.
	 */
	public void delete() {
		files().forEachRemaining(f -> new File(f).delete());
		discardTemporary(); // Also clean up any pending temporary files
		index = 0;
	}

	/**
	 * Removes all files from the temporary directory.
	 */
	public void cleanup() {
		try {
			clean(getTemporaryPath());
		} catch (Exception e) {
			warn(e.getMessage(), e);
		}
	}

	/**
	 * Registers the given file for deletion on JVM exit and returns it.
	 *
	 * @param f the file to mark for deletion on exit
	 * @return the file
	 */
	protected File temporary(File f) {
		f.deleteOnExit();
		return f;
	}

	/**
	 * Deletes all files in the given directory. Refuses to operate on the filesystem
	 * root or paths shorter than three characters as a safety guard.
	 *
	 * @param directory the directory to clean
	 * @throws IllegalArgumentException if the path is too short or is the root
	 */
	protected void clean(Path directory) {
		Path root = directory.getRoot();
		if (root != null && root.equals(directory)) {
			throw new IllegalArgumentException();
		}

		File dir = directory.toFile();
		if (!dir.isDirectory() || dir.getPath().length() < 3) {
			throw new IllegalArgumentException();
		}

		for (File f : Objects.requireNonNull(dir.listFiles())) {
			f.delete();
		}
	}

	/**
	 * Returns the default library root path.
	 * On macOS: ~/Music/Samples
	 * On other platforms: ~/RingsAudioLibrary
	 *
	 * @return the default library root path, creating it if necessary
	 */
	public static Path getDefaultLibraryRoot() {
		Path home = Path.of(SystemUtils.getHome());
		Path libraryPath;
		if (SystemUtils.isMacOS()) {
			libraryPath = home.resolve("Music").resolve(SAMPLES);
		} else {
			libraryPath = home.resolve("RingsAudioLibrary");
		}
		return SystemUtils.ensureDirectoryExists(libraryPath);
	}

	/**
	 * A write session that supplies output streams for successive batch files and
	 * flushes temporary files to their final destinations on close.
	 */
	public class Writer implements Supplier<OutputStream>, AutoCloseable {
		@Override
		public OutputStream get() {
			try {
				if (append) {
					// In append mode, write directly to final destination
					File f = new File(nextFile());
					while (append && f.exists()) {
						f = new File(nextFile());
					}
					return new FileOutputStream(f);
				} else {
					// In non-append mode, write to temporary files first
					File f = new File(nextTemporaryFile());
					return new FileOutputStream(f);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void close() throws IOException { flush(); }
	}
}