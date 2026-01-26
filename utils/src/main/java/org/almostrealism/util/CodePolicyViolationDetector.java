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
			"/test/"                              // Test files may have intentional examples
	);

	/**
	 * Packages/paths where CPU loops with PackedCollection are legitimate.
	 * These domains inherently require CPU-side operations or one-time initialization.
	 */
	private static final List<String> LEGITIMATE_CPU_DOMAINS = List.of(
			// Genetic algorithms and optimization - require CPU randomness
			"/heredity/",
			"/optimize/",
			// Audio processing - FFT interfacing, format conversion, testing
			"WavetableCell.java",
			"BufferOutputLine.java",
			"RunningAverageCell.java",
			"FrequencyToAudioConverter.java",
			"WaveData.java",
			"WaveDetailsFactory.java",
			// Ray tracing - kernel result extraction and setup
			"LightingEngineAggregator.java",
			"CachedMeshIntersectionKernel.java",
			"MeshData.java",
			"DefaultVertexData.java",
			// Interactive editing
			"EditableSpatialWaveDetails.java",
			// Collection utilities with fallback paths
			"CollectionFeatures.java",
			// ML model initialization - one-time setup at model creation
			"LoRALinear.java",      // Random weight initialization
			"LayerFeatures.java",   // Weight normalization
			"TemporalFeatures.java", // Mel filterbank initialization
			"Qwen3.java",           // RoPE frequency computation
			"RotationFeatures.java", // Index map creation for RoPE
			"AttentionFeatures.java" // Index collection setup for attention
	);

	/**
	 * Method name patterns that indicate one-time initialization code.
	 * Loops in these methods are acceptable since they run once at setup.
	 */
	private static final List<String> INITIALIZATION_METHOD_PATTERNS = List.of(
			"init", "setup", "create", "build", "generate", "compute",
			"load", "configure", "prepare", "initialize", "normalize",
			"random", "rope", "freq", "index", "mel", "filter", "attention"
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
		// Skip files in domains where CPU loops are legitimate
		if (isLegitimeCpuDomain(file)) {
			return;
		}

		// Track whether we're inside a javadoc comment
		boolean inJavadoc = false;

		// Check for setMem in a loop pattern (line by line for better error reporting)
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmedLine = line.trim();
			int lineNum = i + 1;

			// Track javadoc comment state
			if (trimmedLine.startsWith("/**")) inJavadoc = true;
			if (trimmedLine.contains("*/")) inJavadoc = false;

			// Skip javadoc comment lines - examples in documentation are not violations
			if (inJavadoc || trimmedLine.startsWith("*") || trimmedLine.startsWith("//")) {
				continue;
			}

			// Skip lines with @nopolicy annotation (legitimate exceptions)
			if (trimmedLine.contains("@nopolicy") || trimmedLine.contains("// nopolicy")) {
				continue;
			}

			// Check enclosing method - initialization methods are allowed to have CPU loops
			String methodName = findEnclosingMethodName(lines, i);
			boolean inInitMethod = isInitializationMethod(methodName);

			// Check for direct setMem with toDouble pattern on same line
			// But skip if it's a bulk copy pattern or in initialization code
			if (SETMEM_WITH_INDEX.matcher(line).find() && !isBulkCopyPattern(line)) {
				if (!inInitMethod) {
					violations.add(new Violation(file, lineNum, line,
							"PACKED_COLLECTION_CPU_LOOP",
							"setMem() with toDouble() defeats GPU parallelism. Use Producer pattern: cp(x).multiply(...).evaluate()"));
				}
			}

			// Check for setMem with loop variable
			if (line.contains(".setMem(") && isInsideForLoop(lines, i)) {
				// Skip bulk copy patterns - they're efficient
				if (isBulkCopyPattern(line)) {
					continue;
				}

				// Skip if in initialization method
				if (inInitMethod) {
					continue;
				}

				// Check if this looks like a CPU manipulation loop
				if (looksLikeCpuManipulationLoop(lines, i)) {
					violations.add(new Violation(file, lineNum, line,
							"PACKED_COLLECTION_CPU_LOOP",
							"setMem() inside for loop defeats GPU parallelism. Use Producer pattern instead."));
				}
			}

			// Check for System.arraycopy - but only if it appears to involve PackedCollection data
			// Skip if it's copying Producer[] or other non-GPU arrays, or if in init method
			if (SYSTEM_ARRAYCOPY.matcher(line).find() && !inInitMethod) {
				// Check context - is this copying actual GPU data?
				String context = getContext(lines, i, 10);
				String lineLower = line.toLowerCase();
				if (context.contains("PackedCollection") &&
						!lineLower.contains("producer") && !lineLower.contains("supplier") &&
						!lineLower.contains("string") && !line.contains("Object") &&
						!lineLower.contains("sources")) {  // Common name for Producer[]
					violations.add(new Violation(file, lineNum, line,
							"PACKED_COLLECTION_ARRAYCOPY",
							"System.arraycopy cannot move GPU-resident data. Use framework methods."));
				}
			}

			// Check for Arrays.copyOf (same logic)
			if (ARRAYS_COPYOF.matcher(line).find() && !inInitMethod) {
				String context = getContext(lines, i, 10);
				String lineLower = line.toLowerCase();
				if (context.contains("PackedCollection") &&
						!lineLower.contains("producer") && !lineLower.contains("supplier") &&
						!lineLower.contains("sources")) {
					violations.add(new Violation(file, lineNum, line,
							"PACKED_COLLECTION_ARRAYCOPY",
							"Arrays.copyOf cannot copy GPU-resident data. Use framework methods."));
				}
			}
		}

		// Check for toArray() followed by setMem() pattern (multi-line)
		// But skip if it's clearly in a javadoc or Java stream operations
		Matcher toArraySetMem = TOARRAY_SETMEM_PATTERN.matcher(content);
		while (toArraySetMem.find()) {
			int lineNum = countLines(content, toArraySetMem.start());
			int lineIdx = Math.min(lineNum - 1, lines.size() - 1);
			String line = lines.get(lineIdx);
			// Skip if in javadoc
			if (line.trim().startsWith("*") || line.trim().startsWith("//")) {
				continue;
			}
			// Skip if this is Java stream toArray(), not PackedCollection
			if (isJavaStreamToArray(lines, lineIdx)) {
				continue;
			}
			// Skip if in initialization method
			String methodName = findEnclosingMethodName(lines, lineIdx);
			if (isInitializationMethod(methodName)) {
				continue;
			}
			violations.add(new Violation(file, lineNum, line,
					"PACKED_COLLECTION_CPU_ROUNDTRIP",
					"toArray() followed by setMem() forces CPU round-trip. Use Producer pattern."));
		}
	}

	/**
	 * Checks if a line contains a bulk copy pattern like:
	 * - setMem(offset, source) where source is a MemoryData
	 * - setMem(offset, source, srcOffset, length)
	 * These are efficient and should not be flagged.
	 */
	private boolean isBulkCopyPattern(String line) {
		// Pattern: setMem(something, variableName) or setMem(something, variableName, offset, length)
		// These have a variable (not a number or expression result) as second argument
		if (!line.contains(".setMem(")) return false;

		// Look for patterns where the second argument looks like a collection variable
		// Common patterns: setMem(0, source), setMem(offset, data, 0, len)
		// Check if the line looks like it's passing a collection/memorydata as argument
		return line.matches(".*\\.setMem\\s*\\([^,]+,\\s*[a-z][\\w.]*(?:\\s*,\\s*\\d+\\s*,\\s*[\\w.]+)?\\s*\\).*")
				|| line.matches(".*\\.setMem\\s*\\([^,]+,\\s*[a-z][\\w.]*\\s*\\).*")
				|| line.contains("getChannelData")  // Common bulk copy source
				|| line.contains(".range(")         // View-based copy
				|| (line.contains(".setMem(") && line.contains(", new double["));  // Array initialization
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
	 * Checks if a file is in a domain where CPU loops with PackedCollection are legitimate.
	 */
	private boolean isLegitimeCpuDomain(Path path) {
		String pathStr = path.toString();
		for (String domain : LEGITIMATE_CPU_DOMAINS) {
			if (pathStr.contains(domain)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Finds the enclosing method name for a given line.
	 */
	private String findEnclosingMethodName(List<String> lines, int lineIndex) {
		int braceDepth = 0;
		for (int i = lineIndex; i >= 0; i--) {
			String line = lines.get(i);
			braceDepth += countChar(line, '}') - countChar(line, '{');
			// Look for method declaration pattern
			if (line.matches(".*\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{?.*") ||
					line.matches(".*\\s+(\\w+)\\s*\\([^)]*\\)\\s*throws.*")) {
				// Extract method name
				String trimmed = line.trim();
				int parenIdx = trimmed.indexOf('(');
				if (parenIdx > 0) {
					int spaceIdx = trimmed.lastIndexOf(' ', parenIdx);
					if (spaceIdx >= 0 && spaceIdx < parenIdx) {
						return trimmed.substring(spaceIdx + 1, parenIdx);
					}
				}
			}
			// Also check for constructor pattern
			if (line.matches(".*public\\s+\\w+\\s*\\([^)]*\\).*") && braceDepth <= 0) {
				return "<constructor>";
			}
		}
		return "";
	}

	/**
	 * Checks if a method name indicates one-time initialization.
	 */
	private boolean isInitializationMethod(String methodName) {
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
	 * Checks if a line appears to be using Java stream toArray().
	 * Java stream toArray() is NOT a PackedCollection operation.
	 */
	private boolean isJavaStreamToArray(List<String> lines, int lineIndex) {
		String context = getContext(lines, lineIndex, 5);
		// Check for stream pipeline patterns
		return context.contains("IntStream") ||
				context.contains("DoubleStream") ||
				context.contains("LongStream") ||
				context.contains(".stream()") ||
				context.contains(".mapToDouble(") ||
				context.contains(".mapToInt(") ||
				context.contains(".flatMap(");
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
