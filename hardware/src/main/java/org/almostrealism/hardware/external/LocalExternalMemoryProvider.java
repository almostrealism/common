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

public class LocalExternalMemoryProvider implements MemoryProvider<Memory> {
	public static boolean enableLazyReading = true;

	private static LocalExternalMemoryProvider local = new LocalExternalMemoryProvider(null);

	private Supplier<File> location;

	public LocalExternalMemoryProvider(Supplier<File> location) {
		this.location = location;
	}

	@Override
	public Memory allocate(int size) {
		return allocate(location.get(), size);
	}

	public Memory allocate(File location, int size) {
		return new LocalExternalMemory(this, location, size);
	}

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
