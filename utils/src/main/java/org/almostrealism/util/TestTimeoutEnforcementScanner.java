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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scanner that enforces the requirement that every JUnit {@code @Test} annotation
 * must include a {@code timeout} parameter.
 *
 * <p>Tests without timeouts can hang indefinitely, blocking CI runners and wasting
 * resources. This scanner detects {@code @Test} annotations that omit the
 * {@code timeout} parameter and reports them as violations.</p>
 *
 * <h2>Valid Patterns</h2>
 * <pre>{@code
 * @Test(timeout = 30000)
 * public void myTest() { ... }
 *
 * @Test(timeout = 60000, expected = RuntimeException.class)
 * public void myOtherTest() { ... }
 * }</pre>
 *
 * <h2>Invalid Patterns</h2>
 * <pre>{@code
 * @Test
 * public void myTest() { ... }
 *
 * @Test(expected = RuntimeException.class)
 * public void myOtherTest() { ... }
 * }</pre>
 *
 * @see CodePolicyViolationDetector
 */
public class TestTimeoutEnforcementScanner {

	/**
	 * A test method that is missing a timeout value.
	 */
	public static class Violation {
		private final Path file;
		private final int lineNumber;
		private final String line;
		private final String methodName;

		/**
		 * Creates a new violation record.
		 *
		 * @param file       the file containing the violation
		 * @param lineNumber the line number of the {@code @Test} annotation
		 * @param line       the raw line text
		 * @param methodName the name of the test method (if detected)
		 */
		public Violation(Path file, int lineNumber, String line, String methodName) {
			this.file = file;
			this.lineNumber = lineNumber;
			this.line = line;
			this.methodName = methodName;
		}

		/** Returns the file containing this violation. */
		public Path getFile() { return file; }

		/** Returns the line number of the {@code @Test} annotation. */
		public int getLineNumber() { return lineNumber; }

		/** Returns the raw annotation line text. */
		public String getLine() { return line; }

		/** Returns the test method name, or an empty string if not detected. */
		public String getMethodName() { return methodName; }

		@Override
		public String toString() {
			String method = methodName.isEmpty() ? "" : " (" + methodName + ")";
			return String.format("%s:%d%s - @Test missing timeout parameter",
					file, lineNumber, method);
		}
	}

	/**
	 * Pattern matching a bare {@code @Test} annotation (no parentheses)
	 * or one with parentheses but no {@code timeout} attribute.
	 */
	private static final Pattern TEST_ANNOTATION = Pattern.compile(
			"^\\s*@Test\\b"
	);

	/**
	 * Pattern confirming that a {@code timeout} parameter is present.
	 */
	private static final Pattern HAS_TIMEOUT = Pattern.compile(
			"@Test\\s*\\([^)]*timeout\\s*="
	);

	/**
	 * Pattern to extract a method name from a test method declaration.
	 */
	private static final Pattern METHOD_DECL = Pattern.compile(
			"(?:public|protected|private)?\\s*(?:void|\\w+)\\s+(\\w+)\\s*\\("
	);

	/**
	 * Files excluded from scanning (e.g., this scanner's own test helpers).
	 */
	private static final List<String> EXCLUDED_PATHS = List.of(
			"TestTimeoutEnforcementScanner.java",
			"CodePolicyEnforcementTest.java",
			"TestSuiteBase.java",
			"TestFeatures.java",
			"TensorTestFeatures.java",
			"ModelTestFeatures.java",
			"TestSettings.java",
			"TestDepthRule.java",
			"TestDepth.java"
	);

	private final List<Violation> violations = new ArrayList<>();
	private final Path rootDir;

	/**
	 * Creates a scanner rooted at the given directory.
	 *
	 * @param rootDir the root directory to scan for test source files
	 */
	public TestTimeoutEnforcementScanner(Path rootDir) {
		this.rootDir = rootDir;
	}

	/**
	 * Scans all Java test source files under the root directory.
	 *
	 * @return this scanner for chaining
	 * @throws IOException if file reading fails
	 */
	public TestTimeoutEnforcementScanner scan() throws IOException {
		violations.clear();

		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(p -> p.toString().endsWith(".java"))
					.filter(this::isTestSource)
					.filter(p -> !isExcluded(p))
					.forEach(this::scanFile);
		}

		return this;
	}

	/**
	 * Scans a single file for {@code @Test} annotations missing timeout.
	 */
	private void scanFile(Path file) {
		try {
			List<String> lines = Files.readAllLines(file);

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);

				if (!TEST_ANNOTATION.matcher(line).find()) {
					continue;
				}

				// Collect the full annotation text (may span multiple lines)
				String annotationText = collectAnnotation(lines, i);

				if (!HAS_TIMEOUT.matcher(annotationText).find()) {
					String methodName = findMethodName(lines, i);
					violations.add(new Violation(file, i + 1, line.trim(), methodName));
				}
			}
		} catch (IOException e) {
			System.err.println("Warning: Could not read file " + file + ": " + e.getMessage());
		}
	}

	/**
	 * Collects the full annotation text starting from the given line index.
	 * Handles annotations that span multiple lines (e.g., when parameters
	 * are on separate lines).
	 */
	private String collectAnnotation(List<String> lines, int startIndex) {
		StringBuilder sb = new StringBuilder();
		sb.append(lines.get(startIndex));

		// If the annotation has an opening paren but no closing paren on this line,
		// keep collecting lines
		String soFar = sb.toString().trim();
		if (soFar.contains("(") && !soFar.contains(")")) {
			for (int j = startIndex + 1; j < lines.size() && j < startIndex + 5; j++) {
				sb.append(" ").append(lines.get(j).trim());
				if (lines.get(j).contains(")")) {
					break;
				}
			}
		}

		return sb.toString();
	}

	/**
	 * Finds the test method name by looking at lines following the annotation.
	 */
	private String findMethodName(List<String> lines, int annotationIndex) {
		for (int j = annotationIndex + 1; j < lines.size() && j <= annotationIndex + 3; j++) {
			Matcher m = METHOD_DECL.matcher(lines.get(j));
			if (m.find()) {
				return m.group(1);
			}
		}
		return "";
	}

	/**
	 * Returns true if the file is in a test source directory.
	 */
	private boolean isTestSource(Path path) {
		String pathStr = path.toString();
		return pathStr.contains("/src/test/");
	}

	/**
	 * Returns true if the file should be excluded from scanning.
	 */
	private boolean isExcluded(Path path) {
		String fileName = path.getFileName().toString();
		return EXCLUDED_PATHS.contains(fileName);
	}

	/** Returns all detected violations. */
	public List<Violation> getViolations() {
		return new ArrayList<>(violations);
	}

	/** Returns true if any violations were detected. */
	public boolean hasViolations() {
		return !violations.isEmpty();
	}

	/**
	 * Generates a human-readable report of all violations.
	 *
	 * @return the formatted report string
	 */
	public String generateReport() {
		if (violations.isEmpty()) {
			return "All @Test annotations include a timeout parameter.";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("=== TEST TIMEOUT ENFORCEMENT VIOLATIONS ===\n\n");
		sb.append("The following @Test annotations are missing a timeout parameter:\n\n");

		for (Violation v : violations) {
			sb.append("  ").append(v.toString()).append("\n");
		}

		sb.append("\n=== TOTAL: ").append(violations.size()).append(" violation(s) ===\n\n");
		sb.append("Fix by adding a timeout, e.g.: @Test(timeout = 30000)\n");

		return sb.toString();
	}
}
