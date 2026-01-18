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
 *   <li><strong>Lazy Loading:</strong> Data read from file only when accessed (if {@code enableLazyReading})</li>
 *   <li><strong>Write-Through:</strong> Modifications written to file immediately</li>
 *   <li><strong>Delegation:</strong> Can replace in-memory data with file-backed storage via {@code reassign()}</li>
 *   <li><strong>Cleanup:</strong> Files deleted after read (unless lazy reading enabled)</li>
 * </ul>
 *
 * <h2>Binary File Format</h2>
 *
 * <p>All data stored as double precision (FP64) in native byte order:</p>
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
	public static boolean enableLazyReading = true;

	private static LocalExternalMemoryProvider local = new LocalExternalMemoryProvider(null);

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

	/**
	 * Returns the size of each number in bytes.
	 *
	 * @return 8 (FP64 double precision)
	 */
	@Override
	public int getNumberSize() { return 8; }

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
		load(src, dest);
		for (int i = 0; i < length; i++) {
			dest.data[offset + i] = src.data[srcOffset + i];
		}
		unload(dest);
	}

	@Override
	public void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		LocalExternalMemory dest = (LocalExternalMemory) mem;
		load(dest);
		for (int i = 0; i < length; i++) {
			dest.data[offset + i] = source[srcOffset + i];
		}
		unload(dest);
	}

	@Override
	public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
		LocalExternalMemory src = (LocalExternalMemory) mem;
		load(src);
		for (int i = 0; i < length; i++) {
			out[oOffset + i] = src.data[sOffset + i];
		}
	}

	@Override
	public void destroy() {
		// TODO  Destroy all LocalExternalMemory
	}

	protected static void load(LocalExternalMemory... mem) {
		Stream.of(mem).forEach(LocalExternalMemory::read);
	}

	protected static void unload(LocalExternalMemory... mem) {
		Stream.of(mem).forEach(LocalExternalMemory::write);
	}

	protected static void readData(File dest, MemoryData[] args) throws IOException {
		for (int i = 0; i < args.length; i++) {
			readBinary(new File(dest, String.valueOf(i)), args[i]);
		}
	}

	protected static void writeData(File dest, MemoryData[] args) throws IOException {
		for (int i = 0; i < args.length; i++) {
			writeBinary(new File(dest, String.valueOf(i)), args[i]);
		}
	}

	protected static void writeSizes(File dest, MemoryData[] args) throws IOException {
		writeBinary(new File(dest, "sizes"), Stream.of(args).mapToInt(MemoryData::getMemLength).toArray());
	}

	protected static void writeOffsets(File dest, MemoryData[] args) throws IOException {
		writeBinary(new File(dest, "offsets"), Stream.of(args).mapToInt(MemoryData::getOffset).toArray());
	}

	protected static void writeCount(File dest, int count) throws IOException {
		writeBinary(new File(dest, "count"), new int[]{count});
	}

	protected static void readBinary(File src, MemoryData mem) throws IOException {
		if (enableLazyReading) {
			mem.getRootDelegate().reassign(local.allocate(src, mem.getRootDelegate().getMemLength()));
		} else {
			double data[] = new double[mem.getMemLength()];
			readBinary(src, data);
			mem.setMem(data, 0);
		}
	}

	protected static void writeBinary(File dest, MemoryData mem) throws IOException {
		writeBinary(dest, mem.getMem(), mem.getMemLength());
	}

	protected static void writeBinary(File dest, Memory mem, int length) throws IOException {
		writeBinary(dest, mem.getBytes(length).array());
	}

	protected static void writeBinary(File dest, int data[]) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(data.length * 8);
		for (int i : data) buf.putInt(i);
		writeBinary(dest, buf.array());
	}

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

	protected static void writeBinary(File dest, byte data[]) throws IOException {
		try (OutputStream out = new FileOutputStream(dest)) {
			out.write(data);
		}
	}
}
