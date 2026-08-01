/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Detects reflective method invocation — calling a method through
 * {@code java.lang.reflect} rather than calling it directly.
 *
 * <p>Reflectively calling a method (typically {@code getDeclaredMethod(...)},
 * {@code setAccessible(true)}, {@code invoke(...)}) is almost always a way to
 * reach a method the caller is not supposed to reach — a {@code private} or
 * package-private implementation detail, most often from a test that wants to
 * probe an internal helper. It is never the right tool here:</p>
 *
 * <ul>
 *   <li>It defeats the access modifier the author chose, coupling the caller to
 *       an implementation detail that was deliberately hidden.</li>
 *   <li>It is unchecked at compile time: a rename or signature change turns into
 *       a {@code NoSuchMethodException} at <em>runtime</em> instead of a build
 *       error — the exact failure that motivated this detector
 *       ({@code MixdownManagerPdslAdapter.diagonalMatrix(int, double)} was
 *       removed, and the test that reflected at it kept compiling and failed
 *       only when run).</li>
 *   <li>If a test needs to exercise a method, that method should be visible to
 *       the test (make it package-private and co-locate the test, or expose a
 *       proper API) — not pried open with reflection.</li>
 * </ul>
 *
 * <p>The correct fix is always to call the method directly. If it is not
 * visible, change its visibility (package-private is usually enough) or add a
 * legitimate API; never bypass the access check.</p>
 *
 * <h2>What this detector flags</h2>
 * <ul>
 *   <li>A reflective method lookup: {@code getDeclaredMethod(} or
 *       {@code getDeclaredMethods(} — these resolve a (usually non-public)
 *       method object for the express purpose of invoking it.</li>
 *   <li>A reflective call: {@code .invoke(} in a file that imports
 *       {@code java.lang.reflect.Method} (or {@code java.lang.reflect.*}) — the
 *       {@link java.lang.reflect.Method#invoke} call itself.</li>
 * </ul>
 *
 * <p>What this detector intentionally does <em>not</em> flag: a bare
 * {@code Class.getMethod(...)} that merely <em>returns</em> a {@link
 * java.lang.reflect.Method} descriptor without invoking it (a legitimate
 * factory/plugin registration pattern), {@code java.lang.invoke.MethodHandle}
 * invocation, and any {@code getMethod()} that is not reflection at all (e.g. an
 * HTTP session's request-method accessor). Reflective <em>field</em> access is a
 * separate concern handled elsewhere; this detector is about <em>calling
 * methods</em>.</p>
 *
 * <h2>Scanned files</h2>
 * <p>All {@code .java} sources under the project root, including {@code /test/}
 * sources — reflective invocation is never legitimate in tests either; a test
 * that needs an internal method should be given visible access to it. Build
 * output ({@code /target/}) and the policy framework's own files are excluded so
 * the detector does not flag its own pattern strings.</p>
 *
 * <h2>Detected violation code</h2>
 * <ul>
 *   <li>{@code REFLECTIVE_METHOD_INVOCATION}</li>
 * </ul>
 *
 * @see PolicyViolationDetector
 * @see CodePolicyViolationDetector
 */
public class ReflectiveInvocationDetector extends PolicyViolationDetector {

	/** Short rule code shared by every violation this detector reports. */
	public static final String RULE = "REFLECTIVE_METHOD_INVOCATION";

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public ReflectiveInvocationDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Walks the project tree and scans every {@code .java} file that is not excluded
	 * by {@link #isExcluded(Path)} (build output and the policy framework's own files,
	 * which contain these pattern strings). Test sources are deliberately <em>not</em>
	 * exempt — reflective invocation is the very thing this detector exists to remove
	 * from test code.
	 *
	 * @return this detector for chaining
	 * @throws IOException if directory traversal fails
	 */
	@Override
	public ReflectiveInvocationDetector scan() throws IOException {
		violations.clear();

		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !isExcluded(p))
					.forEach(this::scanFile);
		}

		return this;
	}

	/**
	 * Scans a single file line-by-line, flagging each reflective method lookup and
	 * each reflective {@code invoke(...)} call.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public ReflectiveInvocationDetector scanFile(Path file) {
		try {
			List<String> lines = Files.readAllLines(file);

			boolean reflectImport = false;
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.startsWith("import java.lang.reflect.Method")
						|| trimmed.startsWith("import java.lang.reflect.*")) {
					reflectImport = true;
					break;
				}
			}

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				int lineNum = i + 1;

				String trimmed = line.trim();
				// Skip comment and javadoc lines so prose that merely names these APIs
				// (including this detector's own documentation elsewhere) is not flagged.
				if (trimmed.startsWith("//") || trimmed.startsWith("*")
						|| trimmed.startsWith("/*")) {
					continue;
				}

				if (line.contains("getDeclaredMethod(") || line.contains("getDeclaredMethods(")) {
					violations.add(new Violation(file, lineNum, line, RULE,
							"Reflective method lookup ('getDeclaredMethod'). Calling a method "
									+ "through reflection bypasses its access modifier and is "
									+ "unchecked at compile time (a rename becomes a runtime "
									+ "NoSuchMethodException). Call the method directly; if it is "
									+ "not visible, make it package-private (and co-locate the "
									+ "test) or add a real API — never pry it open with reflection."));
					continue;
				}

				if (reflectImport && line.contains(".invoke(")) {
					violations.add(new Violation(file, lineNum, line, RULE,
							"Reflective method invocation ('Method.invoke'). Calling a method "
									+ "through reflection bypasses its access modifier and is "
									+ "unchecked at compile time. Call the method directly; if it "
									+ "is not visible, make it package-private (and co-locate the "
									+ "test) or add a real API — never pry it open with reflection."));
				}
			}
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
	}
}
