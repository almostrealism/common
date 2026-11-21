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
import java.util.Locale;
import java.util.OptionalInt;

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
 * <h2>Bracket Nesting Depth</h2>
 *
 * <p>Clang rejects source whose brackets nest deeper than a built-in limit
 * ({@code bracket nesting level exceeded maximum of N}). By default the framework passes no
 * {@code -fbracket-depth} flag, so clang's own limit applies and over-deep generated source
 * fails loudly. Setting {@code AR_HARDWARE_NATIVE_BRACKET_DEPTH} passes
 * {@code -fbracket-depth=<value>} instead. This exists only as an escape hatch: generated source
 * nested that deeply is a symptom of a defect in how an expression tree was built (for example,
 * nested products that were never merged into one product), and raising the limit hides that
 * defect. The right response to a nesting failure is to find out why the expression is that
 * deep, not to set this.</p>
 *
 * @see LlvmCommandProvider
 * @see Lld
 */
public class Clang extends LlvmCommandProvider {
	/**
	 * The bracket nesting depth passed to clang with {@code -fbracket-depth}, configured via
	 * {@code AR_HARDWARE_NATIVE_BRACKET_DEPTH}, or empty (the default) to pass no flag and leave
	 * clang's own limit in place. An escape hatch only; see the class documentation.
	 */
	private static OptionalInt bracketDepth = SystemUtils.getInt("AR_HARDWARE_NATIVE_BRACKET_DEPTH");

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
	 * Adds {@code -fbracket-depth} when a bracket depth has been configured (see
	 * {@link #getBracketDepth()}) and the compiler identifies itself as clang. GCC rejects the
	 * flag, so it is never passed to GCC. When no depth is configured nothing is added and clang's
	 * own default limit applies, which is the intended behavior: over-deep generated source should
	 * fail rather than be accepted.
	 *
	 * @param command the command list to add the flag to
	 */
	@Override
	protected void appendFrontendOptions(List<String> command) {
		if (bracketDepth.isEmpty()) return;

		if (isClangFrontend()) {
			command.add("-fbracket-depth=" + bracketDepth.getAsInt());
		} else {
			warn("AR_HARDWARE_NATIVE_BRACKET_DEPTH is ignored because the compiler is not clang");
		}
	}

	/**
	 * Returns the configured bracket nesting depth passed to clang with {@code -fbracket-depth},
	 * read from {@code AR_HARDWARE_NATIVE_BRACKET_DEPTH}. It is empty unless explicitly set, in
	 * which case clang's own default limit applies.
	 *
	 * <p>This is an escape hatch, not a tuning knob. Deeply nested generated source means an
	 * expression tree was built badly, and raising the limit hides that defect; when clang reports
	 * that the bracket nesting limit was exceeded, find out why the expression is that deep.</p>
	 *
	 * @return the configured depth, or empty when no flag is passed
	 */
	public static OptionalInt getBracketDepth() {
		return bracketDepth;
	}

	/**
	 * Sets the bracket nesting depth for subsequent compilations, overriding
	 * {@code AR_HARDWARE_NATIVE_BRACKET_DEPTH}. Pass {@link OptionalInt#empty()} to pass no
	 * {@code -fbracket-depth} flag and leave clang's own limit in place. The same caution as
	 * {@link #getBracketDepth()} applies.
	 *
	 * @param depth the depth to pass to clang, or empty for clang's default
	 */
	public static void setBracketDepth(OptionalInt depth) {
		bracketDepth = depth;
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
