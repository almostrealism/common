/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Abstract base class for file-based {@link AudioPlayer} implementations that support
 * optional multi-track stems export. Subclasses provide the concrete playback mechanism
 * while this class manages the file path, stems list, and export file resolution.
 */
public abstract class AudioPlayerBase implements AudioPlayer {
	/** When {@code true}, stems files are included when {@link #getExportFiles()} is called. */
	public static boolean enableStemsExport = false;

	/** Path to the primary audio file for this player. */
	private String file;

	/** Ordered list of stem file paths for multi-track audio export; may be {@code null}. */
	private List<String> stems;

	/** Creates an {@code AudioPlayerBase} with no file and no stems. */
	protected AudioPlayerBase() {
		this(null, null);
	}

	/**
	 * Creates an AudioPlayerBase with the specified file path and stems.
	 *
	 * @param file The file path
	 * @param stems List of stem file paths for multi-track audio
	 */
	public AudioPlayerBase(String file, List<String> stems) {
		this.file = file;
		this.stems = stems;
	}

	/**
	 * Sets the list of stem file paths for multi-track audio export.
	 *
	 * @param stems ordered list of stem file paths, or {@code null} to clear stems
	 */
	public void setStems(List<String> stems) {
		this.stems = stems;
	}

	/**
	 * Returns the list of stem file paths, or {@code null} if no stems are set.
	 *
	 * @return the stems list, may be {@code null}
	 */
	public List<String> getStems() {
		return stems;
	}

	/**
	 * Sets the primary audio file path.
	 *
	 * @param file the file path string, optionally prefixed with {@code "file://"}
	 */
	public void setFileString(String file) {
		this.file = file;
	}

	/**
	 * Returns the raw file path string for this player.
	 *
	 * @return the file path string
	 */
	public String getFileString() {
		return file;
	}

	/**
	 * Returns a {@link File} resolved from the configured file path string.
	 * If the path starts with {@code "file://"} the prefix is stripped before
	 * constructing the {@link File}.
	 *
	 * @return the resolved {@link File}
	 */
	public File getFile() {
		if (file.startsWith("file://")) {
			return new File(file.substring(7));
		} else {
			return new File(file);
		}
	}

	/**
	 * Returns the files to export for this player. When stems are configured and
	 * {@link #enableStemsExport} is {@code true}, the individual stem files are
	 * returned; otherwise only the primary file is returned.
	 *
	 * @return the list of export files
	 */
	public List<File> getExportFiles() {
		if (getStems() == null || !enableStemsExport) {
			return List.of(getFile());
		} else {
			return getStems().stream().map(File::new).collect(Collectors.toList());
		}
	}

	/**
	 * Returns the export files for a specific channel. If no stems are configured or
	 * the stems list is empty, an empty list is returned.
	 *
	 * @param channel the zero-based channel index
	 * @return a list containing the stem file for the specified channel, or an empty
	 *         list if no stems are available
	 */
	public List<File> getExportFiles(int channel) {
		if (getStems() == null || getStems().isEmpty()) {
			return Collections.emptyList();
		}

		return List.of(new File(getStems().get(channel)));
	}
}