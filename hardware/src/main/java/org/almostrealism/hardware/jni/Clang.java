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
	private String linker;

	public Clang() {
		this("gcc", true);
	}

	public Clang(String path, boolean localToolchain) {
		super(path, SystemUtils.isMacOS() ? "dynamiclib" : "shared", localToolchain);
	}

	public String getLinker() {
		return linker;
	}

	public void setLinker(String linker) {
		File ld = new File(linker);

		if (ld.exists()) {
			this.linker = ld.getAbsolutePath();
		} else {
			this.linker = linker;
		}
	}

	@Override
	protected void addLinker(List<String> command) {
		if (linker != null) {
			command.add("-fuse-ld=" + linker);
		}
	}
}
