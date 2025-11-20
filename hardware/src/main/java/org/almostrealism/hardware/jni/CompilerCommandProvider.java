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

import java.util.List;

/**
 * Interface for generating native compiler command-line invocations.
 *
 * <p>{@link CompilerCommandProvider} abstracts the specifics of different C/C++ compilers
 * (Clang, GCC, ICC) by providing a method to generate the command array needed to invoke
 * the compiler with appropriate flags for building shared libraries or executables.</p>
 *
 * <h2>Command Generation</h2>
 *
 * <p>Implementations return a {@link List} of strings suitable for {@link ProcessBuilder}:</p>
 * <pre>{@code
 * CompilerCommandProvider clang = new Clang();
 * List<String> command = clang.getCommand("input.c", "output.so", true);
 *
 * // Example result:
 * // ["clang", "-shared", "-fPIC", "-O3", "-o", "output.so", "input.c"]
 *
 * // Execute via ProcessBuilder:
 * Process process = new ProcessBuilder(command).start();
 * }</pre>
 *
 * <h2>Library vs Executable</h2>
 *
 * <p>The {@code lib} parameter determines the output type:</p>
 * <pre>{@code
 * // Shared library: adds -shared flag
 * command = provider.getCommand("code.c", "lib.so", true);
 * // -> ["clang", "-shared", "-fPIC", ..., "code.c"]
 *
 * // Executable: no -shared flag
 * command = provider.getCommand("code.c", "program", false);
 * // -> ["clang", "-O3", ..., "code.c"]
 * }</pre>
 *
 * <h2>Common Implementations</h2>
 *
 * <ul>
 *   <li><strong>{@link Clang}:</strong> Clang/LLVM compiler with optional custom linker</li>
 *   <li><strong>DefaultCompilerCommandProvider:</strong> Wrapper for arbitrary compiler paths</li>
 *   <li><strong>{@link LlvmCommandProvider}:</strong> LLVM toolchain integration</li>
 * </ul>
 *
 * <h2>Integration with NativeCompiler</h2>
 *
 * <pre>{@code
 * CompilerCommandProvider provider = new Clang();
 * LinkedLibraryGenerator generator = new DefaultLinkedLibraryGenerator(provider);
 * NativeCompiler compiler = new NativeCompiler(precision, generator, ...);
 *
 * // When compiling:
 * // 1. NativeCompiler writes C source
 * // 2. Calls generator.generateLibrary()
 * // 3. Generator calls provider.getCommand()
 * // 4. Command executed via ProcessBuilder
 * }</pre>
 *
 * @see Clang
 * @see DefaultLinkedLibraryGenerator
 * @see NativeCompiler
 */
public interface CompilerCommandProvider {
	List<String> getCommand(String inputFile, String outputFile, boolean lib);
}
