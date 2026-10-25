/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.test;

import org.almostrealism.hardware.external.ExternalDataDirectory;
import org.almostrealism.hardware.external.LocalExternalMemoryProvider;
import org.almostrealism.hardware.mem.Bytes;
import org.junit.Assert;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * An {@link ExternalDataDirectory} lays the arguments of an external execution out in the
 * format the executable reads, reads back what the executable left in the argument files,
 * and is deleted once destroyed, unless its files have become the memory of the arguments.
 *
 * <p>No executable is run: each test writes the argument file the executable would have
 * written itself.</p>
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that
 * class lives in the engine layer, which sits above this module.</p>
 */
public class ExternalDataDirectoryTest {

	/**
	 * Each argument is written as its own file of big-endian FP64 values, and the sizes,
	 * offsets and count of the arguments as big-endian 32-bit integers, which is exactly
	 * what the executable reads.
	 *
	 * @throws IOException if the directory cannot be created or read
	 */
	@Test(timeout = 30000)
	public void writeLaysOutTheFileFormat() throws IOException {
		File location = Files.createTempDirectory("external-data").toFile();

		Bytes first = new Bytes(3);
		Bytes second = new Bytes(2);
		first.getMem().set(0, 1.5, -2.0, 3.25);
		second.getMem().set(0, 4.0, 0.5);

		ExternalDataDirectory data = new ExternalDataDirectory(location, first, second);

		try {
			data.write();

			Assert.assertArrayEquals(new double[] { 1.5, -2.0, 3.25 },
					readDoubles(new File(location, "0")), 0.0);
			Assert.assertArrayEquals(new double[] { 4.0, 0.5 },
					readDoubles(new File(location, "1")), 0.0);
			Assert.assertArrayEquals(new int[] { 3, 2 }, readIntegers(new File(location, "sizes")));
			Assert.assertArrayEquals(new int[] { 0, 0 }, readIntegers(new File(location, "offsets")));
			Assert.assertArrayEquals(new int[] { 2 }, readIntegers(new File(location, "count")));
		} finally {
			data.destroy();
			first.destroy();
			second.destroy();
		}
	}

	/**
	 * Without lazy reading the results are copied into the arguments, so nothing needs the
	 * files afterwards and destroying the directory deletes it.
	 *
	 * @throws IOException if the directory cannot be created or written
	 */
	@Test(timeout = 30000)
	public void copiedResultsDeleteTheDirectoryOnDestroy() throws IOException {
		boolean lazy = LocalExternalMemoryProvider.enableLazyReading;
		LocalExternalMemoryProvider.enableLazyReading = false;

		File location = Files.createTempDirectory("external-data").toFile();
		Bytes arg = new Bytes(3);
		arg.getMem().set(0, 1.0, 2.0, 3.0);
		ExternalDataDirectory data = new ExternalDataDirectory(location, arg);

		try {
			data.write();
			writeDoubles(new File(location, "0"), 7.0, 8.0, 9.0);

			data.read();
			Assert.assertArrayEquals(new double[] { 7.0, 8.0, 9.0 }, arg.toArray(), 0.0);

			data.destroy();
			Assert.assertFalse("A directory whose results were copied out should be deleted when destroyed",
					location.exists());
		} finally {
			LocalExternalMemoryProvider.enableLazyReading = lazy;
			arg.destroy();
		}
	}

	/**
	 * With lazy reading an argument's memory becomes its file, so the results are read
	 * through the file, and destroying the directory must leave that file in place.
	 *
	 * @throws IOException if the directory cannot be created or written
	 */
	@Test(timeout = 30000)
	public void mappedResultsKeepTheirFilesOnDestroy() throws IOException {
		boolean lazy = LocalExternalMemoryProvider.enableLazyReading;
		LocalExternalMemoryProvider.enableLazyReading = true;

		File location = Files.createTempDirectory("external-data").toFile();
		Bytes arg = new Bytes(3);
		arg.getMem().set(0, 1.0, 2.0, 3.0);
		ExternalDataDirectory data = new ExternalDataDirectory(location, arg);

		try {
			data.write();
			writeDoubles(new File(location, "0"), 7.0, 8.0, 9.0);

			data.read();
			Assert.assertEquals("The argument's memory should be its file",
					"DISK", arg.getMem().getProvider().getName());
			Assert.assertArrayEquals(new double[] { 7.0, 8.0, 9.0 }, arg.toArray(), 0.0);

			data.destroy();
			Assert.assertTrue("The file backing the argument's memory must outlive the directory",
					new File(location, "0").exists());
			Assert.assertArrayEquals(new double[] { 7.0, 8.0, 9.0 }, arg.toArray(), 0.0);
		} finally {
			LocalExternalMemoryProvider.enableLazyReading = lazy;
			arg.destroy();
		}
	}

	/**
	 * Lazy reading keeps only files that became memory: when the results were never read
	 * back, as after a failed run, nothing is using the files and destroying the directory
	 * deletes it at once.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	@Test(timeout = 30000)
	public void unreadResultsDeleteTheDirectoryOnDestroy() throws IOException {
		boolean lazy = LocalExternalMemoryProvider.enableLazyReading;
		LocalExternalMemoryProvider.enableLazyReading = true;

		File location = Files.createTempDirectory("external-data").toFile();
		Bytes arg = new Bytes(2);
		arg.getMem().set(0, 1.0, 2.0);
		ExternalDataDirectory data = new ExternalDataDirectory(location, arg);

		try {
			data.write();
			data.destroy();

			Assert.assertFalse("A directory whose results were never read back should be deleted when destroyed",
					location.exists());
			Assert.assertArrayEquals(new double[] { 1.0, 2.0 }, arg.toArray(), 0.0);
		} finally {
			LocalExternalMemoryProvider.enableLazyReading = lazy;
			arg.destroy();
		}
	}

	/**
	 * Writes the given values to a file as big-endian FP64, as the executable writes its results.
	 *
	 * @param file   the file to write
	 * @param values the values
	 * @throws IOException if the file cannot be written
	 */
	private static void writeDoubles(File file, double... values) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
			for (double value : values) {
				out.writeDouble(value);
			}
		}
	}

	/**
	 * Reads a whole file as big-endian FP64 values.
	 *
	 * @param file the file to read
	 * @return the values in the file
	 * @throws IOException if the file cannot be read
	 */
	private static double[] readDoubles(File file) throws IOException {
		double[] values = new double[(int) (file.length() / Double.BYTES)];

		try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
			for (int i = 0; i < values.length; i++) {
				values[i] = in.readDouble();
			}
		}

		return values;
	}

	/**
	 * Reads a whole file as big-endian 32-bit integers.
	 *
	 * @param file the file to read
	 * @return the integers in the file
	 * @throws IOException if the file cannot be read
	 */
	private static int[] readIntegers(File file) throws IOException {
		int[] values = new int[(int) (file.length() / Integer.BYTES)];

		try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
			for (int i = 0; i < values.length; i++) {
				values[i] = in.readInt();
			}
		}

		return values;
	}
}
