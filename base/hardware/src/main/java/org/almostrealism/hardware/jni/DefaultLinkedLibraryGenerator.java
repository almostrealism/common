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
 * Default {@link LinkedLibraryGenerator} that delegates to a {@link CompilerCommandProvider}.
 *
 * <p>{@link DefaultLinkedLibraryGenerator} implements the standard compilation flow:
 * get compiler command, execute via provided executor.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * CompilerCommandProvider provider = new Clang();
 * LinkedLibraryGenerator generator = new DefaultLinkedLibraryGenerator(provider);
 *
 * generator.generateLibrary(
 *     "code.c",
 *     "lib.so",
 *     command -> new ProcessBuilder(command).inheritIO().start().waitFor()
 * );
 * }</pre>
 *
 * @see LinkedLibraryGenerator
 * @see CompilerCommandProvider
 */
public class DefaultLinkedLibraryGenerator implements LinkedLibraryGenerator {
	/** The compiler command provider for generating compilation commands. */
	private CompilerCommandProvider commandProvider;

	/**
	 * Creates a new linked library generator with the specified command provider.
	 *
	 * @param commandProvider the provider for compiler commands
	 */
	public DefaultLinkedLibraryGenerator(CompilerCommandProvider commandProvider) {
		this.commandProvider = commandProvider;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Generates a shared library by delegating to the command provider to obtain
	 * the compiler command, then executing it via the provided executor.</p>
	 */
	@Override
	public void generateLibrary(String inputFile, String outputFile, Consumer<List<String>> commandExecutor) {
		commandExecutor.accept(commandProvider.getCommand(inputFile, outputFile, true));
	}
}
