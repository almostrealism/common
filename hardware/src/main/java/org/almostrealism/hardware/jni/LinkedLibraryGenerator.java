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
import java.util.function.Consumer;

/**
 * Interface for orchestrating native library compilation from C source to shared libraries.
 *
 * <p>{@link LinkedLibraryGenerator} coordinates the build process by obtaining compiler
 * commands from a {@link CompilerCommandProvider} and executing them via a provided executor.
 * This abstraction enables different compilation strategies (local toolchain, remote build servers,
 * caching, etc.) without changing the core {@link NativeCompiler} logic.</p>
 *
 * <h2>Compilation Flow</h2>
 *
 * <pre>
 * 1. NativeCompiler writes C source   inputFile (e.g., "code.c")
 * 2. Calls generateLibrary()          generator.generateLibrary(...)
 * 3. Generator gets command           provider.getCommand()
 * 4. Executes via commandExecutor     executor.accept(command)
 * 5. Shared library created           outputFile (e.g., "lib.so")
 * </pre>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * CompilerCommandProvider provider = new Clang();
 * LinkedLibraryGenerator generator = new DefaultLinkedLibraryGenerator(provider);
 *
 * // Command executor: runs commands via ProcessBuilder
 * Consumer<List<String>> executor = command -> {
 *     Process process = new ProcessBuilder(command).inheritIO().start();
 *     process.waitFor();
 *     if (process.exitValue() != 0) {
 *         throw new RuntimeException("Compilation failed");
 *     }
 * };
 *
 * // Generate library
 * generator.generateLibrary(
 *     "/tmp/code.c",
 *     "/tmp/lib.so",
 *     executor
 * );
 * }</pre>
 *
 * <h2>Command Executor Pattern</h2>
 *
 * <p>The {@code commandExecutor} parameter allows flexible execution strategies:</p>
 * <pre>{@code
 * // Local execution (default):
 * executor = cmd -> new ProcessBuilder(cmd).inheritIO().start().waitFor();
 *
 * // With caching:
 * executor = cmd -> {
 *     String cacheKey = hash(cmd);
 *     if (cache.contains(cacheKey)) {
 *         copyFromCache(cacheKey, outputFile);
 *     } else {
 *         runCommand(cmd);
 *         addToCache(cacheKey, outputFile);
 *     }
 * };
 *
 * // Remote build server:
 * executor = cmd -> {
 *     uploadSource(inputFile);
 *     triggerRemoteBuild(cmd);
 *     downloadLibrary(outputFile);
 * };
 * }</pre>
 *
 * <h2>Implementation Example</h2>
 *
 * <pre>{@code
 * public class DefaultLinkedLibraryGenerator implements LinkedLibraryGenerator {
 *     private CompilerCommandProvider provider;
 *
 *     @Override
 *     public void generateLibrary(String input, String output,
 *                                Consumer<List<String>> executor) {
 *         // Get compiler command
 *         List<String> command = provider.getCommand(input, output, true);
 *
 *         // Execute compilation
 *         executor.accept(command);
 *     }
 * }
 * }</pre>
 *
 * <h2>Error Handling</h2>
 *
 * <p>The {@code commandExecutor} is responsible for error handling:</p>
 * <pre>{@code
 * Consumer<List<String>> executor = command -> {
 *     Process p = new ProcessBuilder(command).start();
 *     int exitCode = p.waitFor();
 *
 *     if (exitCode != 0) {
 *         throw new HardwareException("Compiler returned " + exitCode);
 *     }
 * };
 * }</pre>
 *
 * <h2>Integration with NativeCompiler</h2>
 *
 * <pre>{@code
 * NativeCompiler compiler = new NativeCompiler(
 *     precision,
 *     generator,  // LinkedLibraryGenerator
 *     libDir,
 *     libFormat,
 *     dataDir,
 *     clSupport
 * );
 *
 * // When compiling:
 * compiler.compile(target, cCode);
 * // -> Writes C source
 * // -> Calls generator.generateLibrary()
 * // -> Loads resulting library via System.load()
 * }</pre>
 *
 * @see DefaultLinkedLibraryGenerator
 * @see CompilerCommandProvider
 * @see NativeCompiler
 */
public interface LinkedLibraryGenerator {
	/**
	 * Generates a native library from the input file.
	 *
	 * @param inputFile       the source file to compile
	 * @param outputFile      the destination library file
	 * @param commandExecutor consumer that executes the compiler command
	 */
	void generateLibrary(String inputFile, String outputFile, Consumer<List<String>> commandExecutor);
}
