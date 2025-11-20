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
 *   <li><strong>Total overhead:</strong> 10-100× slower than JNI backend</li>
 *   <li><strong>Use case:</strong> Development/debugging only, not production</li>
 * </ul>
 *
 * @see ExternalComputeContext
 * @see LocalExternalMemoryProvider
 */
public class ExternalInstructionSet implements InstructionSet {
	private String executable;
	private Supplier<File> dataDirectory;

	public ExternalInstructionSet(String executable, Supplier<File> dataDirectory) {
		this.executable = executable;
		this.dataDirectory = dataDirectory;
	}

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
		if (LocalExternalMemoryProvider.enableLazyReading) {
			data.deleteOnExit();
		} else {
			data.delete();
		}
	}

	@Override
	public boolean isDestroyed() { return false; }

	@Override
	public void destroy() { }
}
