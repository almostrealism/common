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

import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * {@link InstructionSet} for executing compiled standalone executables via external processes.
 *
 * <p>Wraps a compiled C executable that reads {@link MemoryData} from files, performs computation,
 * and writes results back to files. Used by {@link ExternalComputeContext} for process-based execution.</p>
 *
 * <h2>Execution Protocol</h2>
 *
 * <ol>
 *   <li>Write input {@link MemoryData} arrays to temporary directory as binary files (0, 1, 2, ...)</li>
 *   <li>Write metadata files: sizes, offsets, count</li>
 *   <li>Launch executable with directory path as argument</li>
 *   <li>Wait for process completion</li>
 *   <li>Read modified data from same binary files</li>
 *   <li>Clean up temporary files</li>
 * </ol>
 *
 * <h2>File Format</h2>
 *
 * <pre>
 * data_dir/
 *   0          # Binary data for argument 0 (double array)
 *   1          # Binary data for argument 1
 *   sizes      # Array of element counts per argument
 *   offsets    # Array of memory offsets
 *   count      # Total argument count
 * </pre>
 *
 * <h2>Performance Characteristics</h2>
 *
 * <ul>
 *   <li><strong>Process spawn:</strong> ~10ms overhead per execution</li>
 *   <li><strong>File I/O:</strong> ~0.5ms per MB of data</li>
 *   <li><strong>Total overhead:</strong> 10-100x slower than JNI backend</li>
 *   <li><strong>Use case:</strong> Development/debugging only, not production</li>
 * </ul>
 *
 * @see ExternalComputeContext
 * @see LocalExternalMemoryProvider
 */
public class ExternalInstructionSet implements InstructionSet {
	/** Path to the compiled standalone executable. */
	private String executable;

	/** Supplier of temporary directory for file-based I/O. */
	private Supplier<File> dataDirectory;

	/**
	 * Creates an instruction set that executes via external process.
	 *
	 * @param executable Path to the compiled standalone executable
	 * @param dataDirectory Supplier of temporary directory for I/O files
	 */
	public ExternalInstructionSet(String executable, Supplier<File> dataDirectory) {
		this.executable = executable;
		this.dataDirectory = dataDirectory;
	}

	/**
	 * Returns an execution that runs the external process with file-based I/O.
	 *
	 * <p>Creates temporary directory, writes arguments as binary files, executes
	 * the external process passing directory path, reads results from files, and
	 * cleans up temporary data.</p>
	 *
	 * @param function Function name (unused for external execution)
	 * @param argCount Number of {@link MemoryData} arguments
	 * @return Execution that launches external process
	 */
	@Override
	public Execution get(String function, int argCount) {
		return (args, dependsOn) -> {
			if (dependsOn != null) dependsOn.waitFor();

			File dest = dataDirectory.get();

			try {
				MemoryData data[] = IntStream.range(0, argCount).mapToObj(i -> (MemoryData) args[i]).toArray(MemoryData[]::new);

				try {
					LocalExternalMemoryProvider.writeData(dest, data);
					LocalExternalMemoryProvider.writeSizes(dest, data);
					LocalExternalMemoryProvider.writeOffsets(dest, data);
					LocalExternalMemoryProvider.writeCount(dest, argCount);
				} catch (IOException e) {
					throw new HardwareException("Unable to write binary", e);
				}

				run(dest.getAbsolutePath());

				try {
					LocalExternalMemoryProvider.readData(dest, data);
				} catch (IOException e) {
					throw new HardwareException("Unable to read binary", e);
				}
			} finally {
				deleteData(dest);
			}

			return null;
		};
	}

	/**
	 * Executes the external process with the specified data directory.
	 *
	 * <p>Launches the compiled executable with the directory path as an argument,
	 * waits for completion, and logs execution time.</p>
	 *
	 * @param dir the absolute path to the data directory containing input files
	 * @throws HardwareException if execution fails or returns non-zero exit code
	 */
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

	/**
	 * Recursively deletes temporary data files and directories.
	 *
	 * <p>If lazy reading is enabled, files are scheduled for deletion on JVM exit
	 * instead of being deleted immediately.</p>
	 *
	 * @param data the file or directory to delete
	 */
	protected void deleteData(File data) {
		if (data.isDirectory()) Stream.of(data.listFiles()).forEach(this::deleteData);
		if (LocalExternalMemoryProvider.enableLazyReading) {
			data.deleteOnExit();
		} else {
			data.delete();
		}
	}

	/**
	 * Returns whether this instruction set has been destroyed.
	 *
	 * @return always false, as external instruction sets are not destroyable
	 */
	@Override
	public boolean isDestroyed() { return false; }

	/**
	 * Destroys this instruction set.
	 *
	 * <p>No-op for external instruction sets since there are no resources to release.</p>
	 */
	@Override
	public void destroy() { }
}
