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
	public static boolean enableVerbose = SystemUtils.isEnabled("AR_HARDWARE_COMPILER_LOGGING").orElse(false);

	public static TimingMetric compileTime = Hardware.console.timing("jniCompile");

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
	private static int monitorOutputCount;

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

	public void compileAndLoad(Class target, String code) {
		if (HardwareOperator.enableInstructionSetMonitoring ||
				(HardwareOperator.enableLargeInstructionSetMonitoring && code.length() > 50000)) {
			String name = "jni_instruction_set_" + (monitorOutputCount++) + ".c";

			try {
				Files.writeString(Path.of("results/" + name), code);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}

			ScopeSettings.printStats();
			log("Wrote " + name);
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
