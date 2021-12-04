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
import org.almostrealism.hardware.cl.CLMemoryProvider;

import java.io.BufferedWriter;
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
	private static final String MATH = "#include <math.h>\n";
	private static final String JNI = "#include <jni.h>\n";
	private static final String OPENCL = System.getProperty("os.name").toLowerCase().startsWith("mac os") ?
								"#include <OpenCL/cl.h>\n" : "#include <cl.h>\n";

	private static int runnableCount;

	private final String executable;
	private final String compiler;
	private final String libDir;
	private final String libFormat;

	private final String header;

	public NativeCompiler(Hardware hardware, String compiler, String libDir, String libFormat, boolean cl) {
		this.executable = compiler.contains(".") ? compiler.substring(compiler.lastIndexOf(".") + 1) : null;
		this.compiler = compiler;
		this.libDir = libDir;
		this.libFormat = libFormat;

		String pi = hardware.getNumberTypeName() + " M_PI_F = M_PI;";
		this.header = STDIO + STDLIB + MATH + JNI +
				(cl ? OPENCL : "") +
				pi + "\n";
	}

	protected String getExecutable() {
		if (executable != null) return executable;
		return compiler;
	}

	protected String getInputFile(String name) {
		return libDir + "/" + name + ".c";
	}

	protected String getOutputFile(String name) {
		return libDir + "/" + libFormat.replaceAll(LIB_NAME_REPLACE, name);
	}

	protected List<String> getArguments(String name) {
		List<String> command = new ArrayList<>();
		if (executable != null) command.add(compiler);
		command.add(getInputFile(name));
		command.add(getOutputFile(name));
		return command;
	}

	protected List<String> getCommand(String name) {
		List<String> command = new ArrayList<>();
		command.add(getExecutable());
		command.addAll(getArguments(name));
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
		try {
			compileAndLoad(target.getClass(), code);
		} catch (IOException e) {
			throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
		} catch (InterruptedException e) {
			throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
		}
	}

	public String compile(Class target, String code) throws IOException, InterruptedException {
		if (enableVerbose) System.out.println("NativeCompiler: Compiling native code for " + target.getSimpleName());
		String name = target.getName();

		try (FileOutputStream out = new FileOutputStream(getInputFile(name));
				BufferedWriter buf = new BufferedWriter(new OutputStreamWriter(out))) {
			buf.write(header);
			buf.write(code);
		}

		Process process = new ProcessBuilder(getCommand(name)).inheritIO().start();
		process.waitFor();

		if (process.exitValue() != 0) {
			throw new HardwareException("Native compiler failure (" + process.exitValue() + ")");
		}

		if (enableVerbose) System.out.println("NativeCompiler: Native code compiled for " + target.getSimpleName());
		return name;
	}

	public void compileAndLoad(Class target, String code) throws IOException, InterruptedException {
		String name = compile(target, code);
		if (enableVerbose) System.out.println("NativeCompiler: Loading native library " + name);
		System.loadLibrary(name);
		if (enableVerbose) System.out.println("NativeCompiler: Loaded native library " + name);
	}
}
