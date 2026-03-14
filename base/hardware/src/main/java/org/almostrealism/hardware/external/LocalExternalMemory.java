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

/**
 * {@link Memory} implementation backed by a disk file for external process data exchange.
 *
 * <p>Represents a double array stored in a binary file. Data is lazily loaded into RAM
 * when first accessed and written back to disk when modified. Used by {@link ExternalInstructionSet}
 * to transfer data to/from external processes.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li><strong>Allocation:</strong> File location assigned, no data in memory</li>
 *   <li><strong>Read:</strong> File loaded into memory array on first access</li>
 *   <li><strong>Write:</strong> Memory array written to file when modified</li>
 *   <li><strong>Restore:</strong> Memory array discarded, file remains</li>
 *   <li><strong>Destroy:</strong> File deleted, memory released</li>
 * </ol>
 *
 * <h2>Memory States</h2>
 *
 * <ul>
 *   <li><strong>Unloaded:</strong> {@code data == null}, file exists on disk</li>
 *   <li><strong>Loaded:</strong> {@code data != null}, in-memory array populated</li>
 *   <li><strong>Destroyed:</strong> {@code data == null && location == null}, file deleted</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * LocalExternalMemory mem = provider.allocate(file, 1000);
 *
 * // Lazy load from file
 * mem.read();  // File to memory array
 *
 * // Modify data
 * mem.data[0] = 42.0;
 *
 * // Write back to file
 * mem.write();  // Memory array to file
 *
 * // Free memory but keep file
 * mem.restore();
 * }</pre>
 *
 * @see LocalExternalMemoryProvider
 * @see ExternalInstructionSet
 */
public class LocalExternalMemory implements Memory {
	private LocalExternalMemoryProvider provider;
	protected File location;
	protected double data[];
	private int len;

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
		if (data != null) return;

		data = new double[len];

		try {
			LocalExternalMemoryProvider.readBinary(location, data);
			location.delete();
		} catch (IOException e) {
			throw new HardwareException("Unable to retrieve external memory", e);
		}
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
		if (data == null) return;

		try {
			LocalExternalMemoryProvider.writeBinary(location, this, len);
		} catch (IOException e) {
			throw new HardwareException("Unable to store external memory", e);
		}
	}

	/**
	 * Discards the in-memory data while keeping the backing file.
	 *
	 * <p>Sets the internal data array to null, freeing memory.
	 * The file remains on disk and can be re-read later with {@link #read()}.</p>
	 */
	public void restore() {
		this.data = null;
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
		this.data = null;
		this.location.delete();
		this.location = null;
	}
}
