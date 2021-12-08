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

import io.almostrealism.code.InstructionSet;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ExternalInstructionSet implements InstructionSet {
	private String executable;
	private Supplier<File> dataDirectory;

	public ExternalInstructionSet(String executable, Supplier<File> dataDirectory) {
		this.executable = executable;
		this.dataDirectory = dataDirectory;
	}

	@Override
	public Consumer<Object[]> get(String function, int argCount) {
		return args -> {
			File dest = dataDirectory.get();

			try {
				MemoryData data[] = IntStream.range(0, argCount).mapToObj(i -> (MemoryData) args[i]).toArray(MemoryData[]::new);

				try {
					writeData(dest, data);
					writeSizes(dest, data);
					writeOffsets(dest, data);
					writeCount(dest, argCount);
				} catch (IOException e) {
					throw new HardwareException("Unable to write binary", e);
				}

				run(dest.getAbsolutePath());

				try {
					readData(dest, data);
				} catch (IOException e) {
					throw new HardwareException("Unable to read binary", e);
				}
			} finally {
				deleteData(dest);
			}
		};
	}

	protected void run(String dir) {
		try {
			long start = System.currentTimeMillis();
			Process process = new ProcessBuilder(new File(executable).getAbsolutePath(), dir).inheritIO().start();
			process.waitFor();
			System.out.println("ExternalInstructionSet: " + (System.currentTimeMillis() - start) + " msec");

			if (process.exitValue() != 0) {
				throw new HardwareException("Native execution failure (" + process.exitValue() + ")");
			}
		} catch (IOException | InterruptedException e) {
			throw new HardwareException(e.getMessage(), e);
		}
	}

	protected void deleteData(File data) {
		if (data.isDirectory()) Stream.of(data.listFiles()).forEach(this::deleteData);
		data.delete();
	}

	@Override
	public boolean isDestroyed() { return false; }

	@Override
	public void destroy() { }

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

	protected static void readBinary(File dest, MemoryData mem) throws IOException {
		double data[] = new double[mem.getMemLength()];
		readBinary(dest, data);
//		System.out.println("readBinary: " + Arrays.toString(data));
		mem.setMem(data, 0);
	}

	protected static void writeBinary(File dest, MemoryData mem) throws IOException {
		double data[] = new double[mem.getMemLength()];
		mem.getMem(0, data, 0, data.length);
//		System.out.println("writeBinary: " + Arrays.toString(data));
		ByteBuffer buf = ByteBuffer.allocate(data.length * 8);
		for (double d : data) buf.putDouble(d);
		writeBinary(dest, buf.array());
	}

	protected static void writeBinary(File dest, int data[]) throws IOException {
//		System.out.println("writeBinary: " + Arrays.toString(data));
		ByteBuffer buf = ByteBuffer.allocate(data.length * 8);
		for (int i : data) buf.putInt(i);
		writeBinary(dest, buf.array());
	}

	protected static void readBinary(File dest, double data[]) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(8 * data.length);
		try (InputStream in = new FileInputStream(dest)) {
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
