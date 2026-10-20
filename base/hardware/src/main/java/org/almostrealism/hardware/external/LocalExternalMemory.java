/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.external;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.HardwareException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * {@link Memory} implementation backed by a disk file for external process data exchange.
 *
 * <p>The file <em>is</em> the memory: values are read and written through a shared mapping of it,
 * so nothing is copied onto the Java heap, and a process at the other end of the exchange sees a
 * write without anything being flushed on its behalf. Used by {@link ExternalInstructionSet} to
 * transfer data to and from external processes.</p>
 *
 * <p>Values are stored as big-endian FP64, the format
 * {@link io.almostrealism.code.Memory#getBytes} and
 * {@link org.almostrealism.hardware.MemoryData#read(java.io.InputStream)} write, so a file this
 * class maps and a file written by those are the same file.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li><strong>Allocation:</strong> File location assigned, nothing mapped yet</li>
 *   <li><strong>Read:</strong> File mapped on first access, and stays mapped</li>
 *   <li><strong>Write:</strong> Values go into the mapping, so a write is already in the file</li>
 *   <li><strong>Restore:</strong> Mapping released, file remains with its values</li>
 *   <li><strong>Destroy:</strong> Mapping released, file deleted</li>
 * </ol>
 *
 * <h2>Memory States</h2>
 *
 * <ul>
 *   <li><strong>Unloaded:</strong> nothing mapped, file exists on disk</li>
 *   <li><strong>Loaded:</strong> mapped, values served straight from the file</li>
 *   <li><strong>Destroyed:</strong> {@code location == null}, file deleted</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * LocalExternalMemory mem = provider.allocate(file, 1000);
 *
 * // Map the file (also done on first access)
 * mem.read();
 *
 * // Modify data; the write lands in the file
 * provider.setMem(mem, 0, new double[] { 42.0 }, 0, 1);
 *
 * // Flush the mapping, for durability rather than visibility
 * mem.write();
 *
 * // Free memory but keep file
 * mem.restore();
 * }</pre>
 *
 * @see LocalExternalMemoryProvider
 * @see ExternalInstructionSet
 */
public class LocalExternalMemory implements Memory {
	/** Provider that owns and manages this memory instance. */
	private LocalExternalMemoryProvider provider;
	/** File on disk backing this memory. */
	protected File location;
	/** The mapping the values live in; null until first access, and again once released. */
	private MappedByteBuffer mapping;

	/** Double view of {@link #mapping}; null whenever the mapping is. */
	private DoubleBuffer values;
	/** Number of elements in this memory block. */
	private int len;

	/**
	 * Creates a file-backed memory instance of the given length.
	 *
	 * @param provider Provider that owns this instance
	 * @param location File that backs this memory
	 * @param len      Number of double elements
	 */
	protected LocalExternalMemory(LocalExternalMemoryProvider provider, File location, int len) {
		this.provider = provider;
		this.location = location;
		this.len = len;
	}

	/**
	 * Lazily loads data from the backing file into memory.
	 *
	 * <p>If data is already loaded, this method returns immediately.
	 * Otherwise, reads the binary file into the internal double array
	 * and deletes the file after reading.</p>
	 *
	 * @throws HardwareException if file read fails
	 */
	public void read() {
		values();
	}

	/**
	 * Returns the values, mapping the file on first use.
	 *
	 * <p>The mapping is shared, so a value written through it is in the file and a value the process
	 * at the other end of the exchange wrote is readable here, neither direction needing a copy. The
	 * file is created, and extended to hold this memory's values, if it does not already.</p>
	 *
	 * @return the values in the file
	 * @throws HardwareException if the file cannot be mapped
	 */
	private DoubleBuffer values() {
		if (values != null) return values;

		try (FileChannel channel = FileChannel.open(location.toPath(),
				StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0,
					(long) len * provider.getNumberSize());
			mapping.order(ByteOrder.BIG_ENDIAN);
			values = mapping.asDoubleBuffer();
		} catch (IOException e) {
			throw new HardwareException("Unable to map external memory " + location, e);
		}

		return values;
	}

	/**
	 * Reads the value at the given position from the file.
	 *
	 * @param index the value position
	 * @return the value at that position
	 */
	protected double valueAt(int index) {
		return values().get(index);
	}

	/**
	 * Writes the value at the given position into the file.
	 *
	 * @param index the value position
	 * @param value the value to store
	 */
	protected void setValueAt(int index, double value) {
		values().put(index, value);
	}

	/**
	 * Writes the in-memory data to the backing file.
	 *
	 * <p>If data is not loaded in memory, this method returns immediately.
	 * Otherwise, writes the internal double array to the binary file.</p>
	 *
	 * @throws HardwareException if file write fails
	 */
	public void write() {
		MappedByteBuffer current = mapping;
		if (current != null) current.force();
	}

	/**
	 * Discards the in-memory data while keeping the backing file.
	 *
	 * <p>Sets the internal data array to null, freeing memory.
	 * The file remains on disk and can be re-read later with {@link #read()}.</p>
	 */
	public void restore() {
		this.values = null;
		this.mapping = null;
	}

	/**
	 * Returns the memory provider that allocated this memory.
	 *
	 * @return The {@link LocalExternalMemoryProvider} instance
	 */
	@Override
	public MemoryProvider getProvider() { return provider; }

	/**
	 * Completely destroys this memory, deleting the backing file.
	 *
	 * <p>Discards in-memory data, deletes the file, and clears the location reference.
	 * After calling this method, the memory cannot be used again.</p>
	 */
	public void destroy() {
		this.values = null;
		this.mapping = null;
		this.location.delete();
		this.location = null;
	}
}
