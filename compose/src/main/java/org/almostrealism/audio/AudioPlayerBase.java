/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AudioPlayerBase implements AudioPlayer {
	public static boolean enableStemsExport = false;

	private String file, uri;
	private List<String> stems;

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
	 * @deprecated URI support is no longer needed since FXMediaPlayer is deprecated.
	 *             Use {@link #AudioPlayerBase(String, List)} instead.
	 */
	@Deprecated
	public AudioPlayerBase(String file, String uri,
						   List<String> stems) {
		this.file = file;
		this.uri = uri;
		this.stems = stems;
	}

	/**
	 * @deprecated URI support is no longer needed since FXMediaPlayer is deprecated.
	 *             Audio is loaded via file path or WaveData.
	 */
	@Deprecated
	public String getUri() {
		return uri;
	}

	/**
	 * @deprecated URI support is no longer needed since FXMediaPlayer is deprecated.
	 *             Audio is loaded via file path or WaveData.
	 */
	@Deprecated
	public void setUri(String uri) {
		this.uri = uri;
	}

	public void setStems(List<String> stems) {
		this.stems = stems;
	}

	public List<String> getStems() {
		return stems;
	}

	public void setFileString(String file) {
		this.file = file;
	}

	public String getFileString() {
		return file;
	}

	public File getFile() {
		if (file.startsWith("file://")) {
			return new File(file.substring(7));
		} else {
			return new File(file);
		}
	}

	public List<File> getExportFiles() {
		if (getStems() == null || !enableStemsExport) {
			return List.of(getFile());
		} else {
			return getStems().stream().map(File::new).collect(Collectors.toList());
		}
	}

	public List<File> getExportFiles(int channel) {
		if (getStems() == null || getStems().isEmpty()) {
			return Collections.emptyList();
		}

		return List.of(new File(getStems().get(channel)));
	}
}

