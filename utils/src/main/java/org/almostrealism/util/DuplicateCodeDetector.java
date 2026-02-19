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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detector that identifies blocks of duplicate code across different files.
 *
 * <p>Code duplication is a maintenance hazard: bugs must be fixed in multiple
 * places, and divergence leads to subtle inconsistencies. This detector fails
 * the build when blocks of identical lines appear in more than one location.</p>
 *
 * <h2>How It Works</h2>
 * <p>The detector uses a sliding window approach. For each file, it extracts
 * every contiguous block of N non-trivial lines (where N is the configurable
 * threshold, defaulting to 10). These blocks are normalized (trimmed, blank
 * and comment-only lines removed) and fingerprinted. When the same fingerprint
 * appears in two or more distinct files, a violation is reported.</p>
 *
 * <h2>What Is Ignored</h2>
 * <ul>
 *   <li>Blank lines and lines that are only whitespace</li>
 *   <li>Single-line comments ({@code //})</li>
 *   <li>Import statements</li>
 *   <li>Package declarations</li>
 *   <li>Lines containing only braces ({@code &#123;} or {@code &#125;})</li>
 *   <li>Annotation-only lines (e.g. {@code @Override})</li>
 *   <li>License/copyright header blocks</li>
 * </ul>
 *
 * @see CodePolicyViolationDetector
 */
public class DuplicateCodeDetector {

	/**
	 * A pair of locations that share an identical block of code.
	 */
	public static class Violation {
		private final Path fileA;
		private final int lineA;
		private final Path fileB;
		private final int lineB;
		private final int blockSize;
		private final String preview;

		/**
		 * Creates a new duplicate code violation.
		 *
		 * @param fileA     first file
		 * @param lineA     starting line in the first file
		 * @param fileB     second file
		 * @param lineB     starting line in the second file
		 * @param blockSize number of matching lines
		 * @param preview   first few lines of the duplicate block
		 */
		public Violation(Path fileA, int lineA, Path fileB, int lineB,
						 int blockSize, String preview) {
			this.fileA = fileA;
			this.lineA = lineA;
			this.fileB = fileB;
			this.lineB = lineB;
			this.blockSize = blockSize;
			this.preview = preview;
		}

		/** Returns the first file containing the duplicate block. */
		public Path getFileA() { return fileA; }

		/** Returns the starting line number in the first file. */
		public int getLineA() { return lineA; }

		/** Returns the second file containing the duplicate block. */
		public Path getFileB() { return fileB; }

		/** Returns the starting line number in the second file. */
		public int getLineB() { return lineB; }

		/** Returns the number of matching lines. */
		public int getBlockSize() { return blockSize; }

		/** Returns a preview of the duplicate block content. */
		public String getPreview() { return preview; }

		@Override
		public String toString() {
			return String.format("%s:%d  <-->  %s:%d  (%d identical lines)\n    %s",
					fileA, lineA, fileB, lineB, blockSize,
					preview.replace("\n", "\n    "));
		}
	}

	/** Default minimum number of identical lines to trigger a violation. */
	public static final int DEFAULT_THRESHOLD = 10;

	/**
	 * Files excluded from scanning.
	 */
	private static final List<String> EXCLUDED_PATHS = List.of(
			"DuplicateCodeDetector.java",
			"CodePolicyEnforcementTest.java",
			"/test/",
			"/target/"
	);

	/**
	 * Known file pairs where code duplication is expected due to interface
	 * hierarchy delegation, parallel implementations, or legacy compatibility.
	 * Each pair is checked bidirectionally by file name.
	 */
	private static final List<String[]> KNOWN_PAIRS = List.of(
			// CollectionFeatures interface hierarchy - methods extracted to sub-interfaces
			new String[]{"CollectionFeatures.java", "AggregationFeatures.java"},
			new String[]{"CollectionFeatures.java", "ArithmeticFeatures.java"},
			new String[]{"CollectionFeatures.java", "CollectionCreationFeatures.java"},
			new String[]{"CollectionFeatures.java", "CollectionTraversalFeatures.java"},
			new String[]{"CollectionFeatures.java", "ComparisonFeatures.java"},
			new String[]{"CollectionFeatures.java", "GradientFeatures.java"},
			new String[]{"CollectionFeatures.java", "ShapeFeatures.java"},
			new String[]{"CollectionFeatures.java", "SlicingFeatures.java"},
			// Parallel encoder/decoder implementations with shared residual block patterns
			new String[]{"OobleckDecoder.java", "OobleckEncoder.java"},
			// Audio waveform cells with shared polyBlep and phase accumulation logic
			new String[]{"SawtoothWaveCell.java", "SquareWaveCell.java"},
			new String[]{"SawtoothWaveCell.java", "TriangleWaveCell.java"},
			new String[]{"SquareWaveCell.java", "TriangleWaveCell.java"},
			// Physics spanning tree implementations with shared grid traversal
			new String[]{"SpanningTreeAbsorber.java", "SpanningTreePotentialMap.java"},
			new String[]{"AtomicProtonCloud.java", "SpanningTreeAbsorber.java"},
			new String[]{"PotentialMapHashSet.java", "SpanningTreePotentialMap.java"},
			// Audio output line implementations with shared buffer management
			new String[]{"BufferOutputLine.java", "MockOutputLine.java"},
			// Expression nodes with shared simplification logic
			new String[]{"Mod.java", "Quotient.java"},
			// JNI print writer implementations for different backends
			new String[]{"CJNIPrintWriter.java", "CLJNIPrintWriter.java"},
			// Hardware data context implementations for different backends
			new String[]{"CLDataContext.java", "MetalDataContext.java"},
			// Loop computation variants with shared iteration logic
			new String[]{"Loop.java", "Periodic.java"},
			// Subset traversal expression and index mapping with shared traversal
			new String[]{"SubsetTraversalExpression.java", "SubsetTraversalIndexMapping.java"},
			// Audio filter implementations with shared DSP pipeline
			new String[]{"FilterEnvelopeProcessor.java", "MultiOrderFilterEnvelopeProcessor.java"},
			// Color distribution implementations with shared sampling
			new String[]{"OverlayDistribution.java", "RangeSumDistribution.java"},
			// Camera/reflection with shared coordinate transforms
			new String[]{"PinholeCamera.java", "ReflectedRay.java"},
			// Spatial brush implementations with shared density calculation
			new String[]{"FrequencyBandBrush.java", "GaussianBrush.java"},
			new String[]{"FrequencyBandBrush.java", "HarmonicBrush.java"},
			new String[]{"GaussianBrush.java", "HarmonicBrush.java"},
			// Audio persistence implementations with shared protobuf serialization
			new String[]{"AudioLibraryPersistence.java", "GeneratedSourceLibrary.java"},
			// Legacy compatibility - AudioGenerator delegates to LegacyAudioGenerator
			new String[]{"AudioGenerator.java", "LegacyAudioGenerator.java"}
	);

	/**
	 * Pattern to extract type names from extends/implements clauses.
	 */
	private static final Pattern EXTENDS_PATTERN = Pattern.compile(
			"(?:extends|implements)\\s+([\\w,\\s<>?]+?)\\s*(?:\\{|$)");

	/**
	 * Pattern to extract the simple class/interface name from a type declaration.
	 */
	private static final Pattern TYPE_NAME_PATTERN = Pattern.compile(
			"(?:class|interface|enum)\\s+(\\w+)");

	private final List<Violation> violations = new ArrayList<>();
	private final Path rootDir;
	private final int threshold;
	private final Map<String, Set<String>> typeHierarchy = new HashMap<>();
	private final Map<Path, String> fileToTypeName = new HashMap<>();

	/**
	 * Creates a detector with the default threshold of {@value #DEFAULT_THRESHOLD} lines.
	 *
	 * @param rootDir the root directory to scan
	 */
	public DuplicateCodeDetector(Path rootDir) {
		this(rootDir, DEFAULT_THRESHOLD);
	}

	/**
	 * Creates a detector with a custom threshold.
	 *
	 * @param rootDir   the root directory to scan
	 * @param threshold minimum number of identical lines to flag
	 */
	public DuplicateCodeDetector(Path rootDir, int threshold) {
		this.rootDir = rootDir;
		this.threshold = threshold;
	}

	/**
	 * Represents a block of code at a specific location.
	 */
	private static class CodeBlock {
		final Path file;
		final int originalStartLine;
		final List<String> normalizedLines;

		CodeBlock(Path file, int originalStartLine, List<String> normalizedLines) {
			this.file = file;
			this.originalStartLine = originalStartLine;
			this.normalizedLines = normalizedLines;
		}
	}

	/**
	 * Scans all Java source files under the root directory for duplicate blocks.
	 *
	 * @return this detector for chaining
	 * @throws IOException if file reading fails
	 */
	public DuplicateCodeDetector scan() throws IOException {
		violations.clear();
		typeHierarchy.clear();
		fileToTypeName.clear();

		// Collect all significant lines from each file, keyed by file
		Map<String, List<CodeBlock>> blocksByFingerprint = new HashMap<>();

		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !isExcluded(p))
					.forEach(file -> {
						buildHierarchy(file);
						indexFile(file, blocksByFingerprint);
					});
		}

		// Report violations where the same fingerprint appears in different files
		for (Map.Entry<String, List<CodeBlock>> entry : blocksByFingerprint.entrySet()) {
			List<CodeBlock> blocks = entry.getValue();
			if (blocks.size() < 2) continue;

			// Only report cross-file duplicates
			for (int i = 0; i < blocks.size(); i++) {
				for (int j = i + 1; j < blocks.size(); j++) {
					CodeBlock a = blocks.get(i);
					CodeBlock b = blocks.get(j);

					if (a.file.equals(b.file)) continue;
					if (areRelatedTypes(a.file, b.file)) continue;
					if (isKnownPair(a.file, b.file)) continue;

					String preview = buildPreview(a.normalizedLines);
					violations.add(new Violation(
							a.file, a.originalStartLine,
							b.file, b.originalStartLine,
							threshold, preview));
				}
			}
		}

		return this;
	}

	/**
	 * Indexes all sliding-window blocks from the given file.
	 */
	private void indexFile(Path file, Map<String, List<CodeBlock>> blocksByFingerprint) {
		try {
			List<String> rawLines = Files.readAllLines(file);

			// Build a list of significant lines with their original line numbers
			List<int[]> significantIndices = new ArrayList<>();
			List<String> significantLines = new ArrayList<>();

			for (int i = 0; i < rawLines.size(); i++) {
				String normalized = normalizeLine(rawLines.get(i));
				if (normalized != null) {
					significantIndices.add(new int[]{i + 1}); // 1-based
					significantLines.add(normalized);
				}
			}

			// Slide a window of size `threshold` over the significant lines
			for (int i = 0; i <= significantLines.size() - threshold; i++) {
				List<String> window = significantLines.subList(i, i + threshold);
				String fingerprint = String.join("\n", window);

				int originalStartLine = significantIndices.get(i)[0];
				CodeBlock block = new CodeBlock(file, originalStartLine, new ArrayList<>(window));

				blocksByFingerprint.computeIfAbsent(fingerprint, k -> new ArrayList<>()).add(block);
			}
		} catch (IOException e) {
			System.err.println("Warning: Could not read file " + file + ": " + e.getMessage());
		}
	}

	/**
	 * Normalizes a line for comparison. Returns {@code null} if the line should
	 * be excluded from duplicate detection (blank, comment-only, import, etc.).
	 */
	private String normalizeLine(String line) {
		String trimmed = line.trim();

		// Skip blank lines
		if (trimmed.isEmpty()) return null;

		// Skip single-line comments
		if (trimmed.startsWith("//")) return null;

		// Skip javadoc / block comment lines
		if (trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("*/")) return null;

		// Skip import and package statements
		if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) return null;

		// Skip lines that are only braces
		if (trimmed.equals("{") || trimmed.equals("}") || trimmed.equals("};")) return null;

		// Skip control-flow lines with braces (try, finally, catch, else)
		if (trimmed.matches("try\\s*\\{") || trimmed.matches("try\\s*\\(.*\\)\\s*\\{")
				|| trimmed.matches("\\}\\s*(finally|catch\\s*\\(.*\\)|else(\\s+if\\s*\\(.*\\))?)\\s*\\{?")) {
			return null;
		}

		// Skip annotation-only lines (e.g., @Override, @Test, @Deprecated)
		if (trimmed.startsWith("@") && !trimmed.contains("(") && !trimmed.contains(" ")) return null;

		// Skip simple getter returns and setter assignments
		if (trimmed.matches("return\\s+(this\\.)?\\w+;")) return null;
		if (trimmed.matches("this\\.\\w+\\s*=\\s*\\w+;")) return null;

		// Skip license boilerplate lines
		if (trimmed.startsWith("* Copyright") || trimmed.startsWith("* Licensed") ||
				trimmed.startsWith("* under the") || trimmed.startsWith("* http")) return null;

		// Skip trivial getter/setter lines (return field, this.field = param, accessor declarations)
		if (isGetterSetterLine(trimmed)) return null;

		return trimmed;
	}

	/** Pattern matching trivial getter declarations like {@code public String getFoo()}. */
	private static final Pattern GETTER_DECL = Pattern.compile(
			"^(public|protected|private)?\\s*\\w+(<[^>]+>)?\\s+[gs]et\\w+\\s*\\(.*\\)\\s*\\{?$"
	);

	/** Pattern matching trivial setter declarations like {@code public void setFoo(String foo)}. */
	private static final Pattern SETTER_ASSIGN = Pattern.compile(
			"^this\\.\\w+\\s*=\\s*\\w+;$"
	);

	/** Pattern matching simple return statements like {@code return fieldName;}. */
	private static final Pattern SIMPLE_RETURN = Pattern.compile(
			"^return\\s+\\w+;$"
	);

	/**
	 * Returns true if the line is part of a trivial getter or setter method.
	 * These bean patterns are expected to be identical across classes and
	 * are not meaningful duplication.
	 */
	private boolean isGetterSetterLine(String trimmed) {
		return GETTER_DECL.matcher(trimmed).matches() ||
				SETTER_ASSIGN.matcher(trimmed).matches() ||
				SIMPLE_RETURN.matcher(trimmed).matches();
	}

	/**
	 * Builds a short preview of a duplicate block (first 3 lines).
	 */
	private String buildPreview(List<String> lines) {
		StringBuilder sb = new StringBuilder();
		int previewSize = Math.min(3, lines.size());
		for (int i = 0; i < previewSize; i++) {
			if (i > 0) sb.append("\n");
			sb.append(lines.get(i));
		}
		if (lines.size() > previewSize) {
			sb.append("\n... (").append(lines.size() - previewSize).append(" more lines)");
		}
		return sb.toString();
	}

	/**
	 * Extracts the type name and its parent types from a Java source file
	 * to build an interface/class hierarchy map. Only examines top-level
	 * type declarations (lines starting with access modifiers).
	 */
	private void buildHierarchy(Path file) {
		try {
			List<String> rawLines = Files.readAllLines(file);

			for (String line : rawLines) {
				String trimmed = line.trim();

				// Only consider top-level type declarations
				if (!trimmed.startsWith("public ") && !trimmed.startsWith("abstract ")) {
					continue;
				}

				Matcher typeMatcher = TYPE_NAME_PATTERN.matcher(trimmed);
				if (!typeMatcher.find()) continue;

				String typeName = typeMatcher.group(1);
				fileToTypeName.put(file, typeName);

				Set<String> parents = new HashSet<>();
				Matcher extendsMatcher = EXTENDS_PATTERN.matcher(trimmed);
				while (extendsMatcher.find()) {
					String parentList = extendsMatcher.group(1);
					for (String parent : parentList.split(",")) {
						String[] fragments = parent.split("\\s+(?:implements|extends)\\s+");
						for (String fragment : fragments) {
							String cleaned = fragment.trim().replaceAll("<.*>", "").trim();
							if (!cleaned.isEmpty()) {
								parents.add(cleaned);
							}
						}
					}
				}

				if (!parents.isEmpty()) {
					typeHierarchy.put(typeName, parents);
				}

				break;
			}
		} catch (IOException e) {
			// Ignore unreadable files
		}
	}

	/**
	 * Returns true if the types declared in the two files are related
	 * through an extends or implements relationship (directly, transitively,
	 * or as siblings sharing a common ancestor).
	 */
	private boolean areRelatedTypes(Path fileA, Path fileB) {
		String typeA = fileToTypeName.get(fileA);
		String typeB = fileToTypeName.get(fileB);

		if (typeA == null || typeB == null) return false;

		if (isAncestor(typeA, typeB, new HashSet<>())
				|| isAncestor(typeB, typeA, new HashSet<>())) {
			return true;
		}

		Set<String> ancestorsA = getAllAncestors(typeA, new HashSet<>());
		Set<String> ancestorsB = getAllAncestors(typeB, new HashSet<>());
		ancestorsA.retainAll(ancestorsB);
		return !ancestorsA.isEmpty();
	}

	/**
	 * Returns all direct and transitive ancestors of the given type.
	 */
	private Set<String> getAllAncestors(String type, Set<String> visited) {
		Set<String> result = new HashSet<>();
		if (!visited.add(type)) return result;

		Set<String> parents = typeHierarchy.get(type);
		if (parents == null) return result;

		result.addAll(parents);
		for (String parent : parents) {
			result.addAll(getAllAncestors(parent, visited));
		}
		return result;
	}

	/**
	 * Returns true if {@code ancestor} is a direct or transitive parent
	 * of {@code descendant} in the type hierarchy.
	 */
	private boolean isAncestor(String descendant, String ancestor, Set<String> visited) {
		if (!visited.add(descendant)) return false;

		Set<String> parents = typeHierarchy.get(descendant);
		if (parents == null) return false;

		if (parents.contains(ancestor)) return true;

		for (String parent : parents) {
			if (isAncestor(parent, ancestor, visited)) return true;
		}

		return false;
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
	 * Returns true if the two files form a known pair where duplication is
	 * expected (e.g., interface hierarchy delegation or parallel implementations).
	 */
	private boolean isKnownPair(Path fileA, Path fileB) {
		String nameA = fileA.getFileName().toString();
		String nameB = fileB.getFileName().toString();
		for (String[] pair : KNOWN_PAIRS) {
			if ((nameA.equals(pair[0]) && nameB.equals(pair[1])) ||
					(nameA.equals(pair[1]) && nameB.equals(pair[0]))) {
				return true;
			}
		}
		return false;
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
			return "No duplicate code blocks of " + threshold + "+ identical lines detected.";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("=== DUPLICATE CODE VIOLATIONS ===\n\n");
		sb.append("Blocks of ").append(threshold)
				.append("+ identical lines found across different files:\n\n");

		for (Violation v : violations) {
			sb.append(v.toString()).append("\n\n");
		}

		sb.append("=== TOTAL: ").append(violations.size()).append(" duplicate block(s) ===\n\n");
		sb.append("Refactor duplicated code into shared helper methods or utilities.\n");

		return sb.toString();
	}
}
