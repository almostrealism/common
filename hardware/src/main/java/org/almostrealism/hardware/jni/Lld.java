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

/**
 * {@link LlvmCommandProvider} implementation for LLVM's LLD linker.
 *
 * <p>{@link Lld} provides a simple wrapper for LLVM's LLD linker, which is faster than
 * traditional linkers (ld, gold) for large projects. Uses {@code -dynamiclib} flag.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * Lld lld = new Lld();
 * List<String> command = lld.getCommand("input.c", "output.dylib", true);
 *
 * // Generated command:
 * // lld -O3 -I/path/to/jni/include -dynamiclib input.c -o output.dylib
 * }</pre>
 *
 * <h2>Custom Path</h2>
 *
 * <pre>{@code
 * // From PATH:
 * Lld local = new Lld();
 *
 * // Custom location:
 * Lld custom = new Lld("/opt/llvm/bin/lld", false);
 * }</pre>
 *
 * @see LlvmCommandProvider
 * @see Clang
 */
public class Lld extends LlvmCommandProvider {

	/**
	 * Creates a new LLD instance with default settings.
	 * Uses "lld" command from the system PATH with dynamiclib format.
	 */
	public Lld() {
		this("lld", true);
	}

	/**
	 * Creates a new LLD instance with the specified path.
	 *
	 * @param path           the linker command or path
	 * @param localToolchain true to use the local toolchain from PATH,
	 *                       false to use an absolute path
	 */
	public Lld(String path, boolean localToolchain) {
		super(path, "dynamiclib", localToolchain);
	}
}
