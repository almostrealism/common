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

import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Abstract base class for static analysis tools that detect code policy violations.
 *
 * <p>Provides common infrastructure for file traversal, violation reporting, and
 * result aggregation. Concrete subclasses implement {@link #scanFile(Path)} to check
 * for specific violation categories.
 *
 * <p>All subclasses are automatically excluded from scanning to prevent false positives
 * on enforcement code itself.
 *
 * @see PackedCollectionDetector
 * @see ProducerPatternDetector
 * @see NamingConventionDetector
 * @see CodePolicyViolationDetector
 */
public abstract class PolicyViolationDetector implements ConsoleFeatures {

	/**
	 * A detected violation with location and description.
	 */
	public static class Violation {
		/** The source file containing the violation. */
		private final Path file;

		/** The line number (1-based) of the violating code. */
		private final int lineNumber;

		/** The raw source line that triggered the violation. */
		private final String line;

		/** Short rule code identifying which policy was violated. */
		private final String rule;

		/** Human-readable description of the violation and suggested fix. */
		private final String description;

		/**
		 * Creates a new violation record.
		 *
		 * @param file        the file containing the violation
		 * @param lineNumber  the 1-based line number
		 * @param line        the raw source line
		 * @param rule        short rule code
		 * @param description human-readable description and suggested fix
		 */
		public Violation(Path file, int lineNumber, String line, String rule, String description) {
			this.file = file;
			this.lineNumber = lineNumber;
			this.line = line;
			this.rule = rule;
			this.description = description;
		}

		public Path getFile() { return file; }
		public int getLineNumber() { return lineNumber; }
		public String getLine() { return line; }
		public String getRule() { return rule; }
		public String getDescription() { return description; }

		@Override
		public String toString() {
			return String.format("%s:%d [%s] %s%n  -> %s",
					file, lineNumber, rule, description, line.trim());
		}
	}

	/**
	 * Files or path patterns to exclude from scanning.
	 * Includes the detector infrastructure itself and test files.
	 */
	protected static final List<String> EXCLUDED_PATHS = List.of(
			"PolicyViolationDetector.java",       // This parent class
			"CodePolicyViolationDetector.java",   // The orchestrator
			"PackedCollectionDetector.java",      // Sub-detector
			"ProducerPatternDetector.java",       // Sub-detector
			"NamingConventionDetector.java",      // Sub-detector
			"CodePolicyEnforcementTest.java",     // The test that runs this
			"/test/"                               // Test files may have intentional examples
	);

	/**
	 * Method name patterns that indicate one-time initialization code.
	 * Loops in these methods are acceptable since they run once at setup.
	 */
	protected static final List<String> INITIALIZATION_METHOD_PATTERNS = List.of(
			"init", "setup", "create", "build", "generate", "compute",
			"load", "configure", "prepare", "initialize", "normalize",
			"random", "rope", "freq", "index", "mel", "filter", "attention"
	);

	/** Detects import statements that reference {@code PackedCollection}. */
	protected static final Pattern PACKED_COLLECTION_IMPORT = Pattern.compile(
			"import.*PackedCollection"
	);

	/** Accumulated violations found during the most recent {@link #scan()} call. */
	protected final List<Violation> violations = new ArrayList<>();

	/** Root directory from which Java source files are recursively scanned. */
	protected final Path rootDir;

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	protected PolicyViolationDetector(Path rootDir) {
		this.rootDir = rootDir;
	}

	/**
	 * Scans all Java source files under the root directory.
	 *
	 * @return this detector for chaining
	 * @throws IOException if directory traversal fails
	 */
	public PolicyViolationDetector scan() throws IOException {
		violations.clear();

		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !isExcluded(p))
					.forEach(this::scanFile);
		}

		return this;
	}

	/**
	 * Scans a single file for violations specific to this detector's category.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	public abstract PolicyViolationDetector scanFile(Path file);

	/**
	 * Returns all detected violations.
	 *
	 * @return an unmodifiable snapshot of the current violation list
	 */
	public List<Violation> getViolations() {
		return new ArrayList<>(violations);
	}

	/**
	 * Returns true if any violations were detected.
	 *
	 * @return true if the violations list is non-empty
	 */
	public boolean hasViolations() {
		return !violations.isEmpty();
	}

	/**
	 * Generates a human-readable report of all violations.
	 *
	 * @return formatted report string
	 */
	public String generateReport() {
		if (violations.isEmpty()) {
			return "No code policy violations detected.";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("=== CODE POLICY VIOLATIONS DETECTED ===\n\n");
		sb.append("The following violations of CLAUDE.md rules were found:\n\n");

		for (Violation v : violations) {
			sb.append(v.toString()).append("\n\n");
		}

		sb.append("=== TOTAL: ").append(violations.size()).append(" violation(s) ===\n\n");
		sb.append("Use the Producer pattern: cp(x).multiply(...).add(...).evaluate()\n");

		return sb.toString();
	}

	/**
	 * Returns {@code true} if the given path matches any of the exclusion patterns.
	 *
	 * @param path  the path to test
	 * @return      whether the path should be skipped during scanning
	 */
	protected boolean isExcluded(Path path) {
		String pathStr = path.toString();
		for (String excluded : EXCLUDED_PATHS) {
			if (pathStr.contains(excluded)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Counts the number of lines in {@code content} up to but not including {@code position}.
	 *
	 * @param content   the full file content
	 * @param position  the character offset to count up to
	 * @return          the 1-based line number at the given position
	 */
	protected int countLines(String content, int position) {
		int count = 1;
		for (int i = 0; i < position && i < content.length(); i++) {
			if (content.charAt(i) == '\n') count++;
		}
		return count;
	}

	/**
	 * Counts occurrences of character {@code c} in string {@code s}.
	 *
	 * @param s  the string to search
	 * @param c  the character to count
	 * @return   the number of occurrences
	 */
	protected int countChar(String s, char c) {
		int count = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) count++;
		}
		return count;
	}

	/**
	 * Returns a multi-line string containing the lines within {@code radius} of {@code centerLine}.
	 *
	 * @param lines       all lines of the file
	 * @param centerLine  0-based index of the center line
	 * @param radius      number of lines before and after to include
	 * @return            the context string
	 */
	protected String getContext(List<String> lines, int centerLine, int radius) {
		int start = Math.max(0, centerLine - radius);
		int end = Math.min(lines.size(), centerLine + radius + 1);
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < end; i++) {
			sb.append(lines.get(i)).append("\n");
		}
		return sb.toString();
	}

	/**
	 * Finds the enclosing method name for a given line.
	 *
	 * @param lines      all lines of the file
	 * @param lineIndex  0-based index of the line to check
	 * @return           the method name, or empty string if not found
	 */
	/** Java modifier keywords that appear before a constructor name but never before a return type. */
	private static final Set<String> JAVA_MODIFIERS = Set.of(
			"public", "protected", "private", "static", "final", "abstract",
			"synchronized", "native", "strictfp", "default", "transient", "volatile");

	/**
	 * Keywords that can appear immediately before {@code (} on a line that is NOT a
	 * method declaration (e.g. {@code for (...)}, {@code if (...)}, {@code return (...)}).
	 * Used to prevent {@link #findEnclosingMethodName} from mistaking control-flow
	 * and statement lines for method signatures.
	 */
	private static final Set<String> CONTROL_FLOW_KEYWORDS = Set.of(
			"for", "while", "if", "else", "catch", "switch",
			"return", "throw", "yield", "assert", "do", "try");

	/**
	 * Walks backward from {@code lineIndex} to find the name of the enclosing method
	 * or constructor. Returns {@code "<constructor>"} for constructor declarations,
	 * the method name for regular methods, or an empty string if no enclosing method
	 * is found.
	 *
	 * <p>Lines that look like method calls, control-flow statements, or lambda
	 * signatures (e.g. {@code return () -> args -> { ... }}) are skipped so they do
	 * not masquerade as enclosing declarations. A line qualifies as a declaration
	 * only when the text after its matched {@code )} begins with {@code {} or
	 * {@code throws}.
	 *
	 * <p>Handles multi-line method signatures where the parameter list spans
	 * multiple lines (the closing {@code ) {} or {@code ) throws} is on a
	 * different line from the method name and opening {@code (}).
	 *
	 * @param lines      all lines of the file
	 * @param lineIndex  0-based index of the line to check
	 * @return           the method name, {@code "<constructor>"}, or empty string
	 */
	protected String findEnclosingMethodName(List<String> lines, int lineIndex) {
		for (int i = lineIndex; i >= 0; i--) {
			String line = lines.get(i);
			String trimmed = line.trim();

			// A method/constructor declaration's body-opener line ends with
			// `) {` or `) throws ...`. Locate the rightmost `)` and require
			// that the text after it begins with `{` or `throws`.
			int rparenIdx = trimmed.lastIndexOf(')');
			if (rparenIdx < 0) continue;
			String afterParen = trimmed.substring(rparenIdx + 1).trim();
			if (!afterParen.startsWith("{") && !afterParen.startsWith("throws")) {
				continue;
			}

			// Accumulate lines backward until paren depth is balanced, so that
			// multi-line parameter lists are handled. The earliest line then
			// contains the method name and the opening `(`.
			int parenDepth = countChar(trimmed, ')') - countChar(trimmed, '(');
			StringBuilder combined = new StringBuilder(trimmed);
			int j = i;
			while (parenDepth > 0 && j > 0) {
				j--;
				String prev = lines.get(j);
				parenDepth += countChar(prev, ')') - countChar(prev, '(');
				combined.insert(0, prev + " ");
			}
			if (parenDepth != 0) continue;

			String signature = combined.toString();
			int parenIdx = signature.indexOf('(');
			if (parenIdx <= 0) continue;

			int spaceIdx = signature.lastIndexOf(' ', parenIdx);
			if (spaceIdx < 0 || spaceIdx >= parenIdx) continue;

			String name = signature.substring(spaceIdx + 1, parenIdx).trim();
			// Empty name (lambda signatures like `() -> {`) or a control-flow
			// keyword (for/while/if/catch/...) is not a method.
			if (name.isEmpty() || CONTROL_FLOW_KEYWORDS.contains(name)) continue;

			String before = signature.substring(0, spaceIdx).trim();
			String precedingToken = before.isEmpty() ? ""
					: before.contains(" ")
							? before.substring(before.lastIndexOf(' ') + 1)
							: before;
			// If the token immediately before the name is a modifier keyword
			// (e.g. "public Foo()" has no return type), this is a constructor.
			if (precedingToken.isEmpty() || JAVA_MODIFIERS.contains(precedingToken)) {
				return "<constructor>";
			}
			return name;
		}
		return "";
	}

	/**
	 * Checks if a method name indicates one-time initialization code.
	 *
	 * @param methodName  the name to test
	 * @return            true if the method appears to be initialization code
	 */
	protected boolean isInitializationMethod(String methodName) {
		if (methodName.equals("<constructor>")) return true;
		String lower = methodName.toLowerCase();
		for (String pattern : INITIALIZATION_METHOD_PATTERNS) {
			if (lower.startsWith(pattern) || lower.contains(pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns {@code true} if the line at {@code currentLine} is inside a {@code for} loop body.
	 *
	 * @param lines        all lines of the file
	 * @param currentLine  0-based index of the line to check
	 * @return             whether the line is nested inside a {@code for} loop
	 */
	protected boolean isInsideForLoop(List<String> lines, int currentLine) {
		int braceDepth = 0;
		for (int i = currentLine; i >= 0; i--) {
			String line = lines.get(i);
			braceDepth += countChar(line, '}') - countChar(line, '{');
			if (line.trim().startsWith("for") && line.contains("(") && braceDepth <= 0) {
				return true;
			}
			if (braceDepth > 0) {
				return false;
			}
		}
		return false;
	}

	/**
	 * Checks if a line contains a bulk copy pattern such as
	 * {@code setMem(offset, source)} or {@code setMem(offset, source, srcOffset, length)}.
	 * These are efficient and should not be flagged.
	 *
	 * @param line  the line to test
	 * @return      true if the line is a bulk copy, not an element-wise loop
	 */
	protected boolean isBulkCopyPattern(String line) {
		if (!line.contains(".setMem(")) return false;

		return line.matches(".*\\.setMem\\s*\\([^,]+,\\s*[a-z][\\w.]*\\s*,\\s*[^,]+\\s*,\\s*[^)]+\\).*")
				|| line.matches(".*\\.setMem\\s*\\([^,]+,\\s*[a-z][\\w.]*\\s*\\).*")
				|| line.contains("getChannelData")
				|| line.contains(".range(")
				|| (line.contains(".setMem(") && line.contains(", new double["));
	}

	/**
	 * Checks if a line appears to be using Java stream {@code toArray()},
	 * which is not a PackedCollection operation.
	 *
	 * @param lines      all lines of the file
	 * @param lineIndex  0-based index of the line to check
	 * @return           true if the line is in a Java stream context
	 */
	protected boolean isJavaStreamToArray(List<String> lines, int lineIndex) {
		String context = getContext(lines, lineIndex, 5);
		return context.contains("IntStream") ||
				context.contains("DoubleStream") ||
				context.contains("LongStream") ||
				context.contains(".stream()") ||
				context.contains(".mapToDouble(") ||
				context.contains(".mapToInt(") ||
				context.contains(".flatMap(");
	}

	/**
	 * Checks if a class declaration in the given content implements a target interface
	 * or extends a known base class that implements it.
	 *
	 * @param content          the full file content
	 * @param className        the class name to look up
	 * @param interfaceName    the interface that must be implemented
	 * @param knownBaseClasses known base classes that implement the interface
	 * @return                 true if the class satisfies the interface requirement
	 */
	protected boolean classImplementsInterface(String content, String className,
			String interfaceName, List<String> knownBaseClasses) {
		Pattern classLine = Pattern.compile(
				"class\\s+" + Pattern.quote(className) + "\\b[^{]*",
				Pattern.DOTALL
		);
		Matcher matcher = classLine.matcher(content);
		if (!matcher.find()) return false;

		String declaration = matcher.group();

		if (declaration.contains("implements") && declaration.contains(interfaceName)) {
			return true;
		}

		for (String base : knownBaseClasses) {
			if (declaration.contains("extends") && declaration.contains(base)) {
				return true;
			}
		}

		if (declaration.contains("extends")) {
			Pattern extendsPattern = Pattern.compile("extends\\s+(\\w*" + interfaceName + ")\\b");
			if (extendsPattern.matcher(declaration).find()) {
				return true;
			}
		}

		return false;
	}
}
