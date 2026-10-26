/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.hardware.MemoryData;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

/**
 * {@link MemoryProvider} for file-based memory storage used by external process execution.
 *
 * <p>Provides {@link LocalExternalMemory} instances backed by disk files instead of RAM.
 * Data is lazily loaded from files when needed and written back on demand. An
 * {@link ExternalDataDirectory} reads the results an external process leaves in its files
 * back through {@link #read(File, MemoryData)}.</p>
 *
 * <h2>Memory Model</h2>
 *
 * <ul>
 *   <li><strong>Lazy Mapping:</strong> The file is mapped when first accessed, and values are read
 *       through the mapping rather than copied onto the Java heap</li>
 *   <li><strong>Write-Through:</strong> A write goes into the mapping, which is the file</li>
 *   <li><strong>Delegation:</strong> Can replace in-memory data with file-backed storage via {@code reassign()}</li>
 *   <li><strong>Cleanup:</strong> A file is deleted when its memory is deallocated. Reading no
 *       longer consumes it, since the values are served from it rather than copied out of it</li>
 * </ul>
 *
 * <h2>Binary File Format</h2>
 *
 * <p>All data stored as double precision (FP64), big-endian — the order
 * {@link io.almostrealism.code.Memory#getBytes} and {@link MemoryData#read(java.io.InputStream)}
 * write, which is what lets this provider map a file either of them produced:</p>
 * <pre>
 * [double_0][double_1]...[double_N]
 * </pre>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * // Allocate file-backed memory
 * LocalExternalMemoryProvider provider = new LocalExternalMemoryProvider(() -> tempDir);
 * Memory mem = provider.allocate(1000);
 *
 * // Write data to file
 * provider.setMem(mem, 0, data, 0, 1000);
 *
 * // Read back from file
 * provider.getMem(mem, 0, result, 0, 1000);
 * }</pre>
 *
 * <h2>Configuration</h2>
 *
 * <ul>
 *   <li><strong>enableLazyReading:</strong> If true, {@link #read(File, MemoryData)} maps the
 *       file instead of copying it, so values are read when accessed (default: true)</li>
 * </ul>
 *
 * @see LocalExternalMemory
 * @see ExternalDataDirectory
 */
public class LocalExternalMemoryProvider implements MemoryProvider<Memory> {
	/** If true, file reads are deferred until the data is first accessed. */
	public static boolean enableLazyReading = true;

	/** Supplier of file paths for new memory allocations, or null if this provider has none. */
	private final Supplier<File> location;

	/**
	 * Creates a provider of memory mapping only the files it is given, through
	 * {@link #allocate(File, int)} or {@link #read(File, MemoryData)}. Having no location of its
	 * own, it cannot {@link #allocate(int) allocate} empty memory.
	 */
	public LocalExternalMemoryProvider() {
		this(null);
	}

	/**
	 * Creates a file-based memory provider.
	 *
	 * @param location Supplier of file locations for memory allocation
	 */
	public LocalExternalMemoryProvider(Supplier<File> location) {
		this.location = location;
	}

	/**
	 * Returns the provider name for identification.
	 *
	 * @return "DISK" to indicate file-backed storage
	 */
	@Override
	public String getName() { return "DISK"; }

	/** Size of each stored number in bytes. */
	public static final int NUMBER_SIZE = 8;

	/**
	 * Returns the size of each number in bytes.
	 *
	 * @return 8 (FP64 double precision)
	 */
	@Override
	public int getNumberSize() { return NUMBER_SIZE; }

	/**
	 * Allocates file-backed memory at the configured location.
	 *
	 * @param size Number of doubles to allocate
	 * @return {@link LocalExternalMemory} backed by a file
	 * @throws UnsupportedOperationException if this provider maps only the files it is given
	 */
	@Override
	public Memory allocate(int size) {
		if (location == null) return MemoryProvider.super.allocate(size);
		return allocate(location.get(), size);
	}

	/**
	 * Allocates file-backed memory at a specific file location.
	 *
	 * @param location File path for the memory storage
	 * @param size Number of doubles to allocate
	 * @return {@link LocalExternalMemory} backed by the specified file
	 */
	public Memory allocate(File location, int size) {
		return new LocalExternalMemory(this, location, size);
	}

	/**
	 * Deallocates file-backed memory by destroying the backing file.
	 *
	 * @param size Size of the memory (ignored)
	 * @param mem Memory to deallocate
	 */
	@Override
	public void deallocate(int size, Memory mem) {
		((LocalExternalMemory) mem).destroy();
	}

	@Override
	public void setMem(Memory mem, int offset, Memory source, int srcOffset, int length) {
		LocalExternalMemory src = (LocalExternalMemory) source;
		LocalExternalMemory dest = (LocalExternalMemory) mem;
		for (int i = 0; i < length; i++) {
			dest.setValueAt(offset + i, src.valueAt(srcOffset + i));
		}
	}

	@Override
	public void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		LocalExternalMemory dest = (LocalExternalMemory) mem;
		for (int i = 0; i < length; i++) {
			dest.setValueAt(offset + i, source[srcOffset + i]);
		}
	}

	@Override
	public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
		LocalExternalMemory src = (LocalExternalMemory) mem;
		for (int i = 0; i < length; i++) {
			out[oOffset + i] = src.valueAt(sOffset + i);
		}
	}

	@Override
	public void destroy() {
		// TODO  Destroy all LocalExternalMemory
	}

	/**
	 * Returns whether {@link #read(File, MemoryData)} makes the file the memory it reads into,
	 * rather than copying the values out of it. A file read that way must outlive the read,
	 * since it is deleted only when that memory is deallocated.
	 *
	 * @return {@link #enableLazyReading}
	 */
	public boolean isLazyReading() {
		return enableLazyReading;
	}

	/**
	 * Reads the memory image in the given file into the given memory data.
	 *
	 * <p>When this provider {@link #isLazyReading() reads lazily}, nothing is copied: the root of
	 * the memory data is reassigned to memory of this provider mapping the file, which is then
	 * deleted when that memory is deallocated rather than when it is read. Otherwise the values
	 * are copied into the memory data, and the file is no longer needed once this returns.</p>
	 *
	 * @param src  the file holding the image, big-endian FP64
	 * @param dest the memory data to read it into
	 * @throws IOException if the file cannot be read
	 */
	public void read(File src, MemoryData dest) throws IOException {
		if (isLazyReading()) {
			MemoryData root = dest.getRootDelegate();
			root.reassign(allocate(src, root.getMemLength()));
		} else {
			try (InputStream in = new FileInputStream(src)) {
				dest.read(in);
			}
		}
	}
}
