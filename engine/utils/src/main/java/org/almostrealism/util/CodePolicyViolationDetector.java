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
 *   <li>.evaluate() calls in computation code (engine/ml/, studio/ source trees)</li>
 *   <li>.toDouble() calls in computation code</li>
 *   <li>Classes named *Cell that don't implement org.almostrealism.graph.Cell</li>
 *   <li>Classes named *Block that don't implement org.almostrealism.model.Block</li>
 * </ul>
 *
 */
public class CodePolicyViolationDetector {

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
	 * Files or patterns to exclude from scanning.
	 * These are legitimate uses or the detector itself.
	 */
	private static final List<String> EXCLUDED_PATHS = List.of(
			"CodePolicyViolationDetector.java",  // This file
			"CodePolicyEnforcementTest.java",    // The test that runs this
			"/test/"                              // Test files may have intentional examples
	);

	/**
	 * Source trees where .evaluate() and .toDouble() in computation code
	 * are flagged as Producer pattern violations.
	 */
	private static final List<String> COMPUTATION_SOURCE_TREES = List.of(
			"/ml/src/main/java/",
			"/studio/"
	);

	/**
	 * Files in computation source trees where .evaluate() is legitimate.
	 * These are pipeline boundaries, data loading, or sampling loops.
	 */
	private static final List<String> EVALUATE_ALLOWED_FILES = List.of(
			"DiffusionSampler.java",            // Step boundary in sampling loop
			"DiffusionFeatures.java",           // Diffusion setup
			"DiffusionTrainingDataset.java",    // Data loading boundary
			"AudioScene.java",                  // Pipeline boundary
			"AudioDiffusionGenerator.java",     // Step boundaries
			"AudioGenerator.java",              // Pipeline boundary
			"AudioModulator.java",              // Pipeline boundary
			"AudioTrainingDataCollector.java",  // Data loading boundary
			"AudioLatentDataset.java",          // Data loading boundary
			"AutoEncoderFeatureProvider.java",  // Pipeline boundary
			"DefaultChannelSectionFactory.java", // Configuration
			"ChordProgressionManager.java",     // Heredity domain
			"ParameterSet.java",                // Heredity domain
			"PatternFeatures.java",             // Pattern setup
			"LegacyAudioGenerator.java"         // Legacy code
	);

	/**
	 * Files in computation source trees where .toDouble() is legitimate.
	 */
	private static final List<String> TODOUBLE_ALLOWED_FILES = List.of(
			"AudioMeter.java",                  // Monitoring/metrics
			"ConditionalAudioScoring.java",     // Loss/metric at pipeline boundary
			"ChordProgressionManager.java",     // Heredity domain
			"ParameterSet.java",                // Heredity domain
			"PatternLayerManager.java"          // Automation parameter
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
	/** Detects {@code setMem()} calls inside a {@code for} loop block (DOTALL). */
	private static final Pattern SETMEM_IN_LOOP = Pattern.compile(
			"for\\s*\\([^)]*\\)\\s*\\{[^}]*\\.setMem\\s*\\([^)]*\\)",
			Pattern.DOTALL
	);

	/** Detects {@code setMem(index, expr.toDouble(index))} on a single line. */
	private static final Pattern SETMEM_WITH_INDEX = Pattern.compile(
			"\\.setMem\\s*\\(\\s*\\w+\\s*,.*\\.toDouble\\s*\\(\\s*\\w+\\s*\\)"
	);

	/** Detects a {@code for} loop that calls both {@code toDouble()} and {@code setMem} (DOTALL). */
	private static final Pattern TODOUBLE_LOOP = Pattern.compile(
			"for\\s*\\([^)]*\\)\\s*\\{[^}]*\\.toDouble\\s*\\([^)]*\\)[^}]*\\.setMem",
			Pattern.DOTALL
	);

	/** Detects calls to {@code System.arraycopy}. */
	private static final Pattern SYSTEM_ARRAYCOPY = Pattern.compile(
			"System\\.arraycopy\\s*\\("
	);

	/** Detects calls to {@code Arrays.copyOf}. */
	private static final Pattern ARRAYS_COPYOF = Pattern.compile(
			"Arrays\\.copyOf\\s*\\("
	);

	/** Detects import statements that reference {@code PackedCollection}. */
	private static final Pattern PACKED_COLLECTION_IMPORT = Pattern.compile(
			"import.*PackedCollection"
	);

	/** Detects a {@code toArray()} call followed by {@code setMem()} in a multi-line pattern. */
	private static final Pattern TOARRAY_SETMEM_PATTERN = Pattern.compile(
			"\\.toArray\\s*\\([^)]*\\)[^;]*;[^}]*\\.setMem\\s*\\(",
			Pattern.DOTALL
	);

	/**
	 * Pattern to detect .evaluate() calls in source code.
	 */
	private static final Pattern EVALUATE_CALL = Pattern.compile(
			"\\.evaluate\\s*\\("
	);

	/**
	 * Pattern to detect .toDouble() calls in source code.
	 */
	private static final Pattern TODOUBLE_CALL = Pattern.compile(
			"\\.toDouble\\s*\\("
	);

	/**
	 * Pattern to detect class declarations ending in "Cell".
	 * Group 1 captures the class name.
	 */
	private static final Pattern CELL_CLASS_PATTERN = Pattern.compile(
			"(?:public|protected)\\s+(?:abstract\\s+)?class\\s+(\\w*Cell)\\b"
	);

	/**
	 * Pattern to detect class declarations ending in "Block".
	 * Group 1 captures the class name.
	 */
	private static final Pattern BLOCK_CLASS_PATTERN = Pattern.compile(
			"(?:public|protected)\\s+(?:abstract\\s+)?class\\s+(\\w*Block)\\b"
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

	/** Accumulated violations found during the most recent {@link #scan()} call. */
	private final List<Violation> violations = new ArrayList<>();

	/** Root directory from which Java source files are recursively scanned. */
	private final Path rootDir;

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
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

			// Check for Producer pattern violations in computation source trees
			checkProducerPatternViolations(file, content, lines);

			// Check for Cell/Block naming violations
			checkCellBlockNaming(file, content, lines);

			// Check for Features interface violations
			checkFeaturesInterfaceViolations(file, content, lines);

		} catch (IOException e) {
			System.err.println("Warning: Could not read file " + file + ": " + e.getMessage());
		}

		return this;
	}

	/**
	 * Checks a single file for PackedCollection GPU memory model violations.
	 *
	 * @param file     the file being checked
	 * @param content  the full file content as a string
	 * @param lines    the file content split into lines
	 */
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
		return line.matches(".*\\.setMem\\s*\\([^,]+,\\s*[a-z][\\w.]*\\s*,\\s*[^,]+\\s*,\\s*[^)]+\\).*")
				|| line.matches(".*\\.setMem\\s*\\([^,]+,\\s*[a-z][\\w.]*\\s*\\).*")
				|| line.contains("getChannelData")  // Common bulk copy source
				|| line.contains(".range(")         // View-based copy
				|| (line.contains(".setMem(") && line.contains(", new double["));  // Array initialization
	}

	/**
	 * Checks for .evaluate() and .toDouble() calls in computation source trees.
	 *
	 * <p>These calls break the computation graph when used inside model layers.
	 * They are only acceptable at pipeline boundaries (test methods, main methods,
	 * autoregressive loop boundaries, data loading).</p>
	 */
	private void checkProducerPatternViolations(Path file, String content, List<String> lines) {
		String pathStr = file.toString();

		// Only check files in computation source trees
		boolean inComputationTree = false;
		for (String tree : COMPUTATION_SOURCE_TREES) {
			if (pathStr.contains(tree)) {
				inComputationTree = true;
				break;
			}
		}
		if (!inComputationTree) return;

		String fileName = file.getFileName().toString();

		// Check .evaluate() calls
		boolean evaluateAllowed = false;
		for (String allowed : EVALUATE_ALLOWED_FILES) {
			if (fileName.equals(allowed)) {
				evaluateAllowed = true;
				break;
			}
		}

		// Check .toDouble() calls
		boolean toDoubleAllowed = false;
		for (String allowed : TODOUBLE_ALLOWED_FILES) {
			if (fileName.equals(allowed)) {
				toDoubleAllowed = true;
				break;
			}
		}

		boolean inJavadoc = false;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmedLine = line.trim();
			int lineNum = i + 1;

			if (trimmedLine.startsWith("/**")) inJavadoc = true;
			if (trimmedLine.contains("*/")) { inJavadoc = false; continue; }
			if (inJavadoc || trimmedLine.startsWith("*") || trimmedLine.startsWith("//")) {
				continue;
			}

			// Check enclosing method — main methods and constructors are exempt
			String methodName = findEnclosingMethodName(lines, i);
			if (methodName.equals("main") || methodName.equals("<constructor>")) {
				continue;
			}

			if (!evaluateAllowed && EVALUATE_CALL.matcher(line).find()) {
				violations.add(new Violation(file, lineNum, line,
						"PRODUCER_EVALUATE_IN_COMPUTATION",
						".evaluate() inside computation code breaks the computation graph. " +
								"Return a CollectionProducer instead and let the caller evaluate at the pipeline boundary."));
			}

			if (!toDoubleAllowed && TODOUBLE_CALL.matcher(line).find()) {
				violations.add(new Violation(file, lineNum, line,
						"PRODUCER_TODOUBLE_IN_COMPUTATION",
						".toDouble() in computation code pulls data to host via JNI. " +
								"Use Producer operations (.multiply(), .add(), etc.) to keep computation on the device."));
			}
		}
	}

	/**
	 * Checks that classes whose names end in "Cell" implement
	 * {@code org.almostrealism.graph.Cell} and classes ending in "Block"
	 * implement {@code org.almostrealism.model.Block}.
	 */
	private void checkCellBlockNaming(Path file, String content, List<String> lines) {
		// Check for *Cell classes
		Matcher cellMatcher = CELL_CLASS_PATTERN.matcher(content);
		while (cellMatcher.find()) {
			String className = cellMatcher.group(1);
			int lineNum = countLines(content, cellMatcher.start());

			// Check if the class declaration or its hierarchy mentions Cell interface
			// Look for "implements ... Cell" or "extends ... Cell" or known Cell adapters
			if (!classImplementsInterface(content, className, "Cell", List.of(
					"CellAdapter", "CachedStateCell", "FilteredCell",
					"CollectionTemporalCellAdapter", "CollectionCachedStateCell",
					"SummationCell", "AudioCellChoiceAdapter", "BatchedCell"))) {
				violations.add(new Violation(file, lineNum,
						lines.get(Math.min(lineNum - 1, lines.size() - 1)),
						"CELL_NAMING_VIOLATION",
						"Class '" + className + "' ends in 'Cell' but does not implement " +
								"org.almostrealism.graph.Cell. All *Cell classes must implement the Cell interface."));
			}
		}

		// Check for *Block classes
		Matcher blockMatcher = BLOCK_CLASS_PATTERN.matcher(content);
		while (blockMatcher.find()) {
			String className = blockMatcher.group(1);
			int lineNum = countLines(content, blockMatcher.start());

			// Skip inner utility classes like CodeBlock in DuplicateCodeDetector
			if (className.equals("CodeBlock")) continue;

			if (!classImplementsInterface(content, className, "Block", List.of(
					"SequentialBlock", "DefaultBlock", "ForwardOnlyBlock", "BranchBlock"))) {
				violations.add(new Violation(file, lineNum,
						lines.get(Math.min(lineNum - 1, lines.size() - 1)),
						"BLOCK_NAMING_VIOLATION",
						"Class '" + className + "' ends in 'Block' but does not implement " +
								"org.almostrealism.model.Block. All *Block classes must implement the Block interface."));
			}
		}
	}

	/**
	 * Checks if a class declaration in the given content implements a target interface
	 * or extends a known base class that implements it.
	 */
	private boolean classImplementsInterface(String content, String className,
			String interfaceName, List<String> knownBaseClasses) {
		// Build a regex to find the class declaration line with extends/implements
		Pattern classLine = Pattern.compile(
				"class\\s+" + Pattern.quote(className) + "\\b[^{]*",
				Pattern.DOTALL
		);
		Matcher matcher = classLine.matcher(content);
		if (!matcher.find()) return false;

		String declaration = matcher.group();

		// Check implements directly
		if (declaration.contains("implements") && declaration.contains(interfaceName)) {
			return true;
		}

		// Check extends known base classes
		for (String base : knownBaseClasses) {
			if (declaration.contains("extends") && declaration.contains(base)) {
				return true;
			}
		}

		// Check extends a class that ends with the interface name (e.g., extends FooCell)
		if (declaration.contains("extends")) {
			Pattern extendsPattern = Pattern.compile("extends\\s+(\\w*" + interfaceName + ")\\b");
			if (extendsPattern.matcher(declaration).find()) {
				return true;
			}
		}

		return false;
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

	/**
	 * Returns {@code true} if the line at {@code currentLine} is inside a {@code for} loop body.
	 *
	 * @param lines        all lines of the file
	 * @param currentLine  0-based index of the line to check
	 * @return             whether the line is nested inside a {@code for} loop
	 */
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

	/**
	 * Returns {@code true} if the surrounding context of the given line suggests
	 * that a CPU-side manipulation loop is being used (e.g., combined with {@code toDouble()}).
	 *
	 * @param lines        all lines of the file
	 * @param currentLine  0-based index of the line to check
	 * @return             whether the context looks like a CPU manipulation loop
	 */
	private boolean looksLikeCpuManipulationLoop(List<String> lines, int currentLine) {
		// Look at surrounding context for signs of CPU manipulation
		String context = getContext(lines, currentLine, 5);
		return context.contains(".toDouble(") ||
				context.contains(".toFloat(") ||
				context.contains(".toArray(") ||
				(context.contains("for") && context.contains(".setMem("));
	}

	/**
	 * Returns a multi-line string containing the lines within {@code radius} of {@code centerLine}.
	 *
	 * @param lines       all lines of the file
	 * @param centerLine  0-based index of the center line
	 * @param radius      number of lines before and after to include
	 * @return            the context string
	 */
	private String getContext(List<String> lines, int centerLine, int radius) {
		int start = Math.max(0, centerLine - radius);
		int end = Math.min(lines.size(), centerLine + radius + 1);
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < end; i++) {
			sb.append(lines.get(i)).append("\n");
		}
		return sb.toString();
	}

	/**
	 * Counts the number of lines in {@code content} up to but not including {@code position}.
	 *
	 * @param content   the full file content
	 * @param position  the character offset to count up to
	 * @return          the 1-based line number at the given position
	 */
	private int countLines(String content, int position) {
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
	private int countChar(String s, char c) {
		int count = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) count++;
		}
		return count;
	}

	/**
	 * Returns {@code true} if the given path matches any of the exclusion patterns.
	 *
	 * @param path  the path to test
	 * @return      whether the path should be skipped during scanning
	 */
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
