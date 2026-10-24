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

package org.almostrealism.hardware.external;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

/**
 * The directory through which one run of an {@link ExternalInstructionSet}'s executable
 * exchanges its {@link MemoryData} arguments. The executable is given the path of this
 * directory and nothing else: it reads its arguments from the files here, and writes its
 * results back into the argument files.
 *
 * <h2>File Format</h2>
 *
 * <pre>
 * data_dir/
 *   0          # Values of argument 0 (big-endian FP64)
 *   1          # Values of argument 1
 *   ...
 *   sizes      # Number of values in each argument (big-endian 32-bit integers)
 *   offsets    # Memory offset of each argument (big-endian 32-bit integers)
 *   count      # Number of arguments (one big-endian 32-bit integer)
 * </pre>
 *
 * <p>This is the layout {@code external-wrapper.c} reads before it calls the compiled
 * computation; the argument files are where it writes the results afterwards.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>{@code
 * try (ExternalDataDirectory data = new ExternalDataDirectory(location, args)) {
 *     data.write();
 *     // run the executable, giving it data.getPath()
 *     data.read();
 * }
 * }</pre>
 *
 * <p>The results are read back through a {@link LocalExternalMemoryProvider} this directory
 * owns. When that provider {@link LocalExternalMemoryProvider#isLazyReading() reads lazily},
 * each argument file becomes the memory of its argument rather than being copied out of, so
 * the files outlive the run and {@link #destroy()} leaves the directory to be deleted when the
 * JVM exits. Otherwise, and whenever the results were never read back, the directory is
 * deleted as soon as it is destroyed.</p>
 *
 * @see ExternalInstructionSet
 * @see LocalExternalMemoryProvider
 */
public class ExternalDataDirectory implements Destroyable {
	/** The directory on disk. */
	private final File location;

	/** The arguments exchanged through this directory, each in the file named by its position. */
	private final MemoryData[] args;

	/** Provider of the file-backed memory an argument becomes when its result is read lazily. */
	private final LocalExternalMemoryProvider results;

	/** Whether the argument files became the memory of the arguments when they were read back. */
	private boolean mapped;

	/**
	 * Creates the data directory at the given location, which must already exist, for
	 * exchanging the given arguments with the executable.
	 *
	 * @param location the directory on disk
	 * @param args     the arguments to exchange, which receive the results
	 */
	public ExternalDataDirectory(File location, MemoryData... args) {
		this.location = location;
		this.args = args;
		this.results = new LocalExternalMemoryProvider();
	}

	/**
	 * Returns the absolute path of this directory, which is what the executable is given.
	 *
	 * @return the absolute path of this directory
	 */
	public String getPath() {
		return location.getAbsolutePath();
	}

	/**
	 * Writes the arguments into this directory, in the format the executable reads: one
	 * file of values per argument, then the sizes, offsets and count of the arguments.
	 *
	 * @throws HardwareException if a file cannot be written
	 */
	public void write() {
		try {
			for (int i = 0; i < args.length; i++) {
				writeFile(String.valueOf(i), args[i].getMem().getBytes(args[i].getMemLength()));
			}

			writeFile("sizes", Stream.of(args).mapToInt(MemoryData::getMemLength).toArray());
			writeFile("offsets", Stream.of(args).mapToInt(MemoryData::getOffset).toArray());
			writeFile("count", args.length);
		} catch (IOException e) {
			throw new HardwareException("Unable to write binary", e);
		}
	}

	/**
	 * Reads the values the executable left in the argument files back into the arguments.
	 *
	 * @throws HardwareException if a file cannot be read
	 * @see LocalExternalMemoryProvider#read(File, MemoryData)
	 */
	public void read() {
		// Recorded first, so a file that became memory is kept even if a later one cannot be read
		mapped = results.isLazyReading();

		try {
			for (int i = 0; i < args.length; i++) {
				results.read(new File(location, String.valueOf(i)), args[i]);
			}
		} catch (IOException e) {
			throw new HardwareException("Unable to read binary", e);
		}
	}

	/**
	 * Deletes this directory once the run it served is over. When the argument files became
	 * the memory of the arguments, the directory and its files are deleted when the JVM exits;
	 * otherwise they are deleted now.
	 */
	@Override
	public void destroy() {
		File[] files = location.listFiles();
		if (files == null) files = new File[0];

		if (mapped) {
			// Deleted on exit in the reverse of the order registered, so the directory goes after its files
			location.deleteOnExit();
			for (File file : files) file.deleteOnExit();
		} else {
			for (File file : files) file.delete();
			location.delete();
		}
	}

	/**
	 * Writes the given 32-bit integers, big-endian, to the named file in this directory.
	 *
	 * @param name   the name of the file
	 * @param values the integers to write
	 * @throws IOException if the file cannot be written
	 */
	private void writeFile(String name, int... values) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(values.length * Integer.BYTES);
		for (int value : values) buf.putInt(value);
		writeFile(name, buf);
	}

	/**
	 * Writes the content of the given buffer to the named file in this directory.
	 *
	 * @param name the name of the file
	 * @param data the bytes to write
	 * @throws IOException if the file cannot be written
	 */
	private void writeFile(String name, ByteBuffer data) throws IOException {
		try (OutputStream out = new FileOutputStream(new File(location, name))) {
			out.write(data.array());
		}
	}
}
