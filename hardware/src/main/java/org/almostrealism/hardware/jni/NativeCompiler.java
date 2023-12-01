/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.code.Precision;
import io.almostrealism.relation.Factory;
import org.almostrealism.generated.BaseGeneratedOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class NativeCompiler implements ConsoleFeatures {
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

	private Precision precision;

	private LinkedLibraryGenerator libraryGenerator;
	private final String libDir;
	private final String libFormat;
	private final String dataDir;

	private final String header;

	public NativeCompiler(Precision precision, LinkedLibraryGenerator libraryGenerator,
						  String libDir, String libFormat, String dataDir, boolean cl) {
		this.libraryGenerator = libraryGenerator;
		this.precision = precision;
		this.libDir = libDir;
		this.libFormat = libFormat;
		this.dataDir = dataDir;

		if (this.dataDir != null) {
			File data = new File(this.dataDir);
			if (!data.exists()) data.mkdir();
		}

		String pi = precision.typeName() + " M_PI_F = M_PI;";
		this.header = STDIO + STDLIB + STR + MATH + JNI +
				(cl ? OPENCL : "") +
				pi + "\n";
	}

	public Precision getPrecision() { return precision; }

	public String getLibraryDirectory() { return libDir; }

	public String getDataDirectory() { return dataDir; }

	public File reserveDataDirectory() {
		File data = new File(getDataDirectory() + "/" + dataCount++);
		if (!data.exists()) {
			data.mkdir();
		}

		return data;
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
		if (enableVerbose) {
			log("Compiling native code for " + name + "\nSource:\n" + code);
		}

		try (FileOutputStream out = new FileOutputStream(getInputFile(name));
				BufferedWriter buf = new BufferedWriter(new OutputStreamWriter(out))) {
			buf.write(header);
			buf.write(code);
		} catch (IOException e) {
			throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
		}

		libraryGenerator.generateLibrary(getInputFile(name), getOutputFile(name, lib), runner(name));

		if (enableVerbose) log("Native code compiled for " + name);
		return name;
	}

	public void compileAndLoad(Class target, String code) {
		String name = compile(target, code);
		if (enableVerbose) log("Loading native library " + name);
		System.loadLibrary(name);
		if (enableVerbose) log("Loaded native library " + name);
	}

	protected Consumer<List<String>> runner(String name) {
		return command -> {
			try {
				Process process = new ProcessBuilder(command).inheritIO().start();
				process.waitFor();

				if (process.exitValue() != 0) {
					if (enableVerbose) {
						log(Arrays.toString(command.toArray()));
					}

					throw new HardwareException("Native compiler failure (" + process.exitValue() + ") on " + name);
				}
			} catch (IOException | InterruptedException e) {
				throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
			}
		};
	}

	public static Factory<NativeCompiler> factory(Precision precision, boolean cl) {
		return () -> {
			String libFormat = System.getProperty("AR_HARDWARE_LIB_FORMAT");
			if (libFormat == null) libFormat = System.getenv("AR_HARDWARE_LIB_FORMAT");
			if (libFormat == null) libFormat = SystemUtils.isAarch64() ? "lib%NAME%.dylib" : "lib%NAME%.so";

			String libCompiler = SystemUtils.getProperty("AR_HARDWARE_NATIVE_COMPILER");
			String libLinker = SystemUtils.getProperty("AR_HARDWARE_NATIVE_LINKER");
			String exeCompiler = SystemUtils.getProperty("AR_HARDWARE_EXTERNAL_COMPILER");
			String libDir = SystemUtils.getProperty("AR_HARDWARE_LIBS");
			String data = SystemUtils.getProperty("AR_HARDWARE_DATA");

			boolean localToolchain = libCompiler == null || !libCompiler.contains("/");

			if (libDir == null && SystemUtils.isMacOS()) {
				if (localToolchain) {
					libDir = System.getProperty("user.home") + "/Library/Java/Extensions";
				} else {
					libDir = "Extensions";
				}

				File ld = new File(libDir);
				if (!ld.exists()) ld.mkdir();
			}

			CompilerCommandProvider commandProvider;

			if (libCompiler == null) {
				commandProvider = new Clang();
			} else if (libCompiler.endsWith("gcc") || libCompiler.endsWith("clang")) {
				commandProvider = new Clang(libCompiler, localToolchain);
			} else if (libCompiler.endsWith("icc")) {
				throw new UnsupportedOperationException();
			} else {
				commandProvider = new DefaultCompilerCommandProvider(libCompiler, exeCompiler);
			}

			if (libLinker != null) {
				if (commandProvider instanceof Clang) {
					((Clang) commandProvider).setLinker(libLinker);
				} else {
					throw new UnsupportedOperationException("Cannot set linker for " + commandProvider.getClass().getName());
				}
			}

			return new NativeCompiler(precision, new DefaultLinkedLibraryGenerator(commandProvider),
										libDir, libFormat, data, cl);
		};
	}

	@Override
	public Console console() { return Hardware.console; }
}
