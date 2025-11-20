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
 * mem.read();  // File → memory array
 *
 * // Modify data
 * mem.data[0] = 42.0;
 *
 * // Write back to file
 * mem.write();  // Memory array → file
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

	public void write() {
		if (data == null) return;

		try {
			LocalExternalMemoryProvider.writeBinary(location, this, len);
		} catch (IOException e) {
			throw new HardwareException("Unable to store external memory", e);
		}
	}

	public void restore() {
		this.data = null;
	}

	@Override
	public MemoryProvider getProvider() { return provider; }

	public void destroy() {
		this.data = null;
		this.location.delete();
		this.location = null;
	}
}
