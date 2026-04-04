/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Factory;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.generated.BaseGeneratedOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compiler infrastructure for converting generated C code into native shared libraries loaded via JNI.
 *
 * <p>{@link NativeCompiler} is the core compilation engine for Almost Realism's native execution backend.
 * It orchestrates the following pipeline:</p>
 * <ol>
 *   <li><strong>Write C source:</strong> Generates .c files with proper headers and JNI signatures</li>
 *   <li><strong>Invoke compiler:</strong> Calls native compilers (Clang/GCC) to build shared libraries</li>
 *   <li><strong>Load via JNI:</strong> Loads compiled .so/.dylib files into the JVM via System.load()</li>
 *   <li><strong>Track performance:</strong> Measures compilation time for profiling</li>
 * </ol>
 *
 * <h2>Compilation Pipeline</h2>
 *
 * <p>When C code is compiled, the following steps occur:</p>
 * <pre>
 * 1. Write source file       libName.c (with headers)
 * 2. Invoke native compiler  clang -shared -o libName.so libName.c
 * 3. Wait for process        Process.waitFor()
 * 4. Load library           System.load(libName.so)
 * 5. Execute via JNI        Native method now available
 * </pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create compiler
 * NativeCompiler compiler = NativeCompiler.factory(Precision.FP64, false).construct();
 *
 * // Reserve a target for compilation
 * NativeInstructionSet target = (NativeInstructionSet) compiler.reserveLibraryTarget();
 *
 * // Generate C code (from Scope)
 * String cCode = generateCCode(scope);
 *
 * // Compile and load
 * compiler.compile(target, cCode);
 *
 * // Now target.execute() will call native code via JNI
 * }</pre>
 *
 * <h2>Configuration via Environment Variables</h2>
 *
 * <p>The compiler is configured via environment variables:</p>
 * <table>
 *   <caption>Native compiler configuration options</caption>
 *   <tr>
 *     <th>Variable</th>
 *     <th>Description</th>
 *     <th>Default</th>
 *   </tr>
 *   <tr>
 *     <td>AR_HARDWARE_LIBS</td>
 *     <td>Directory for compiled libraries</td>
 *     <td>~/Library/Extensions (macOS)</td>
 *   </tr>
 *   <tr>
 *     <td>AR_HARDWARE_DATA</td>
 *     <td>Directory for runtime data</td>
 *     <td>null (no data dir)</td>
 *   </tr>
 *   <tr>
 *     <td>AR_HARDWARE_LIB_FORMAT</td>
 *     <td>Library filename format</td>
 *     <td>lib%NAME%.so (Linux)<br>lib%NAME%.dylib (macOS)</td>
 *   </tr>
 *   <tr>
 *     <td>AR_HARDWARE_NATIVE_COMPILER</td>
 *     <td>Path to compiler executable</td>
 *     <td>clang (from PATH)</td>
 *   </tr>
 *   <tr>
 *     <td>AR_HARDWARE_NATIVE_LINKER</td>
 *     <td>Path to linker (Clang only)</td>
 *     <td>Default linker</td>
 *   </tr>
 *   <tr>
 *     <td>AR_HARDWARE_COMPILER_LOGGING</td>
 *     <td>Enable verbose logging</td>
 *     <td>false</td>
 *   </tr>
 * </table>
 *
 * <h2>Compiler Selection</h2>
 *
 * <p>The factory method automatically selects the appropriate compiler:</p>
 * <pre>{@code
 * // Default: Uses Clang from PATH
 * NativeCompiler compiler = NativeCompiler.factory(Precision.FP64, false).construct();
 *
 * // Custom: Specify compiler via environment
 * System.setProperty("AR_HARDWARE_NATIVE_COMPILER", "/usr/bin/gcc");
 * NativeCompiler gcc = NativeCompiler.factory(Precision.FP64, false).construct();
 *
 * // LLD linker: Use LLVM's lld instead of default linker
 * System.setProperty("AR_HARDWARE_NATIVE_LINKER", "lld");
 * NativeCompiler lld = NativeCompiler.factory(Precision.FP64, false).construct();
 * }</pre>
 *
 * <h2>Precision Configuration</h2>
 *
 * <p>The {@link Precision} determines C data types:</p>
 * <ul>
 *   <li><strong>FP32:</strong> Uses {@code float} in generated code, 4 bytes per element</li>
 *   <li><strong>FP64:</strong> Uses {@code double} in generated code, 8 bytes per element</li>
 * </ul>
 *
 * <h2>Generated Headers</h2>
 *
 * <p>All compiled C files include standard headers plus JNI:</p>
 * <pre>
 * #include &lt;stdio.h&gt;
 * #include &lt;stdlib.h&gt;
 * #include &lt;string.h&gt;
 * #include &lt;math.h&gt;
 * #include &lt;jni.h&gt;
 * float M_PI_F = M_PI;  // or double for FP64
 * </pre>
 *
 * <p>If OpenCL compatibility is enabled ({@code cl = true}), also includes:</p>
 * <pre>
 * #include &lt;OpenCL/cl.h&gt;  // macOS
 * #include &lt;cl.h&gt;         // Linux
 * </pre>
 *
 * <h2>Library Target Management</h2>
 *
 * <p>{@link #reserveLibraryTarget()} allocates pre-generated JNI operation classes:</p>
 * <pre>{@code
 * // Reserves org.almostrealism.generated.GeneratedOperation0
 * BaseGeneratedOperation op1 = compiler.reserveLibraryTarget();
 *
 * // Reserves org.almostrealism.generated.GeneratedOperation1
 * BaseGeneratedOperation op2 = compiler.reserveLibraryTarget();
 *
 * // Each gets a unique class name for native linking
 * }</pre>
 *
 * <p>Generated operation classes are pre-compiled Java classes with native method stubs.
 * The compiler generates matching C implementations with JNI signatures.</p>
 *
 * <h2>Compilation Monitoring</h2>
 *
 * <p>Enable monitoring to save generated C code for inspection:</p>
 * <pre>{@code
 * // Save all instruction sets
 * HardwareOperator.enableInstructionSetMonitoring = true;
 *
 * // Only save large instruction sets (> 50KB)
 * HardwareOperator.enableLargeInstructionSetMonitoring = true;
 *
 * // Saved to: results/jni_instruction_set_N.c
 * }</pre>
 *
 * <h2>Performance Tracking</h2>
 *
 * <p>Compilation times are recorded via {@link #compileTime}:</p>
 * <pre>{@code
 * // Get compilation metrics
 * TimingMetric timing = NativeCompiler.compileTime;
 * double avgMs = timing.getAverage() / 1_000_000.0;
 * long count = NativeCompiler.getTotalInstructionSets();
 *
 * System.out.printf("Compiled %d libraries (avg %.2f ms)\n", count, avgMs);
 * }</pre>
 *
 * <h2>Error Handling</h2>
 *
 * <p>Compilation failures throw {@link HardwareException}:</p>
 * <pre>{@code
 * try {
 *     compiler.compile(target, code);
 * } catch (HardwareException e) {
 *     // Check if compilation failed
 *     if (e.getMessage().contains("Native compiler failure")) {
 *         // Compiler returned non-zero exit code
 *         // Check compiler logs for details
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The {@link #reserveLibraryTarget()} method is synchronized to ensure unique class names.
 * Compilation itself is thread-safe, allowing concurrent compilations to different targets.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Typically created once per {@link NativeDataContext} and reused for all compilations.
 * {@link #destroy()} is currently a no-op but may clean up resources in future versions.</p>
 *
 * @see NativeComputeContext
 * @see NativeInstructionSet
 * @see LinkedLibraryGenerator
 * @see CompilerCommandProvider
 */
public class NativeCompiler implements Destroyable, ConsoleFeatures {
	/** If true, log each compilation and library load operation. Controlled by {@code AR_HARDWARE_COMPILER_LOGGING}. */
	public static boolean enableVerbose = SystemUtils.isEnabled("AR_HARDWARE_COMPILER_LOGGING").orElse(false);

	/** Metric tracking native compilation time for JNI kernels. */
	public static TimingMetric compileTime = Hardware.console.timing("jniCompile");

	/** Placeholder token in library path format strings that is replaced with the class name. */
	public static final String LIB_NAME_REPLACE = "%NAME%";

	/** C standard I/O include directive prepended to every generated source file. */
	private static final String STDIO = "#include <stdio.h>\n";
	/** C standard library include directive prepended to every generated source file. */
	private static final String STDLIB = "#include <stdlib.h>\n";
	/** C string library include directive prepended to every generated source file. */
	private static final String STR = "#include <string.h>\n";
	/** C math library include directive prepended to every generated source file. */
	private static final String MATH = "#include <math.h>\n";
	/** JNI header include directive prepended to every generated source file. */
	private static final String JNI = "#include <jni.h>\n";
	/** OpenCL header include directive; uses platform-specific path on macOS. */
	private static final String OPENCL = System.getProperty("os.name").toLowerCase().startsWith("mac os") ?
								"#include <OpenCL/cl.h>\n" : "#include <cl.h>\n";

	/** Counter used to generate unique class names for compiled runnable operations. */
	private static int runnableCount;
	/** Counter used to assign unique directory names for kernel data files. */
	private static int dataCount;
	/** Counter tracking how many instruction set monitoring files have been written. */
	private static int monitorOutputCount;

	/** Numeric precision used for type name and PI constant declaration in generated code. */
	private Precision precision;

	/** Generator responsible for invoking the native compiler and linker toolchain. */
	private LinkedLibraryGenerator libraryGenerator;
	/** Directory where generated C source files and compiled shared libraries are written. */
	private final String libDir;
	/** Format string for the output library filename; use {@link #LIB_NAME_REPLACE} as the name placeholder. */
	private final String libFormat;
	/** Directory where kernel data files (e.g., weight dumps) are written; may be null. */
	private final String dataDir;

	/** Fixed C header prepended to every generated source file, including standard includes and the PI constant. */
	private final String header;

	/**
	 * Creates a native compiler that generates and compiles C kernel code via JNI.
	 *
	 * @param precision Numeric precision used for generated type names and constants
	 * @param libraryGenerator Generator that invokes the native compiler and linker
	 * @param libDir Directory for generated C sources and compiled shared libraries
	 * @param libFormat Filename format string for output libraries (use {@code %NAME%} as placeholder)
	 * @param dataDir Directory for kernel data files; may be null if data persistence is not needed
	 * @param cl If true, includes the OpenCL header in generated source files
	 */
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

	/** Returns the numeric precision used when generating kernel source code. */
	public Precision getPrecision() { return precision; }

	/** Returns the directory path where compiled shared libraries and C sources are written. */
	public String getLibraryDirectory() { return libDir; }

	/** Returns the directory path where kernel data files are written; may be null. */
	public String getDataDirectory() { return dataDir; }

	/**
	 * Creates and returns a new uniquely named subdirectory under the data directory.
	 *
	 * <p>The directory is created immediately on disk. Used to store data files
	 * associated with a specific kernel invocation.</p>
	 *
	 * @return New {@link File} for the reserved subdirectory
	 */
	public File reserveDataDirectory() {
		File data = new File(getDataDirectory() + "/" + dataCount++);
		if (!data.exists()) {
			data.mkdir();
		}

		return data;
	}

	/**
	 * Returns the absolute path to the C source file for the given class name.
	 *
	 * @param name Class name (used as filename stem)
	 * @return Absolute path to the {@code .c} source file
	 */
	protected String getInputFile(String name) {
		return libDir + "/" + name + ".c";
	}

	/**
	 * Returns the absolute path to the output file (shared library or object) for the given class name.
	 *
	 * @param name Class name (used as filename stem)
	 * @param lib If true, format the filename as a shared library using {@link #libFormat}; otherwise use name directly
	 * @return Absolute path to the output file
	 */
	protected String getOutputFile(String name, boolean lib) {
		if (lib) {
			return libDir + "/" + libFormat.replaceAll(LIB_NAME_REPLACE, name);
		} else {
			return libDir + "/" + name;
		}
	}

	/**
	 * Reserves a unique pre-generated operation class as a compilation target.
	 *
	 * <p>Loads a pre-generated class named {@code GeneratedOperationN} (where N is a sequential counter)
	 * via reflection. These classes are generated at build time and act as empty stubs that native
	 * code is compiled into.</p>
	 *
	 * @return A {@link BaseGeneratedOperation} instance acting as the compilation target slot
	 * @throws HardwareException if the class cannot be found or instantiated
	 */
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

	/**
	 * Compiles and loads native code for the given instruction set target.
	 *
	 * <p>Delegates to {@link #compileAndLoad(Class, String)} using the target's class.</p>
	 *
	 * @param target The {@link NativeInstructionSet} whose class will hold the compiled function
	 * @param code C source code to compile
	 */
	public void compile(NativeInstructionSet target, String code) {
		compileAndLoad(target.getClass(), code);
	}

	/**
	 * Compiles native code for the given class and returns the path to the compiled library.
	 *
	 * @param target Class used as the compilation target (its name becomes the filename stem)
	 * @param code C source code to compile
	 * @return Absolute path to the compiled shared library
	 */
	public String compile(Class target, String code) {
		return compile(target.getName(), code, true);
	}

	/**
	 * Writes C source to disk and invokes the native compiler toolchain.
	 *
	 * <p>Writes the fixed header followed by {@code code} to a {@code .c} file in
	 * {@link #libDir}, then calls the {@link LinkedLibraryGenerator} to produce the
	 * output file.</p>
	 *
	 * @param name Stem name used for the source and output filenames
	 * @param code C source code (without headers)
	 * @param lib If true, format the output as a shared library; otherwise as a plain object
	 * @return Absolute path to the compiled output file
	 * @throws HardwareException if I/O or compilation fails
	 */
	public String compile(String name, String code, boolean lib) {
		if (HardwareOperator.enableVerboseLog) {
			log("Compiling native code for " + name + "\nSource:\n" + code);
		} else if (enableVerbose) {
			log("Compiling native code for " + name);
		}

		try (FileOutputStream out = new FileOutputStream(getInputFile(name));
				BufferedWriter buf = new BufferedWriter(new OutputStreamWriter(out))) {
			buf.write(header);
			buf.write(code);
		} catch (IOException e) {
			throw new HardwareException(e.getMessage(), new UnsupportedOperationException(e));
		}

		String result = getOutputFile(name, lib);
		libraryGenerator.generateLibrary(getInputFile(name), result, runner(name));

		if (enableVerbose) log("Native code compiled for " + name);
		return result;
	}

	/**
	 * Compiles native code for the given class, then loads the resulting shared library into the JVM.
	 *
	 * <p>If instruction set monitoring is enabled and the code is large enough, writes the source
	 * to the configured monitoring output directory before compiling.</p>
	 *
	 * @param target Class used as the compilation target
	 * @param code C source code to compile and load
	 * @throws HardwareException if compilation or library loading fails
	 */
	public void compileAndLoad(Class target, String code) {
		if (HardwareOperator.enableInstructionSetMonitoring ||
				(HardwareOperator.enableLargeInstructionSetMonitoring && code.length() > 50000)) {
			String name = "jni_instruction_set_" + (monitorOutputCount++) + ".c";

			try {
				Path outputDir = Path.of(HardwareOperator.instructionSetOutputDir);
				Files.createDirectories(outputDir);
				Files.writeString(outputDir.resolve(name), code);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}

			ScopeSettings.printStats();
			log("Wrote " + HardwareOperator.instructionSetOutputDir + "/" + name);
		}

		long start = System.nanoTime();

		try {
			String name = compile(target, code);
			if (enableVerbose) log("Loading native library " + name);
			System.load(name);
			if (enableVerbose) log("Loaded native library " + name);
		} finally {
			compileTime.addEntry(System.nanoTime() - start);
		}
	}

	/**
	 * Returns a {@link Consumer} that runs a native compiler command and validates its exit code.
	 *
	 * <p>The consumer starts the process using {@link ProcessBuilder}, inherits its I/O, waits for
	 * completion, and throws a {@link HardwareException} if the exit value is non-zero.</p>
	 *
	 * @param name Name of the compilation target, included in error messages
	 * @return Consumer that accepts a compiler command line and runs it
	 */
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

	@Override
	public void destroy() {
		// TODO
	}

	@Override
	public Console console() { return Hardware.console; }

	/**
	 * Creates a factory for {@link NativeCompiler} instances with the given precision and CL flag.
	 *
	 * <p>Reads compiler configuration from system properties and environment variables:
	 * {@code AR_HARDWARE_LIB_FORMAT}, {@code AR_HARDWARE_NATIVE_COMPILER}, {@code AR_HARDWARE_NATIVE_LINKER}.</p>
	 *
	 * @param precision Numeric precision for generated kernel code
	 * @param cl If true, include the OpenCL header in generated source files
	 * @return Factory that constructs configured {@link NativeCompiler} instances
	 */
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

			if (libDir == null) {
				libDir = SystemUtils.getExtensionsPath().toFile().getPath();
			}

			File ld = new File(libDir);
			if (!ld.exists()) ld.mkdir();

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
										ld.getAbsolutePath(), libFormat, data, cl);
		};
	}

	/**
	 * The total number of {@link NativeInstructionSet}s that have been compiled
	 * by instances of {@link NativeCompiler}.
	 */
	public static long getTotalInstructionSets() { return runnableCount; }
}
