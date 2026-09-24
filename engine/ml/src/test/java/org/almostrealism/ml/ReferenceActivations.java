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

import io.almostrealism.code.Memory;
import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MappedMemoryProvider;

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

	/** Size of the value-count header each reference file starts with. */
	private static final int HEADER_BYTES = 4;

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
	 * Creates a collection of the given shape over a reference file, reading the values through a
	 * mapping of the file rather than copying them out of it.
	 *
	 * <p>Nothing is materialized on the host: the payload is served from the operating system's page
	 * cache, and reaches a device only when a kernel requires it. These files are written by the
	 * extraction scripts and read exactly as written, so there is nothing to convert and no reason
	 * to hold a second copy of a dump that can be gigabytes.</p>
	 *
	 * @param name  the file name within the directory
	 * @param shape the shape to read the values as
	 * @return a collection reading through a mapping of the file
	 * @throws IOException if the file cannot be read
	 * @throws IllegalStateException if the file's value count does not match the shape
	 */
	public PackedCollection collection(String name, TraversalPolicy shape) throws IOException {
		File file = new File(directory, name);
		int count = count(file.toPath());

		if (count != shape.getTotalSize()) {
			throw new IllegalStateException(name + ": file has " + count
					+ " values but shape expects " + shape.getTotalSize());
		}

		Memory mem = MappedMemoryProvider.getInstance()
				.allocate(file, Precision.FP32, HEADER_BYTES, count);
		return new PackedCollection(shape, shape.getTraversalAxis(), Bytes.of(mem, count), 0);
	}

	/**
	 * Reads the value count from a reference file's header.
	 *
	 * @param path the file path
	 * @return the number of values that follow the header
	 * @throws IOException if the file cannot be read
	 */
	public static int count(Path path) throws IOException {
		try (DataInputStream in = new DataInputStream(new FileInputStream(path.toFile()))) {
			byte[] header = new byte[HEADER_BYTES];
			in.readFully(header);
			return ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
		}
	}

	/**
	 * Reads a reference file into a buffer positioned over its payload values.
	 *
	 * <p>Prefer {@link #collection(String, TraversalPolicy)} when the values are going into a
	 * collection: this copies the whole payload onto the Java heap first.</p>
	 *
	 * @param path the file path
	 * @return a little-endian buffer holding the payload values
	 * @throws IOException if the file cannot be read
	 */
	public static ByteBuffer loadBuffer(Path path) throws IOException {
		try (DataInputStream in = new DataInputStream(new FileInputStream(path.toFile()))) {
			byte[] header = new byte[HEADER_BYTES];
			in.readFully(header);
			int count = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
			byte[] payload = new byte[count * Precision.FP32.bytes()];
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
