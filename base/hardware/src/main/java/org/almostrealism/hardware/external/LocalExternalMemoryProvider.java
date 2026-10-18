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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@link MemoryProvider} for file-based memory storage used by external process execution.
 *
 * <p>Provides {@link LocalExternalMemory} instances backed by disk files instead of RAM.
 * Data is lazily loaded from files when needed and written back on demand. Used by
 * {@link ExternalInstructionSet} to transfer data to/from external processes.</p>
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
 *   <li><strong>enableLazyReading:</strong> If true, delay file reads until data accessed (default: true)</li>
 * </ul>
 *
 * @see LocalExternalMemory
 * @see ExternalInstructionSet
 */
public class LocalExternalMemoryProvider implements MemoryProvider<Memory> {
	/** If true, file reads are deferred until the data is first accessed. */
	public static boolean enableLazyReading = true;

	/** Shared provider instance with a null location, used for lazy-read reassignment. */
	private static LocalExternalMemoryProvider local = new LocalExternalMemoryProvider(null);

	/** Supplier of file paths for new memory allocations. */
	private Supplier<File> location;

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
	 */
	@Override
	public Memory allocate(int size) {
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
	 * Reads binary data from numbered files in a directory into the corresponding memory data arguments.
	 *
	 * @param dest Directory containing numbered argument files
	 * @param args Memory data arguments to populate
	 * @throws IOException If any file cannot be read
	 */
	protected static void readData(File dest, MemoryData[] args) throws IOException {
		for (int i = 0; i < args.length; i++) {
			readBinary(new File(dest, String.valueOf(i)), args[i]);
		}
	}

	/**
	 * Writes memory data arguments to numbered binary files in a directory.
	 *
	 * @param dest Directory to write argument files into
	 * @param args Memory data arguments to write
	 * @throws IOException If any file cannot be written
	 */
	protected static void writeData(File dest, MemoryData[] args) throws IOException {
		for (int i = 0; i < args.length; i++) {
			writeBinary(new File(dest, String.valueOf(i)), args[i]);
		}
	}

	/**
	 * Writes a binary file containing the memory lengths of the given arguments.
	 *
	 * @param dest Directory to write the sizes file into
	 * @param args Memory data arguments whose lengths to record
	 * @throws IOException If the file cannot be written
	 */
	protected static void writeSizes(File dest, MemoryData[] args) throws IOException {
		writeBinary(new File(dest, "sizes"), Stream.of(args).mapToInt(MemoryData::getMemLength).toArray());
	}

	/**
	 * Writes a binary file containing the memory offsets of the given arguments.
	 *
	 * @param dest Directory to write the offsets file into
	 * @param args Memory data arguments whose offsets to record
	 * @throws IOException If the file cannot be written
	 */
	protected static void writeOffsets(File dest, MemoryData[] args) throws IOException {
		writeBinary(new File(dest, "offsets"), Stream.of(args).mapToInt(MemoryData::getOffset).toArray());
	}

	/**
	 * Writes a binary file containing the given count value.
	 *
	 * @param dest Directory to write the count file into
	 * @param count Count value to write
	 * @throws IOException If the file cannot be written
	 */
	protected static void writeCount(File dest, int count) throws IOException {
		writeBinary(new File(dest, "count"), new int[]{count});
	}

	/**
	 * Reads binary data from a file into the given memory data instance.
	 *
	 * @param src Source file to read from
	 * @param mem Memory data instance to populate
	 * @throws IOException If the file cannot be read
	 */
	protected static void readBinary(File src, MemoryData mem) throws IOException {
		if (enableLazyReading) {
			mem.getRootDelegate().reassign(local.allocate(src, mem.getRootDelegate().getMemLength()));
		} else {
			try (InputStream in = new FileInputStream(src)) {
				mem.read(in);
			}
		}
	}

	/**
	 * Writes the raw bytes of the given memory data to a binary file.
	 *
	 * @param dest Destination file to write to
	 * @param mem  Memory data to write
	 * @throws IOException If the file cannot be written
	 */
	protected static void writeBinary(File dest, MemoryData mem) throws IOException {
		writeBinary(dest, mem.getMem(), mem.getMemLength());
	}

	/**
	 * Writes the first {@code length} elements of raw memory to a binary file.
	 *
	 * @param dest   Destination file to write to
	 * @param mem    Raw memory to read from
	 * @param length Number of elements to write
	 * @throws IOException If the file cannot be written
	 */
	protected static void writeBinary(File dest, Memory mem, int length) throws IOException {
		writeBinary(dest, mem.getBytes(length).array());
	}

	/**
	 * Writes an array of integers to a binary file as 32-bit values.
	 *
	 * @param dest Destination file to write to
	 * @param data Integer data to write
	 * @throws IOException If the file cannot be written
	 */
	protected static void writeBinary(File dest, int data[]) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(data.length * 8);
		for (int i : data) buf.putInt(i);
		writeBinary(dest, buf.array());
	}

	/**
	 * Reads double values from a binary file into the given array.
	 *
	 * @param src  Source file to read from
	 * @param data Array to populate with double values
	 * @throws IOException If the file cannot be read
	 */
	protected static void readBinary(File src, double data[]) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(8 * data.length);
		try (InputStream in = new FileInputStream(src)) {
			for (int i = 0; i < data.length; i++) {
				buf.put(in.readNBytes(8));
			}
		}

		buf.position(0);

		for (int i = 0; i < data.length; i++) {
			data[i] = buf.getDouble();
		}
	}

	/**
	 * Writes raw bytes to a file.
	 *
	 * @param dest Destination file to write to
	 * @param data Byte data to write
	 * @throws IOException If the file cannot be written
	 */
	protected static void writeBinary(File dest, byte data[]) throws IOException {
		try (OutputStream out = new FileOutputStream(dest)) {
			out.write(data);
		}
	}
}
