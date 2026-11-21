/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.hardware.jni.Clang;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that native compilation accepts source nested more deeply than clang's historical
 * default bracket limit of {@value Clang#DEFAULT_BRACKET_DEPTH}.
 *
 * <p>A product of several hundred factors renders as several hundred nested parentheses. Older
 * clang releases reject that by default ({@code bracket nesting level exceeded maximum of 256})
 * while newer ones accept it, so a kernel compiled on one host and failed on another. The
 * {@link Clang} command now raises the limit from the framework's own expression depth bound.</p>
 */
public class ClangBracketDepthTest extends TestSuiteBase {
	/** Nesting depth of the generated test source, well beyond the historical default limit. */
	private static final int NESTING = 600;

	/**
	 * The compile command carries {@code -fbracket-depth} exactly when the compiler is clang,
	 * since GCC rejects the flag, and the depth it requests covers the framework's expression bound.
	 */
	@Test(timeout = 60000)
	public void bracketDepthFlagMatchesCompiler() {
		Clang clang = new Clang();
		List<String> command = clang.getCommand("input.c", "output", true);
		String flag = "-fbracket-depth=" + Clang.getBracketDepth();

		Assert.assertTrue(Clang.getBracketDepth() >= ScopeSettings.maxDepth);
		Assert.assertTrue(Clang.getBracketDepth() > Clang.DEFAULT_BRACKET_DEPTH);

		if (clang.isClangFrontend()) {
			Assert.assertTrue("clang command is missing " + flag + ": " + command, command.contains(flag));
		} else {
			Assert.assertTrue("non-clang command must not carry -fbracket-depth: " + command,
					command.stream().noneMatch(arg -> arg.startsWith("-fbracket-depth")));
		}
	}

	/**
	 * Compiles source nested {@value #NESTING} parentheses deep with the {@link Clang} command,
	 * preceded by an explicit {@code -fbracket-depth=256} that stands in for the default of an older
	 * clang. Clang honours the last occurrence of the flag, so the compile succeeds only if the
	 * command itself raises the limit, which is what an older toolchain needs. A non-clang compiler
	 * (GCC) has no such limit and compiles the same source with the command as built.
	 */
	@Test(timeout = 120000)
	public void deeplyNestedSourceCompilesUnderOldDefaultLimit() throws IOException, InterruptedException {
		Clang clang = new Clang();
		Path dir = Files.createTempDirectory("bracket-depth");
		Path source = dir.resolve("nested.c");
		Path output = dir.resolve("libnested.so");
		Files.writeString(source, "double nested(double x) { return " + "(".repeat(NESTING) + "x" +
				" * x)".repeat(NESTING) + "; }\n", StandardCharsets.UTF_8);

		List<String> command = new ArrayList<>(clang.getCommand(source.toString(), output.toString(), true));
		if (clang.isClangFrontend()) {
			command.add(1, "-fbracket-depth=" + Clang.DEFAULT_BRACKET_DEPTH);
		}

		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();

		try {
			Assert.assertEquals("compile failed: " + log, 0, exit);
			Assert.assertTrue(new File(output.toString()).exists());
		} finally {
			Files.deleteIfExists(source);
			Files.deleteIfExists(output);
			Files.deleteIfExists(dir);
		}
	}
}
