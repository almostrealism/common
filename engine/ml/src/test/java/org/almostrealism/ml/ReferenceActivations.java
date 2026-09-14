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

package org.almostrealism.ml;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * A directory of reference activations written by the extraction scripts'
 * {@code save_reference_output} (each file {@code [uint32 count][float32 ...]}, little-endian),
 * as consumed by the gated numerical-parity tests. The directory is located among candidate
 * paths at test time and is never committed.
 */
public class ReferenceActivations {

	/** The directory holding the reference files. */
	private final File directory;

	/**
	 * Wraps a directory of reference files.
	 *
	 * @param directory the directory
	 */
	public ReferenceActivations(File directory) {
		this.directory = directory;
	}

	/**
	 * The directory holding the reference files.
	 *
	 * @return the directory
	 */
	public File getDirectory() { return directory; }

	/**
	 * Reads one reference file as a flat array of values.
	 *
	 * @param name the file name within the directory
	 * @return the values
	 * @throws IOException if the file cannot be read
	 */
	public float[] load(String name) throws IOException {
		return load(new File(directory, name).toPath());
	}

	/**
	 * Reads a reference file as a flat array of values.
	 *
	 * @param path the file path
	 * @return the values
	 * @throws IOException if the file cannot be read
	 */
	public static float[] load(Path path) throws IOException {
		ByteBuffer buffer = loadBuffer(path);
		float[] values = new float[buffer.remaining() / 4];
		for (int i = 0; i < values.length; i++) {
			values[i] = buffer.getFloat(i * 4);
		}
		return values;
	}

	/**
	 * Reads a reference file into a buffer positioned over its payload values.
	 *
	 * @param path the file path
	 * @return a little-endian buffer holding the payload values
	 * @throws IOException if the file cannot be read
	 */
	public static ByteBuffer loadBuffer(Path path) throws IOException {
		try (DataInputStream in = new DataInputStream(new FileInputStream(path.toFile()))) {
			byte[] header = new byte[4];
			in.readFully(header);
			int count = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
			byte[] payload = new byte[count * 4];
			in.readFully(payload);
			return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
		}
	}

	/**
	 * Returns the first directory among {@code candidates} that exists and contains
	 * {@code marker}, or {@code null} if none does.
	 *
	 * @param candidates candidate directory paths (entries may be {@code null})
	 * @param marker     a file that must exist within the directory
	 * @return the resolved directory, or {@code null}
	 */
	public static File firstExisting(String[] candidates, String marker) {
		for (String candidate : candidates) {
			if (candidate == null) continue;
			File dir = new File(candidate);
			if (dir.isDirectory() && new File(dir, marker).exists()) {
				return dir;
			}
		}
		return null;
	}

	/**
	 * Locates a reference directory among the candidates.
	 *
	 * @param candidates candidate directory paths (entries may be {@code null})
	 * @param marker     a file that must exist within the directory
	 * @return the references, or {@code null} when no candidate holds the marker
	 */
	public static ReferenceActivations locate(String[] candidates, String marker) {
		File dir = firstExisting(candidates, marker);
		return dir == null ? null : new ReferenceActivations(dir);
	}
}
