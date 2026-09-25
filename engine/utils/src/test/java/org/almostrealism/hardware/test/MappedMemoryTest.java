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
import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.FileMapping;
import org.almostrealism.hardware.mem.MappedMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Tests for values served from the file that holds them: a file becomes memory by being mapped,
 * the values never reach the Java heap, and a kernel reading them gets what the file says.
 *
 * @see MappedMemoryProvider
 */
public class MappedMemoryTest extends TestSuiteBase {

	/** Number of values in the test files. */
	private static final int SIZE = 48;

	/** Bytes of header written before the values, as the extraction scripts write. */
	private static final int HEADER = 4;

	/** The value at each position. */
	private static double valueAt(int index) { return 1.5 * index - 6.0; }

	/**
	 * Writes a file of {@link #SIZE} values at the given precision, behind a header.
	 *
	 * @param precision the width to store the values at
	 * @return the written file
	 * @throws IOException if the file cannot be written
	 */
	private File written(Precision precision) throws IOException {
		File file = File.createTempFile("mapped-memory", ".bin");
		file.deleteOnExit();

		ByteBuffer buffer = ByteBuffer
				.allocate(HEADER + precision.bytes() * SIZE)
				.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(SIZE);

		for (int i = 0; i < SIZE; i++) {
			if (precision == Precision.FP64) {
				buffer.putDouble(valueAt(i));
			} else {
				buffer.putFloat((float) valueAt(i));
			}
		}

		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(buffer.array());
		}

		return file;
	}

	/**
	 * A collection over the given file's values.
	 *
	 * @param file      the file to read through
	 * @param precision the width the values are stored at
	 * @return a collection of {@link #SIZE} values
	 */
	private PackedCollection mapped(File file, Precision precision) {
		Memory mem = MappedMemoryProvider.getInstance().allocate(file, precision, HEADER, SIZE);
		return new PackedCollection(new TraversalPolicy(SIZE), 0, Bytes.of(mem, SIZE), 0);
	}

	/**
	 * Values stored as single precision read back as they were written, and the collection reports
	 * itself read-only, since the file it reads is not a place to put results.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 60000)
	public void singlePrecisionValuesReadAsWritten() throws IOException {
		PackedCollection mapped = mapped(written(Precision.FP32), Precision.FP32);

		Assert.assertTrue(mapped.isReadOnly());
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), mapped.toDouble(i), 1e-5);
		}
	}

	/**
	 * Values stored as double precision read back exactly, so the stored width is the file's and not
	 * the reader's.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 60000)
	public void doublePrecisionValuesReadExactly() throws IOException {
		PackedCollection mapped = mapped(written(Precision.FP64), Precision.FP64);

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, valueAt(i), mapped.toDouble(i), 0.0);
		}
	}

	/**
	 * A kernel reading a mapped file computes from its values: the framework migrates the memory to
	 * a device at first use, which is the whole reason a file can be memory rather than a copy.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 120000)
	public void mappedFileComputesAsKernelArgument() throws IOException {
		PackedCollection mapped = mapped(written(Precision.FP32), Precision.FP32);

		PackedCollection doubled = cp(mapped).multiply(2.0).evaluate();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, 2.0 * valueAt(i), doubled.toDouble(i), 1e-5);
		}
	}

	/**
	 * The header is skipped rather than read as a value, so the offset a caller gives is where the
	 * values actually start.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 60000)
	public void valuesStartPastTheHeader() throws IOException {
		File file = written(Precision.FP32);
		Memory withHeader = MappedMemoryProvider.getInstance()
				.allocate(file, Precision.FP32, 0, SIZE);

		double[] shifted = MappedMemoryProvider.getInstance().toArray(withHeader, 1, SIZE - 1);
		PackedCollection mapped = mapped(file, Precision.FP32);

		for (int i = 0; i < SIZE - 1; i++) {
			Assert.assertEquals("element " + i, mapped.toDouble(i), shifted[i], 1e-5);
		}
	}

	/**
	 * A file too short for the values asked of it is refused when the memory is created, rather than
	 * read past its end later.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 60000)
	public void shortFileIsRefused() throws IOException {
		File file = written(Precision.FP32);

		try {
			MappedMemoryProvider.getInstance().allocate(file, Precision.FP32, HEADER, SIZE + 16);
			throw new AssertionError("a file short of the values requested must be refused");
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	/**
	 * A precision the mapping cannot serve is refused when the memory is created, rather than read
	 * with the wrong byte width later. Only FP32 and FP64 are read directly from the file; FP16 would
	 * advance two bytes per value while the reader consumes four, corrupting the values and running
	 * past the mapping.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 60000)
	public void unservedPrecisionIsRefused() throws IOException {
		File file = written(Precision.FP32);

		try {
			MappedMemoryProvider.getInstance().allocate(file, Precision.FP16, HEADER, SIZE);
			throw new AssertionError("a precision the mapping cannot serve must be refused");
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	/**
	 * Many readers of one file share one mapping, so a directory of references costs one mapping per
	 * file rather than one per tensor read out of it.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test(timeout = 60000)
	public void readersOfOneFileShareOneMapping() throws IOException {
		File file = written(Precision.FP32);
		int before = FileMapping.getMappedFileCount();

		PackedCollection first = mapped(file, Precision.FP32);
		PackedCollection second = mapped(file, Precision.FP32);

		Assert.assertEquals(before + 1, FileMapping.getMappedFileCount());
		Assert.assertEquals(first.toDouble(3), second.toDouble(3), 0.0);
	}
}
