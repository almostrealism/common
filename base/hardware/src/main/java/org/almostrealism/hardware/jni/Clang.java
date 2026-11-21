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

import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.util.List;
import java.util.Locale;

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
	/** The bracket nesting limit clang applies when {@code -fbracket-depth} is not given. */
	public static final int DEFAULT_BRACKET_DEPTH = 256;

	/**
	 * The bracket nesting levels allowed per level of expression depth: one expression node can
	 * render as a parenthesized operation wrapped in a cast and a function call.
	 */
	public static final int BRACKETS_PER_EXPRESSION_LEVEL = 4;

	/** The custom linker name or path to use with {@code -fuse-ld}, or null for default linker. */
	private String linker;

	/** Whether the compiler identifies itself as clang, or null until it has been checked. */
	private Boolean clangFrontend;

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
	protected void appendLinkerOptions(List<String> command) {
		if (linker != null) {
			command.add("-fuse-ld=" + linker);
		}
	}

	/**
	 * Raises clang's bracket nesting limit so that it accepts every expression the framework
	 * permits.
	 *
	 * <p>Generated source nests parentheses as deeply as the expression tree it renders: a product
	 * of several hundred factors becomes several hundred nested parentheses. Older clang releases
	 * reject anything nested more than 256 deep by default ({@code bracket nesting level exceeded
	 * maximum of 256}), while newer ones accept it, so the same kernel compiles on one host and
	 * fails on another. The framework bounds expression depth by {@link ScopeSettings#maxDepth},
	 * so the limit passed here is derived from that bound rather than left to the toolchain's
	 * default. The flag is clang-specific, and GCC (which has no such limit) rejects it, so it is
	 * only added when the configured compiler identifies itself as clang.</p>
	 *
	 * @param command the command list to add the flag to
	 */
	@Override
	protected void appendFrontendOptions(List<String> command) {
		if (isClangFrontend()) {
			command.add("-fbracket-depth=" + getBracketDepth());
		}
	}

	/**
	 * Returns the bracket nesting depth passed to clang: {@value #BRACKETS_PER_EXPRESSION_LEVEL}
	 * nesting levels for each level of the deepest expression {@link ScopeSettings#maxDepth}
	 * permits, which covers the parentheses, casts and calls a single expression node renders.
	 *
	 * @return the value for {@code -fbracket-depth}
	 */
	public static int getBracketDepth() {
		return Math.max(DEFAULT_BRACKET_DEPTH, BRACKETS_PER_EXPRESSION_LEVEL * ScopeSettings.maxDepth);
	}

	/**
	 * Returns whether the configured compiler is clang, determined once by running it with
	 * {@code --version}. On macOS the {@code gcc} command is Apple's clang; on Linux it is
	 * usually GCC.
	 *
	 * @return {@code true} if the compiler reports itself as clang
	 */
	public boolean isClangFrontend() {
		if (clangFrontend == null) {
			clangFrontend = getVersion().toLowerCase(Locale.ROOT).contains("clang");
		}

		return clangFrontend;
	}
}
