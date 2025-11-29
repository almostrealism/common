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

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Base {@link CompilerCommandProvider} for LLVM-based compilers (Clang, GCC with LLVM backend).
 *
 * <p>{@link LlvmCommandProvider} provides common command-line generation logic for compilers
 * that use LLVM infrastructure. It handles:</p>
 * <ul>
 *   <li><strong>Include paths:</strong> Automatic JNI header discovery from JAVA_HOME</li>
 *   <li><strong>Math optimization:</strong> Configurable optimization levels (-O0, -O3, -ffast-math)</li>
 *   <li><strong>Platform detection:</strong> macOS vs Linux flag differences</li>
 *   <li><strong>OpenCL integration:</strong> Optional OpenCL framework linking</li>
 * </ul>
 *
 * <h2>Generated Command Structure</h2>
 *
 * <pre>
 * [compiler] [linker-flags] [opt-flags] [includes] -shared/-dynamiclib [input] -o [output]
 *
 * Example (macOS):
 * clang -O3 -I/Library/Java/.../include -I/Library/Java/.../include/darwin
 *       -dynamiclib input.c -o output.dylib
 *
 * Example (Linux):
 * gcc -O3 -I/usr/lib/jvm/.../include -I/usr/lib/jvm/.../include/linux
 *     -shared -fPIC input.c -o output.so
 * </pre>
 *
 * <h2>Math Optimization Levels</h2>
 *
 * <p>Configured via {@code AR_HARDWARE_MATH_OPT} environment variable:</p>
 * <table>
 *   <caption>Math optimization configuration</caption>
 *   <tr>
 *     <th>Value</th>
 *     <th>Flags</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>enabled (default)</td>
 *     <td>-O3</td>
 *     <td>Standard optimization, IEEE 754 compliant</td>
 *   </tr>
 *   <tr>
 *     <td>aggressive</td>
 *     <td>-O3 -ffast-math</td>
 *     <td>Fast math, may break IEEE 754 compliance</td>
 *   </tr>
 *   <tr>
 *     <td>none/disabled</td>
 *     <td>-O0</td>
 *     <td>No optimization, for debugging</td>
 *   </tr>
 * </table>
 *
 * <h2>JNI Include Discovery</h2>
 *
 * <p>Automatically adds JNI headers from {@code JAVA_HOME}:</p>
 * <pre>{@code
 * // Adds:
 * -I$JAVA_HOME/include
 * -I$JAVA_HOME/include/darwin    (macOS)
 * -I$JAVA_HOME/include/linux     (Linux)
 * -I$JAVA_HOME/include/win32     (Windows)
 * }</pre>
 *
 * <h2>Custom Include/Library Paths</h2>
 *
 * <p>For non-local toolchains, additional paths can be configured:</p>
 * <pre>{@code
 * // Via environment variables:
 * AR_HARDWARE_NATIVE_INCLUDES=Contents/Resources/include
 * AR_HARDWARE_NATIVE_LIBS=Contents/Resources/lib
 *
 * // Adds:
 * -IContents/Resources/include
 * -LContents/Resources/lib
 * }</pre>
 *
 * <h2>Platform-Specific Flags</h2>
 *
 * <ul>
 *   <li><strong>macOS:</strong> Uses {@code -dynamiclib}, no {@code -fPIC}</li>
 *   <li><strong>Linux:</strong> Uses {@code -shared -fPIC}</li>
 * </ul>
 *
 * <h2>OpenCL Integration</h2>
 *
 * <p>If {@link CLDataContext#enableClNative} is true, adds OpenCL framework:</p>
 * <pre>{@code
 * // macOS only:
 * -framework OpenCL
 * }</pre>
 *
 * <h2>Subclass Customization</h2>
 *
 * <p>Subclasses can override {@link #addLinker(List)} to inject linker flags:</p>
 * <pre>{@code
 * public class Clang extends LlvmCommandProvider {
 *     private String linker;
 *
 *     @Override
 *     protected void addLinker(List<String> command) {
 *         if (linker != null) {
 *             command.add("-fuse-ld=" + linker);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Local vs Non-Local Toolchains</h2>
 *
 * <pre>{@code
 * // Local toolchain (e.g., "gcc" from PATH):
 * new Clang("gcc", true);
 * // -> Uses relative path, no custom includes
 *
 * // Non-local toolchain (e.g., "/opt/llvm/bin/clang"):
 * new Clang("/opt/llvm/bin/clang", false);
 * // -> Converts to absolute path, adds custom includes
 * }</pre>
 *
 * @see Clang
 * @see CompilerCommandProvider
 * @see NativeCompiler
 */
public abstract class LlvmCommandProvider implements CompilerCommandProvider, ConsoleFeatures {
	/** Custom include path for non-local toolchains, configured via AR_HARDWARE_NATIVE_INCLUDES. */
	private static String includePath = SystemUtils.getProperty("AR_HARDWARE_NATIVE_INCLUDES", "Contents/Resources/include");
	/** Custom library path for non-local toolchains, configured via AR_HARDWARE_NATIVE_LIBS. */
	private static String libPath = SystemUtils.getProperty("AR_HARDWARE_NATIVE_LIBS", "Contents/Resources/lib");
	/** The math optimization level, configured via AR_HARDWARE_MATH_OPT environment variable. */
	private static MathOptLevel optLevel;

	static {
		String opt = SystemUtils.getProperty("AR_HARDWARE_MATH_OPT", "enabled");

		if (opt.equalsIgnoreCase("enabled")) {
			optLevel = MathOptLevel.FAST;
		} else if (opt.equalsIgnoreCase("aggressive")) {
			optLevel = MathOptLevel.AGGRESSIVE;
		} else {
			if (!opt.equalsIgnoreCase("none") &&
					!opt.equalsIgnoreCase("disabled")) {
				Hardware.console.warn(opt +
						" is not a recognized math optimization level, disabling optimization");
			}

			optLevel = MathOptLevel.NONE;
		}
	}

	/** The path to the compiler executable. */
	private String path;
	/** The shared library flag ("shared" for Linux, "dynamiclib" for macOS). */
	private String cmd;
	/** List of include path flags (-I...) for JNI headers and custom includes. */
	private List<String> includes;
	/** Whether using a compiler from the system PATH (true) or a custom toolchain path (false). */
	private boolean localToolchain;

	/**
	 * Creates a new LLVM command provider with the specified compiler path and command.
	 *
	 * @param path           The path to the compiler executable (relative for local, absolute for non-local)
	 * @param command        The shared library flag to use ("shared" for Linux, "dynamiclib" for macOS)
	 * @param localToolchain {@code true} if using a compiler from PATH, {@code false} for custom toolchain paths
	 */
	public LlvmCommandProvider(String path, String command, boolean localToolchain) {
		this.path = localToolchain ? path : new File(path).getAbsolutePath();
		this.cmd = command;
		this.localToolchain = localToolchain;
	}

	/**
	 * Initializes the include and library paths for JNI compilation.
	 *
	 * <p>This method discovers JNI headers from {@code JAVA_HOME} and adds
	 * platform-specific include directories. For non-local toolchains, it also
	 * adds custom include and library paths configured via environment variables.</p>
	 */
	protected void init() {
		includes = new ArrayList<>();

		if (!localToolchain) {
			includes.add("-I" + includePath);
		}

		String baseInclude = System.getProperty("java.home") + "/include";
		includes.add("-I" + baseInclude);

		for (File child : new File(baseInclude).listFiles()) {
			if (child.isDirectory()) {
				includes.add("-I" + child.getAbsolutePath());
			}
		}

		if (!localToolchain) {
			includes.add("-L" + libPath);
		}
	}

	/**
	 * Hook for subclasses to add linker flags to the command.
	 *
	 * @param command The command list to add linker flags to
	 */
	protected void addLinker(List<String> command) { }

	/**
	 * Returns the configured math optimization level.
	 *
	 * @return the math optimization level
	 */
	public MathOptLevel getMathOptLevel() { return optLevel; }

	/** {@inheritDoc} */
	@Override
	public List<String> getCommand(String inputFile, String outputFile, boolean lib) {
		if (includes == null) init();

		List<String> command = new ArrayList<>();
		command.add(path);
		addLinker(command);

		if (!localToolchain) command.add("-w");

		if (CLDataContext.enableClNative) {
			command.add("-framework");
			command.add("OpenCL");
		}

		command.addAll(getMathOptLevel().getFlags());
		command.addAll(includes);

		command.add("-" + cmd);
		if (!SystemUtils.isMacOS()) command.add("-fPIC");
		command.add(inputFile);
		command.add("-o");
		command.add(outputFile);
		return command;
	}

	/** {@inheritDoc} */
	@Override
	public Console console() {
		return Hardware.console;
	}

	/**
	 * Math optimization levels for native code compilation.
	 *
	 * <p>These levels control the trade-off between compilation speed,
	 * IEEE 754 compliance, and runtime performance.</p>
	 */
	public enum MathOptLevel {
		/** Maximum optimization with fast-math (may break IEEE 754 compliance). */
		AGGRESSIVE,
		/** Standard optimization (-O3), IEEE 754 compliant. */
		FAST,
		/** No optimization (-O0), for debugging. */
		NONE;

		/**
		 * Returns the compiler flags for this optimization level.
		 *
		 * @return A list of compiler flags (e.g., "-O3", "-ffast-math")
		 */
		public List<String> getFlags() {
			switch (this) {
				case AGGRESSIVE: return List.of("-O3", "-ffast-math");
				case FAST: return List.of("-O3");
				default: return List.of("-O0");
			}
		}
	}
}

