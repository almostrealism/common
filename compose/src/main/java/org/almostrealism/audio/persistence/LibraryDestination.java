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

public class LibraryDestination implements ConsoleFeatures {
	public static final String TEMP = "temp";
	public static final String SAMPLES = "Samples";

	private final String prefix;
	private int index;
	private final boolean append;
	private final List<String> temporaryFiles;

	public LibraryDestination(String prefix) {
		this(prefix, false);
	}

	public LibraryDestination(String prefix, boolean append) {
		if (prefix.contains("/")) {
			this.prefix = prefix;
		} else {
			this.prefix = SystemUtils.getLocalDestination(prefix);
		}

		this.append = append;
		this.temporaryFiles = new ArrayList<>();
	}

	protected String nextFile() {
		return prefix + "_" + index++ + ".bin";
	}

	protected String nextTemporaryFile() {
		String tempFile = getTemporaryPath().resolve("lib_" +
				System.currentTimeMillis() +
				"_" + temporaryFiles.size() + ".tmp").toString();
		temporaryFiles.add(tempFile);
		return tempFile;
	}

	protected Iterator<String> files() {
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

	public void load(AudioLibrary library) {
		try {
			AudioLibraryPersistence.loadLibrary(library, in());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void save(AudioLibrary library) {
		try (Writer writer = out()) {
			AudioLibraryPersistence.saveLibrary(library, writer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

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

	public void save(Audio.AudioLibraryData data) {
		try (Writer writer = out()) {
			data.writeTo(writer.get());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public Path getTemporaryPath() {
		Path p = SystemUtils.getLocalDestination().resolve(TEMP);
		return SystemUtils.ensureDirectoryExists(p);
	}

	public File getTemporaryFile(String key, String extension) {
		if (key.contains("/")) {
			key = Base64.getEncoder().encodeToString(key.getBytes());
		}

		return temporary(getTemporaryPath().resolve(key + "." + extension).toFile());
	}

	public OutputStream getTemporaryDestination(String key, String extension) {
		try {
			return new FileOutputStream(getTemporaryFile(key, extension));
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	public File getTemporaryWave(String key, WaveData data) {
		File f = getTemporaryFile(key, "wav");
		return (f.exists() || data.save(f)) ? f : null;
	}

	public void delete() {
		files().forEachRemaining(f -> new File(f).delete());
		discardTemporary(); // Also clean up any pending temporary files
		index = 0;
	}

	public void cleanup() {
		try {
			clean(getTemporaryPath());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected File temporary(File f) {
		f.deleteOnExit();
		return f;
	}

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