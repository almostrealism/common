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

package org.almostrealism.hardware.test;

import io.almostrealism.code.Memory;
import org.almostrealism.hardware.external.LocalExternalMemory;
import org.almostrealism.hardware.external.LocalExternalMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Tests for the file-backed memory the external-process backend exchanges data through: values live
 * in the file rather than in a copy of it, a write is in the file without being flushed, and the
 * format matches what the rest of the framework writes when it serializes memory.
 *
 * @see LocalExternalMemoryProvider
 */
public class LocalExternalMemoryTest extends TestSuiteBase {

	/** Number of values in the test exchanges. */
	private static final int SIZE = 32;

	/** The value at each position. */
	private static double valueAt(int index) { return 0.5 - 2.25 * index; }

	/** A provider allocating into fresh temporary files. */
	private LocalExternalMemoryProvider provider() {
		return new LocalExternalMemoryProvider(() -> {
			try {
				File file = File.createTempFile("external-memory", ".bin");
				file.deleteOnExit();
				return file;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	/** The values {@link #valueAt} describes. */
	private double[] values() {
		double[] values = new double[SIZE];
		for (int i = 0; i < SIZE; i++) {
			values[i] = valueAt(i);
		}
		return values;
	}

	/**
	 * Values written through the provider read back through it.
	 */
	@Test(timeout = 60000)
	public void valuesWrittenReadBack() {
		LocalExternalMemoryProvider provider = provider();
		Memory mem = provider.allocate(SIZE);

		provider.setMem(mem, 0, values(), 0, SIZE);

		double[] out = new double[SIZE];
		provider.getMem(mem, 0, out, 0, SIZE);
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), out[i], 0.0);
		}
	}

	/**
	 * A write lands at the offset given, leaving its neighbours alone, so a partial exchange does
	 * not disturb the rest of the file.
	 */
	@Test(timeout = 60000)
	public void partialWriteLeavesNeighboursAlone() {
		LocalExternalMemoryProvider provider = provider();
		Memory mem = provider.allocate(SIZE);
		provider.setMem(mem, 0, values(), 0, SIZE);

		provider.setMem(mem, 4, new double[] { 99.5, 98.5 }, 0, 2);

		double[] out = new double[SIZE];
		provider.getMem(mem, 0, out, 0, SIZE);
		Assert.assertEquals(valueAt(3), out[3], 0.0);
		Assert.assertEquals(99.5, out[4], 0.0);
		Assert.assertEquals(98.5, out[5], 0.0);
		Assert.assertEquals(valueAt(6), out[6], 0.0);
	}

	/**
	 * A value written through the provider is in the file, with nothing flushed on its behalf: the
	 * mapping is shared, which is what lets a process at the other end of the exchange read it.
	 *
	 * @throws IOException if the file cannot be read
	 */
	@Test(timeout = 60000)
	public void writeIsVisibleInTheFile() throws IOException {
		File file = File.createTempFile("external-memory-visible", ".bin");
		file.deleteOnExit();

		LocalExternalMemoryProvider provider = provider();
		Memory mem = provider.allocate(file, SIZE);
		provider.setMem(mem, 0, values(), 0, SIZE);

		Assert.assertEquals((long) SIZE * provider.getNumberSize(), file.length());

		double[] fromFile = readBigEndian(file, SIZE);
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), fromFile[i], 0.0);
		}
	}

	/**
	 * A file written in the framework's serialization format is read by mapping it, so the format
	 * this memory maps and the format {@code Memory.getBytes} writes are the same format. A
	 * byte-order mistake in either direction fails here.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 60000)
	public void readsAFileWrittenInTheSerializationFormat() throws IOException {
		File file = File.createTempFile("external-memory-format", ".bin");
		file.deleteOnExit();

		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
			for (int i = 0; i < SIZE; i++) {
				out.writeDouble(valueAt(i));
			}
		}

		LocalExternalMemoryProvider provider = provider();
		double[] read = provider.toArray(provider.allocate(file, SIZE), SIZE);
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), read[i], 0.0);
		}
	}

	/**
	 * Releasing the mapping keeps the file and its values, so memory can be given up without the
	 * exchange losing anything.
	 */
	@Test(timeout = 60000)
	public void releasingTheMappingKeepsTheValues() {
		LocalExternalMemoryProvider provider = provider();
		Memory mem = provider.allocate(SIZE);
		provider.setMem(mem, 0, values(), 0, SIZE);

		double[] out = new double[SIZE];
		provider.getMem(mem, 0, out, 0, SIZE);
		Assert.assertEquals(valueAt(9), out[9], 0.0);

		((LocalExternalMemory) mem).restore();

		double[] again = new double[SIZE];
		provider.getMem(mem, 0, again, 0, SIZE);
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), again[i], 0.0);
		}
	}

	/**
	 * Reads the given number of big-endian doubles from a file.
	 *
	 * @param file  the file to read
	 * @param count the number of values
	 * @return the values
	 * @throws IOException if the file cannot be read
	 */
	private double[] readBigEndian(File file, int count) throws IOException {
		double[] values = new double[count];

		try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
			for (int i = 0; i < count; i++) {
				values[i] = in.readDouble();
			}
		}

		return values;
	}
}
