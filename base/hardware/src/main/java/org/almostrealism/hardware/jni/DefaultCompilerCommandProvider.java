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

import java.util.ArrayList;
import java.util.List;

/**
 * Simple {@link CompilerCommandProvider} for arbitrary compiler executables without flag customization.
 *
 * <p>{@link DefaultCompilerCommandProvider} provides minimal command generation for compilers
 * that don't require LLVM-specific flags. Useful for wrapper scripts or custom build systems.</p>
 *
 * <h2>Command Format</h2>
 *
 * <pre>
 * [executable] [input] [output]
 *
 * // Or if script format:
 * [executable] [script] [input] [output]
 * </pre>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * CompilerCommandProvider provider = new DefaultCompilerCommandProvider(
 *     "custom_compiler.sh",  // For libraries
 *     "custom_linker.sh"     // For executables
 * );
 *
 * List<String> command = provider.getCommand("input.c", "output.so", true);
 * // -> ["custom_compiler.sh", "input.c", "output.so"]
 * }</pre>
 *
 * @see CompilerCommandProvider
 * @see Clang
 */
public class DefaultCompilerCommandProvider implements CompilerCommandProvider {
	/** The executable to use for library compilation, or {@code null} if using libCompiler directly. */
	private String libExecutable;

	/** The executable to use for executable compilation, or {@code null} if using exeCompiler directly. */
	private String exeExecutable;

	/** The compiler script or path for library compilation. */
	private String libCompiler;

	/** The compiler script or path for executable compilation. */
	private String exeCompiler;

	/**
	 * Creates a new compiler command provider with the specified compilers.
	 *
	 * <p>If a compiler path contains a dot (e.g., "compile.sh"), the extension
	 * is treated as the executable name and the full path as a script argument.</p>
	 *
	 * @param libCompiler the compiler script or path for shared library compilation
	 * @param exeCompiler the compiler script or path for executable compilation
	 */
	public DefaultCompilerCommandProvider(String libCompiler, String exeCompiler) {
		if (libCompiler != null) {
			this.libExecutable = libCompiler.contains(".") ? libCompiler.substring(libCompiler.lastIndexOf(".") + 1) : null;
		}

		if (exeCompiler != null) {
			this.exeExecutable = exeCompiler.contains(".") ? exeCompiler.substring(exeCompiler.lastIndexOf(".") + 1) : null;
		}

		this.libCompiler = libCompiler;
		this.exeCompiler = exeCompiler;
	}

	/**
	 * Returns the executable to invoke for compilation.
	 *
	 * @param lib true for library compilation, false for executable compilation
	 * @return the executable command or path
	 */
	protected String getExecutable(boolean lib) {
		if (lib) {
			if (libExecutable != null) return libExecutable;
			return libCompiler;
		} else {
			if (exeExecutable != null) return exeExecutable;
			return exeCompiler;
		}
	}

	/**
	 * Returns the arguments to pass to the compiler executable.
	 *
	 * @param inputFile  the input source file path
	 * @param outputFile the output file path
	 * @param lib        true for library compilation, false for executable compilation
	 * @return the list of arguments (may include script path, input, and output)
	 */
	protected List<String> getArguments(String inputFile, String outputFile, boolean lib) {
		List<String> command = new ArrayList<>();
		if (lib) {
			if (libExecutable != null) command.add(libCompiler);
		} else {
			if (exeExecutable != null) command.add(exeCompiler);
		}
		command.add(inputFile);
		command.add(outputFile);
		return command;
	}

	/** {@inheritDoc} */
	@Override
	public List<String> getCommand(String inputFile, String outputFile, boolean lib) {
		List<String> command = new ArrayList<>();
		command.add(getExecutable(lib));
		command.addAll(getArguments(inputFile, outputFile, lib));
		return command;
	}
}
