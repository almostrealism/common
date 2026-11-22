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

import org.almostrealism.hardware.jni.Clang;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

/**
 * Verifies that the bracket nesting depth passed to clang is an explicit, optional setting.
 *
 * <p>With {@code AR_HARDWARE_NATIVE_BRACKET_DEPTH} unset, no {@code -fbracket-depth} flag is
 * passed and clang's own limit applies, so over-deep generated source fails loudly. With it set,
 * the flag carries the configured value and clang enforces exactly that limit.</p>
 */
public class ClangBracketDepthTest extends TestSuiteBase {
	/** A deliberately low depth, so honouring it is observable with shallow source. */
	private static final int CONFIGURED_DEPTH = 32;

	/** Nesting depth of the generated test source, beyond {@link #CONFIGURED_DEPTH}. */
	private static final int NESTING = 64;

	/** With no bracket depth configured, the compile command carries no bracket-depth flag. */
	@Test(timeout = 60000)
	public void unsetDepthPassesNoFlag() {
		OptionalInt previous = Clang.getBracketDepth();

		try {
			Clang.setBracketDepth(OptionalInt.empty());
			List<String> command = new Clang().getCommand("input.c", "output", true);
			Assert.assertTrue("unexpected -fbracket-depth in " + command,
					command.stream().noneMatch(arg -> arg.startsWith("-fbracket-depth")));
		} finally {
			Clang.setBracketDepth(previous);
		}
	}

	/**
	 * With a bracket depth configured but a compiler that cannot be identified as clang (here a
	 * path that cannot be run at all, so {@code --version} yields nothing), the flag is never
	 * passed, on the first command or on any later one. This exercises the non-clang branch on
	 * every host, including hosts whose default compiler is clang.
	 */
	@Test(timeout = 60000)
	public void unidentifiedCompilerNeverReceivesFlag() {
		OptionalInt previous = Clang.getBracketDepth();

		try {
			Clang.setBracketDepth(OptionalInt.of(CONFIGURED_DEPTH));
			Clang compiler = new Clang("/nonexistent/ar-bracket-depth-compiler", true);

			Assert.assertEquals("", compiler.getVersion());
			Assert.assertFalse(compiler.isClangFrontend());

			for (int i = 0; i < 3; i++) {
				List<String> command = compiler.getCommand("input.c", "output", true);
				Assert.assertEquals("/nonexistent/ar-bracket-depth-compiler", command.get(0));
				Assert.assertTrue("unidentified compiler must not receive -fbracket-depth: " + command,
						command.stream().noneMatch(arg -> arg.startsWith("-fbracket-depth")));
			}
		} finally {
			Clang.setBracketDepth(previous);
		}
	}

	/**
	 * With a bracket depth configured, a clang command carries {@code -fbracket-depth} with that
	 * value and clang enforces it: source nested {@value #NESTING} deep is rejected against a
	 * configured limit of {@value #CONFIGURED_DEPTH}, and the error names that limit. A compiler
	 * that is not clang (GCC rejects the flag) never receives it.
	 */
	@Test(timeout = 120000)
	public void configuredDepthIsPassedAndHonoured() throws IOException, InterruptedException {
		OptionalInt previous = Clang.getBracketDepth();
		Path dir = Files.createTempDirectory("bracket-depth");
		Path source = dir.resolve("nested.c");
		Path output = dir.resolve("libnested.so");

		try {
			Clang.setBracketDepth(OptionalInt.of(CONFIGURED_DEPTH));
			Clang clang = new Clang();
			List<String> command = clang.getCommand(source.toString(), output.toString(), true);

			if (!clang.isClangFrontend()) {
				Assert.assertTrue("non-clang command must not carry -fbracket-depth: " + command,
						command.stream().noneMatch(arg -> arg.startsWith("-fbracket-depth")));
				return;
			}

			String flag = "-fbracket-depth=" + CONFIGURED_DEPTH;
			Assert.assertTrue("clang command is missing " + flag + ": " + command, command.contains(flag));

			Files.writeString(source, "double nested(double x) { return " + "(".repeat(NESTING) + "x" +
					" * x)".repeat(NESTING) + "; }\n", StandardCharsets.UTF_8);

			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = process.waitFor();

			Assert.assertNotEquals("compile should exceed the configured limit: " + log, 0, exit);
			Assert.assertTrue("compile did not fail on the configured limit: " + log,
					log.contains("maximum of " + CONFIGURED_DEPTH));
		} finally {
			Clang.setBracketDepth(previous);
			Files.deleteIfExists(source);
			Files.deleteIfExists(output);
			Files.deleteIfExists(dir);
		}
	}
}
