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

package org.almostrealism.hardware.mem;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * A read-only mapping of one file, shared by every piece of memory reading
 * from it.
 *
 * <p>A file is mapped once however many ranges of it are being read: a store
 * that keeps many records in one file would otherwise map that file once per
 * record, spending address space and descriptors on ranges that already share
 * a mapping. The mapping is closed when the last reader releases it.</p>
 *
 * <p>The mapping counts its readers rather than tracking their identities, so
 * a reader must {@link #release()} exactly once for every {@link #of} that
 * handed it out. Whatever holds the mapping owns that pairing.</p>
 */
public class FileMapping {
	/** Mappings currently held, keyed by file path and byte order. */
	private static final Map<String, FileMapping> mappings = new HashMap<>();

	/** The mapped file, retained for reporting. */
	private final File file;

	/** The mapping, or {@code null} once every reader has released it. */
	private MappedByteBuffer buffer;

	/** Number of readers currently holding this mapping. */
	private int readers;

	/**
	 * Maps the given file in its entirety, read-only.
	 *
	 * @param file  the file to map
	 * @param order byte order the file's values are stored in
	 * @throws UncheckedIOException if the file cannot be mapped
	 */
	protected FileMapping(File file, ByteOrder order) {
		this.file = file;

		try (FileChannel channel = FileChannel.open(file.toPath(),
				StandardOpenOption.READ)) {
			long size = channel.size();

			if (size > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(file + " is " + size +
						" bytes; a single mapping addresses at most " +
						Integer.MAX_VALUE + ". Read it as several files, or " +
						"as several mappings of ranges within it.");
			}

			this.buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
			this.buffer.order(order);
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to map " + file, e);
		}

		this.readers = 1;
	}

	/**
	 * Returns the mapping of the given file, creating it if this is the first
	 * reader and recording another reader if it is not.
	 *
	 * <p>Every caller reading one file shares one mapping, so a store holding
	 * many records in a file spends one mapping on it rather than one per
	 * record. The caller must {@link #release()} exactly once when finished.</p>
	 *
	 * @param file  the file to read
	 * @param order byte order the file's values are stored in
	 * @return the mapping to read through
	 */
	public static synchronized FileMapping of(File file, ByteOrder order) {
		String key = file.getAbsolutePath() + ":" + order;
		FileMapping existing = mappings.get(key);

		if (existing != null && existing.isMapped()) {
			existing.retain();
			return existing;
		}

		FileMapping created = new FileMapping(file, order);
		mappings.put(key, created);
		return created;
	}

	/**
	 * Returns how many files are currently mapped.
	 *
	 * <p>A mapping costs address space and a descriptor whatever is being read
	 * through it, so this is the figure to look at when either is under
	 * pressure. Many readers of one file count once.</p>
	 *
	 * @return the number of mappings held
	 */
	public static synchronized int getMappedFileCount() {
		mappings.values().removeIf(mapping -> !mapping.isMapped());
		return mappings.size();
	}

	/** Returns the mapped file. */
	public File getFile() { return file; }

	/** Returns the number of readers currently holding this mapping. */
	public synchronized int getReaders() { return readers; }

	/** Returns whether this mapping is still held by at least one reader. */
	public synchronized boolean isMapped() { return buffer != null; }

	/**
	 * Returns the mapping to read from.
	 *
	 * @return the mapped bytes
	 * @throws IllegalStateException if every reader has released this mapping
	 */
	public ByteBuffer buffer() {
		ByteBuffer current = buffer;

		if (current == null) {
			throw new IllegalStateException("Mapping of " + file + " has been released");
		}

		return current;
	}

	/** Records another reader of this mapping. */
	private synchronized void retain() {
		if (buffer == null) {
			throw new IllegalStateException("Mapping of " + file + " has been released");
		}

		readers++;
	}

	/**
	 * Records that one reader is finished, releasing the mapping when it was
	 * the last.
	 *
	 * <p>Releasing drops the reference; the operating system reclaims the
	 * mapping itself once the buffer is collected. Nothing is written back, so
	 * a mapping that outlives its release costs address space and never
	 * correctness.</p>
	 *
	 * @return whether this release was the last
	 */
	public synchronized boolean release() {
		if (buffer == null) return false;

		readers--;
		if (readers > 0) return false;

		buffer = null;
		return true;
	}
}
