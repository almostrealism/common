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

import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.util.List;

/**
 * {@link LlvmCommandProvider} implementation for Clang/LLVM compiler with optional custom linker support.
 *
 * <p>{@link Clang} extends {@link LlvmCommandProvider} to support Clang-specific features,
 * particularly the ability to use alternative linkers via {@code -fuse-ld}.</p>
 *
 * <h2>Default Configuration</h2>
 *
 * <pre>{@code
 * Clang clang = new Clang();
 * // Uses "gcc" command (actually clang on most systems)
 * // Platform-appropriate library format:
 * //   macOS: -dynamiclib
 * //   Linux: -shared
 * }</pre>
 *
 * <h2>Custom Linker</h2>
 *
 * <p>Supports alternative linkers like LLD (LLVM's linker) or gold:</p>
 * <pre>{@code
 * Clang clang = new Clang();
 * clang.setLinker("lld");
 *
 * // Generated command includes:
 * // clang -fuse-ld=lld ...
 *
 * // Or with absolute path:
 * clang.setLinker("/usr/local/bin/lld");
 * // -> clang -fuse-ld=/usr/local/bin/lld ...
 * }</pre>
 *
 * <h2>Custom Compiler Path</h2>
 *
 * <pre>{@code
 * // Local toolchain (from PATH):
 * Clang local = new Clang("clang-15", true);
 *
 * // Absolute path:
 * Clang custom = new Clang("/opt/llvm/bin/clang", false);
 * }</pre>
 *
 * <h2>Generated Command Example</h2>
 *
 * <pre>
 * macOS:
 * clang -fuse-ld=lld -O3 -I/Library/Java/.../include -dynamiclib code.c -o lib.dylib
 *
 * Linux:
 * clang -fuse-ld=lld -O3 -I/usr/lib/jvm/.../include -shared -fPIC code.c -o lib.so
 * </pre>
 *
 * @see LlvmCommandProvider
 * @see Lld
 */
public class Clang extends LlvmCommandProvider {
	/** The custom linker name or path to use with {@code -fuse-ld}, or null for default linker. */
	private String linker;

	/**
	 * Creates a new Clang instance with default settings.
	 * Uses "gcc" as the compiler command with platform-appropriate
	 * library format (dynamiclib for macOS, shared for Linux).
	 */
	public Clang() {
		this("gcc", true);
	}

	/**
	 * Creates a new Clang instance with the specified compiler path.
	 *
	 * @param path           the compiler command or path
	 * @param localToolchain true to use the local toolchain from PATH,
	 *                       false to use an absolute path
	 */
	public Clang(String path, boolean localToolchain) {
		super(path, SystemUtils.isMacOS() ? "dynamiclib" : "shared", localToolchain);
	}

	/**
	 * Returns the custom linker name or path, or null if using the default linker.
	 *
	 * @return the linker name or path, or null
	 */
	public String getLinker() {
		return linker;
	}

	/**
	 * Sets the linker to use via Clang's {@code -fuse-ld} option.
	 * If the specified path exists as a file, the absolute path is used;
	 * otherwise, the value is passed directly to {@code -fuse-ld}.
	 *
	 * @param linker the linker name (e.g., "lld", "gold") or absolute path
	 */
	public void setLinker(String linker) {
		File ld = new File(linker);

		if (ld.exists()) {
			this.linker = ld.getAbsolutePath();
		} else {
			this.linker = linker;
		}
	}

	/**
	 * Adds the custom linker flag to the command if a linker has been set.
	 *
	 * @param command the command list to add the linker flag to
	 */
	@Override
	protected void addLinker(List<String> command) {
		if (linker != null) {
			command.add("-fuse-ld=" + linker);
		}
	}
}
