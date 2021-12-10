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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.Computation;
import org.almostrealism.generated.BaseGeneratedOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class NativeCompiler {
	public static boolean enableVerbose = false;

	public static final String LIB_NAME_REPLACE = "%NAME%";

	private static final String STDIO = "#include <stdio.h>\n";
	private static final String STDLIB = "#include <stdlib.h>\n";
	private static final String STR = "#include <string.h>\n";
	private static final String MATH = "#include <math.h>\n";
	private static final String JNI = "#include <jni.h>\n";
	private static final String OPENCL = System.getProperty("os.name").toLowerCase().startsWith("mac os") ?
								"#include <OpenCL/cl.h>\n" : "#include <cl.h>\n";

	private static int runnableCount;
	private static int dataCount;

	private String libExecutable, exeExecutable;
	private final String libCompiler, exeCompiler;
	private final String libDir;
	private final String libFormat;
	private final String dataDir;

	private final String header;

	public NativeCompiler(Hardware hardware, String libCompiler, String exeCompiler, String libDir, String libFormat, String dataDir, boolean cl) {
		if (libCompiler != null) {
			this.libExecutable = libCompiler.contains(".") ? libCompiler.substring(libCompiler.lastIndexOf(".") + 1) : null;
		}

		if (exeCompiler != null) {
			this.exeExecutable = exeCompiler.contains(".") ? exeCompiler.substring(exeCompiler.lastIndexOf(".") + 1) : null;
		}

		this.libCompiler = libCompiler;
		this.exeCompiler = exeCompiler;
		this.libDir = libDir;
		this.libFormat = libFormat;
		this.dataDir = dataDir;

		if (this.dataDir != null) {
			File data = new File(this.dataDir);
			if (!data.exists()) data.mkdir();
		}

		String pi = hardware.getNumberTypeName() + " M_PI_F = M_PI;";
		this.header = STDIO + STDLIB + STR + MATH + JNI +
				(cl ? OPENCL : "") +
				pi + "\n";
	}

	public String getLibraryDirectory() { return libDir; }

	public String getDataDirectory() { return dataDir; }

	public File reserveDataDirectory() {
		File data = new File(getDataDirectory() + "/" + dataCount++);
		if (!data.exists()) {
			data.mkdir();
		}

		return data;
	}

	protected String getExecutable(boolean lib) {
		if (lib) {
			if (libExecutable != null) return libExecutable;
			return libCompiler;
		} else {
			if (exeExecutable != null) return exeExecutable;
			return exeCompiler;
		}
	}

	protected String getInputFile(String name) {
		return libDir + "/" + name + ".c";
	}

	protected String getOutputFile(String name, boolean lib) {
		if (lib) {
			return libDir + "/" + libFormat.replaceAll(LIB_NAME_REPLACE, name);
		} else {
			return libDir + "/" + name;
		}
	}

	protected List<String> getArguments(String name, boolean lib) {
		List<String> command = new ArrayList<>();
		if (lib) {
			if (libExecutable != null) command.add(libCompiler);
		} else {
			if (exeExecutable != null) command.add(exeCompiler);
		}
		command.add(getInputFile(name));
		command.add(getOutputFile(name, lib));
		return command;
	}

	protected List<String> getCommand(String name, boolean lib) {
		List<String> command = new ArrayList<>();
		command.add(getExecutable(lib));
		command.addAll(getArguments(name, lib));
		return command;
	}

	public synchronized BaseGeneratedOperation reserveLibraryTarget() {
		try {
			BaseGeneratedOperation gen = (BaseGeneratedOperation)
					Class.forName("org.almostrealism.generated.GeneratedOperation" + runnableCount++)
							.getConstructor(Computation.class).newInstance(new Object[] { null });
			return gen;
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException
				| NoSuchMethodException | ClassNotFoundException e) {
			throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
		}
	}

	public void compile(NativeInstructionSet target, String code) {
		compileAndLoad(target.getClass(), code);
	}

	public String compile(Class target, String code) {
		return compile(target.getName(), code, true);
	}

	public String compile(String name, String code, boolean lib) {
		if (enableVerbose) System.out.println("NativeCompiler: Compiling native code for " + name);

		try (FileOutputStream out = new FileOutputStream(getInputFile(name));
				BufferedWriter buf = new BufferedWriter(new OutputStreamWriter(out))) {
			buf.write(header);
			buf.write(code);
		} catch (IOException e) {
			throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
		}

		try {
			Process process = new ProcessBuilder(getCommand(name, lib)).inheritIO().start();
			process.waitFor();

			if (process.exitValue() != 0) {
				throw new HardwareException("Native compiler failure (" + process.exitValue() + ")");
			}
		} catch (IOException | InterruptedException e) {
			throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
		}

		if (enableVerbose) System.out.println("NativeCompiler: Native code compiled for " + name);
		return name;
	}

	public void compileAndLoad(Class target, String code) {
		String name = compile(target, code);
		if (enableVerbose) System.out.println("NativeCompiler: Loading native library " + name);
		System.loadLibrary(name);
		if (enableVerbose) System.out.println("NativeCompiler: Loaded native library " + name);
	}
}
