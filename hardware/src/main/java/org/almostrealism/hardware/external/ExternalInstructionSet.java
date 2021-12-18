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
