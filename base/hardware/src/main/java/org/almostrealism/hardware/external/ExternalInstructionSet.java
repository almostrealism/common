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
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * {@link InstructionSet} for executing compiled standalone executables via external processes.
 *
 * <p>Wraps a compiled C executable that reads {@link MemoryData} from files, performs computation,
 * and writes results back to files. Used by {@link ExternalComputeContext} for process-based execution.</p>
 *
 * <h2>Execution Protocol</h2>
 *
 * <p>Each run of the executable ({@link #apply(MemoryData...)}) exchanges its arguments through
 * a fresh {@link ExternalDataDirectory}, which defines the file format:</p>
 *
 * <ol>
 *   <li>Write the input {@link MemoryData} arrays to the directory as binary files (0, 1, 2, ...)</li>
 *   <li>Write metadata files: sizes, offsets, count</li>
 *   <li>Launch executable with directory path as argument</li>
 *   <li>Wait for process completion</li>
 *   <li>Read modified data from same binary files</li>
 *   <li>Destroy the directory</li>
 * </ol>
 *
 * <p>The executable is given no work-item index, so each execution performs a single
 * work item (see {@link ExternalExecution}).</p>
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
 * @see ExternalDataDirectory
 * @see LocalExternalMemoryProvider
 */
public class ExternalInstructionSet implements InstructionSet, ConsoleFeatures {
	/** Path to the compiled standalone executable. */
	private final String executable;

	/** Supplier of a fresh, existing directory for each run's data exchange. */
	private final Supplier<File> dataDirectory;

	/**
	 * Creates an instruction set that executes via external process.
	 *
	 * @param executable Path to the compiled standalone executable
	 * @param dataDirectory Supplier of a fresh, existing directory for each run's data exchange
	 */
	public ExternalInstructionSet(String executable, Supplier<File> dataDirectory) {
		this.executable = executable;
		this.dataDirectory = dataDirectory;
	}

	/**
	 * Returns an execution that runs the executable over the given number of arguments.
	 *
	 * @param function Function name (unused for external execution)
	 * @param argCount Number of {@link MemoryData} arguments
	 * @return Execution that launches external process
	 */
	@Override
	public Execution get(String function, int argCount) {
		return new ExternalExecution(this, argCount);
	}

	/**
	 * Runs the executable once over the given arguments, exchanging them through a fresh
	 * {@link ExternalDataDirectory}: the arguments are written to it, the executable is run
	 * over it, the results are read back into the arguments, and the directory is destroyed
	 * whether or not the run succeeded.
	 *
	 * @param args the arguments, which receive the results
	 * @throws HardwareException if the arguments cannot be exchanged or the executable fails
	 */
	public void apply(MemoryData... args) {
		try (ExternalDataDirectory data = new ExternalDataDirectory(dataDirectory.get(), args)) {
			data.write();
			run(data);
			data.read();
		}
	}

	/**
	 * Launches the executable with the path of the given data directory as its argument, and
	 * waits for it to exit.
	 *
	 * @param data the directory holding the arguments
	 * @throws HardwareException if execution fails or returns non-zero exit code
	 */
	protected void run(ExternalDataDirectory data) {
		try {
			long start = System.currentTimeMillis();
			Process process = new ProcessBuilder(new File(executable).getAbsolutePath(), data.getPath()).inheritIO().start();
			process.waitFor();
			log("Executable completed in " + (System.currentTimeMillis() - start) + " msec");

			if (process.exitValue() != 0) {
				throw new HardwareException("Native execution failure (" + process.exitValue() + ")");
			}
		} catch (IOException | InterruptedException e) {
			throw new HardwareException(e.getMessage(), e);
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
