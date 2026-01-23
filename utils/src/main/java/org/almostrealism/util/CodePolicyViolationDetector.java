/*
 * Copyright 2025 Michael Murray
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
 * Static analysis tool that detects code policy violations.
 *
 * <p>This detector enforces the rules in CLAUDE.md by scanning source files
 * for patterns that violate the PackedCollection GPU memory model.
 *
 * <p><b>This tool exists because documentation alone does not prevent violations.</b>
 * The build must fail when violations are detected.
 *
 * <h2>Detected Violations</h2>
 * <ul>
 *   <li>CPU loops with setMem() - defeats GPU parallelism</li>
 *   <li>CPU loops with toDouble()/toArray() followed by setMem() - forces GPU-CPU round trips</li>
 *   <li>System.arraycopy near PackedCollection - cannot move GPU data</li>
 *   <li>Interfaces named *Features with abstract methods - violates Features pattern convention</li>
 * </ul>
 *
 * @see <a href="file:../../../I_DONT_KNOW_HOW_A_GPU_WORKS.md">I_DONT_KNOW_HOW_A_GPU_WORKS.md</a>
 */
public class CodePolicyViolationDetector {

	/**
	 * A detected violation with location and description.
	 */
	public static class Violation {
		private final Path file;
		private final int lineNumber;
		private final String line;
		private final String rule;
		private final String description;

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
	 * Files or patterns to exclude from scanning.
	 * These are legitimate uses or the detector itself.
	 */
	private static final List<String> EXCLUDED_PATHS = List.of(
			"CodePolicyViolationDetector.java",  // This file
			"CodePolicyEnforcementTest.java",    // The test that runs this
			"I_DONT_KNOW_HOW_A_GPU_WORKS.md",    // Documentation
			"/test/"                              // Test files may have intentional examples
	);

	/**
	 * Patterns that indicate PackedCollection CPU-loop violations.
	 */
	private static final Pattern SETMEM_IN_LOOP = Pattern.compile(
			"for\\s*\\([^)]*\\)\\s*\\{[^}]*\\.setMem\\s*\\([^)]*\\)",
			Pattern.DOTALL
	);

	private static final Pattern SETMEM_WITH_INDEX = Pattern.compile(
			"\\.setMem\\s*\\(\\s*\\w+\\s*,.*\\.toDouble\\s*\\(\\s*\\w+\\s*\\)"
	);

	private static final Pattern TODOUBLE_LOOP = Pattern.compile(
			"for\\s*\\([^)]*\\)\\s*\\{[^}]*\\.toDouble\\s*\\([^)]*\\)[^}]*\\.setMem",
			Pattern.DOTALL
	);

	private static final Pattern SYSTEM_ARRAYCOPY = Pattern.compile(
			"System\\.arraycopy\\s*\\("
	);

	private static final Pattern ARRAYS_COPYOF = Pattern.compile(
			"Arrays\\.copyOf\\s*\\("
	);

	private static final Pattern PACKED_COLLECTION_IMPORT = Pattern.compile(
			"import.*PackedCollection"
	);

	private static final Pattern TOARRAY_SETMEM_PATTERN = Pattern.compile(
			"\\.toArray\\s*\\([^)]*\\)[^;]*;[^}]*\\.setMem\\s*\\(",
			Pattern.DOTALL
	);

	/**
	 * Pattern to detect interfaces named *Features.
	 * Group 1 captures the interface name.
	 */
	private static final Pattern FEATURES_INTERFACE_PATTERN = Pattern.compile(
			"public\\s+interface\\s+(\\w*Features)\\s"
	);

	/**
	 * Pattern to detect non-default method declarations in interfaces.
	 * This matches method signatures that don't start with "default".
	 */
	private static final Pattern ABSTRACT_METHOD_PATTERN = Pattern.compile(
			"^\\s*(?!default\\s)(?!//)(\\w+(?:<[^>]+>)?\\s+\\w+\\s*\\([^)]*\\)\\s*;)",
			Pattern.MULTILINE
	);

	private final List<Violation> violations = new ArrayList<>();
	private final Path rootDir;

	public CodePolicyViolationDetector(Path rootDir) {
		this.rootDir = rootDir;
	}

	/**
	 * Scans all Java source files under the root directory.
	 *
	 * @return this detector for chaining
	 * @throws IOException if file reading fails
	 */
	public CodePolicyViolationDetector scan() throws IOException {
		violations.clear();

		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !isExcluded(p))
					.forEach(this::scanFile);
		}

		return this;
	}

	/**
	 * Scans a single file for violations.
	 *
	 * @param file the file to scan
	 * @return this detector for chaining
	 */
	public CodePolicyViolationDetector scanFile(Path file) {
		try {
			String content = Files.readString(file);
			List<String> lines = Files.readAllLines(file);

			// Only check PackedCollection rules if the file uses PackedCollection
			boolean usesPackedCollection = PACKED_COLLECTION_IMPORT.matcher(content).find()
					|| content.contains("PackedCollection");

			if (usesPackedCollection) {
				checkPackedCollectionViolations(file, content, lines);
			}

			// Check for Features interface violations
			checkFeaturesInterfaceViolations(file, content, lines);

		} catch (IOException e) {
			System.err.println("Warning: Could not read file " + file + ": " + e.getMessage());
		}

		return this;
	}

	private void checkPackedCollectionViolations(Path file, String content, List<String> lines) {
		// Check for setMem in a loop pattern (line by line for better error reporting)
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int lineNum = i + 1;

			// Check for direct setMem with toDouble pattern on same line
			if (SETMEM_WITH_INDEX.matcher(line).find()) {
				violations.add(new Violation(file, lineNum, line,
						"PACKED_COLLECTION_CPU_LOOP",
						"setMem() with toDouble() defeats GPU parallelism. Use Producer pattern: cp(x).multiply(...).evaluate()"));
			}

			// Check for setMem with loop variable
			if (line.contains(".setMem(") && isInsideForLoop(lines, i)) {
				// Check if this looks like a CPU manipulation loop
				if (looksLikeCpuManipulationLoop(lines, i)) {
					violations.add(new Violation(file, lineNum, line,
							"PACKED_COLLECTION_CPU_LOOP",
							"setMem() inside for loop defeats GPU parallelism. Use Producer pattern instead."));
				}
			}

			// Check for System.arraycopy
			if (SYSTEM_ARRAYCOPY.matcher(line).find()) {
				// Check context - is this near PackedCollection usage?
				String context = getContext(lines, i, 10);
				if (context.contains("PackedCollection")) {
					violations.add(new Violation(file, lineNum, line,
							"PACKED_COLLECTION_ARRAYCOPY",
							"System.arraycopy cannot move GPU-resident data. Use framework methods."));
				}
			}

			// Check for Arrays.copyOf
			if (ARRAYS_COPYOF.matcher(line).find()) {
				String context = getContext(lines, i, 10);
				if (context.contains("PackedCollection")) {
					violations.add(new Violation(file, lineNum, line,
							"PACKED_COLLECTION_ARRAYCOPY",
							"Arrays.copyOf cannot copy GPU-resident data. Use framework methods."));
				}
			}
		}

		// Check for toArray() followed by setMem() pattern (multi-line)
		Matcher toArraySetMem = TOARRAY_SETMEM_PATTERN.matcher(content);
		while (toArraySetMem.find()) {
			int lineNum = countLines(content, toArraySetMem.start());
			String line = lines.get(Math.min(lineNum - 1, lines.size() - 1));
			violations.add(new Violation(file, lineNum, line,
					"PACKED_COLLECTION_CPU_ROUNDTRIP",
					"toArray() followed by setMem() forces CPU round-trip. Use Producer pattern."));
		}
	}

	/**
	 * Checks for "Features" interfaces that have abstract methods.
	 *
	 * <p>"Features" interfaces should only contain default methods - they exist to
	 * provide capabilities via composition, not to require implementations.</p>
	 */
	private void checkFeaturesInterfaceViolations(Path file, String content, List<String> lines) {
		// Check if this file defines an interface named *Features
		Matcher interfaceMatcher = FEATURES_INTERFACE_PATTERN.matcher(content);
		if (!interfaceMatcher.find()) {
			return; // Not a Features interface
		}

		String interfaceName = interfaceMatcher.group(1);
		int interfaceLineNum = countLines(content, interfaceMatcher.start());

		// Find the interface body start
		int interfaceStart = content.indexOf('{', interfaceMatcher.end());
		if (interfaceStart == -1) {
			return;
		}

		// Track brace depth to know when we're at the interface level (depth 1)
		// vs inside a method body (depth > 1)
		int braceDepth = 0;
		boolean inInterface = false;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmedLine = line.trim();
			int lineNum = i + 1;

			// Update brace depth BEFORE skip check - the interface line may contain the opening brace
			for (char c : line.toCharArray()) {
				if (c == '{') {
					braceDepth++;
					if (braceDepth == 1) inInterface = true;
				} else if (c == '}') {
					braceDepth--;
					if (braceDepth == 0) inInterface = false;
				}
			}

			// Skip if we're on/before the interface declaration line (can't have abstract methods there)
			if (lineNum <= interfaceLineNum) continue;

			// Only check for abstract methods when at interface level (depth 1)
			if (braceDepth != 1 || !inInterface) continue;

			// Skip comments, annotations, and empty lines
			if (trimmedLine.isEmpty() || trimmedLine.startsWith("//") ||
					trimmedLine.startsWith("/*") || trimmedLine.startsWith("*") ||
					trimmedLine.startsWith("@")) {
				continue;
			}

			// Skip default methods, static methods, and nested type declarations
			if (trimmedLine.startsWith("default ") || trimmedLine.startsWith("static ") ||
					trimmedLine.contains(" class ") || trimmedLine.contains(" interface ") ||
					trimmedLine.contains(" enum ")) {
				continue;
			}

			// Skip lines that contain return, if, for, etc. (statements, not declarations)
			if (trimmedLine.startsWith("return ") || trimmedLine.startsWith("if ") ||
					trimmedLine.startsWith("if(") || trimmedLine.startsWith("for ") ||
					trimmedLine.startsWith("for(") || trimmedLine.startsWith("while ") ||
					trimmedLine.startsWith("throw ") || trimmedLine.startsWith("try ")) {
				continue;
			}

			// Skip lines that are just closing braces or opening braces
			if (trimmedLine.equals("{") || trimmedLine.equals("}") ||
					trimmedLine.equals("};") || trimmedLine.endsWith("{")) {
				continue;
			}

			// Check if this looks like an abstract method declaration
			// Pattern: return_type methodName(params); - but NOT a method call
			// Key: must start with a type (capitalized or primitive), not an expression
			if (trimmedLine.matches("^[A-Z]\\w*(?:<[^>]+>)?\\s+\\w+\\s*\\([^)]*\\)\\s*;$") ||
					trimmedLine.matches("^(void|int|long|double|float|boolean|byte|char|short)\\s+\\w+\\s*\\([^)]*\\)\\s*;$") ||
					trimmedLine.matches("^[A-Z]\\w*(?:<[^>]+>)?\\[\\]\\s+\\w+\\s*\\([^)]*\\)\\s*;$")) {
				violations.add(new Violation(file, lineNum, trimmedLine,
						"FEATURES_INTERFACE_ABSTRACT_METHOD",
						"Interface '" + interfaceName + "' should only have default methods. " +
								"Abstract methods force implementations - use a different interface name or make the method default."));
			}
		}
	}

	private boolean isInsideForLoop(List<String> lines, int currentLine) {
		int braceDepth = 0;
		for (int i = currentLine; i >= 0; i--) {
			String line = lines.get(i);
			braceDepth += countChar(line, '}') - countChar(line, '{');
			if (line.trim().startsWith("for") && line.contains("(") && braceDepth <= 0) {
				return true;
			}
			if (braceDepth > 0) {
				return false; // We've exited more blocks than entered
			}
		}
		return false;
	}

	private boolean looksLikeCpuManipulationLoop(List<String> lines, int currentLine) {
		// Look at surrounding context for signs of CPU manipulation
		String context = getContext(lines, currentLine, 5);
		return context.contains(".toDouble(") ||
				context.contains(".toFloat(") ||
				context.contains(".toArray(") ||
				(context.contains("for") && context.contains(".setMem("));
	}

	private String getContext(List<String> lines, int centerLine, int radius) {
		int start = Math.max(0, centerLine - radius);
		int end = Math.min(lines.size(), centerLine + radius + 1);
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < end; i++) {
			sb.append(lines.get(i)).append("\n");
		}
		return sb.toString();
	}

	private int countLines(String content, int position) {
		int count = 1;
		for (int i = 0; i < position && i < content.length(); i++) {
			if (content.charAt(i) == '\n') count++;
		}
		return count;
	}

	private int countChar(String s, char c) {
		int count = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) count++;
		}
		return count;
	}

	private boolean isExcluded(Path path) {
		String pathStr = path.toString();
		for (String excluded : EXCLUDED_PATHS) {
			if (pathStr.contains(excluded)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns all detected violations.
	 */
	public List<Violation> getViolations() {
		return new ArrayList<>(violations);
	}

	/**
	 * Returns true if any violations were detected.
	 */
	public boolean hasViolations() {
		return !violations.isEmpty();
	}

	/**
	 * Generates a report of all violations.
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
		sb.append("See /workspace/project/common/I_DONT_KNOW_HOW_A_GPU_WORKS.md for how to fix these.\n");
		sb.append("Use the Producer pattern: cp(x).multiply(...).add(...).evaluate()\n");

		return sb.toString();
	}

	/**
	 * Command-line entry point for manual scanning.
	 */
	public static void main(String[] args) throws IOException {
		Path rootDir = args.length > 0 ? Path.of(args[0]) : Path.of(".");

		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(rootDir);
		detector.scan();

		System.out.println(detector.generateReport());

		if (detector.hasViolations()) {
			System.exit(1);
		}
	}
}
