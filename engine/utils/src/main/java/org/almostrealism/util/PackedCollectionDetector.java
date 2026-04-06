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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects PackedCollection GPU memory model violations.
 *
 * <p>This detector flags patterns that defeat GPU parallelism by performing
 * element-wise operations on {@code PackedCollection} from the Java (host) side:
 *
 * <ul>
 *   <li>{@code PACKED_COLLECTION_CPU_LOOP} — {@code setMem()} inside a {@code for}
 *       loop, or {@code setMem(index, expr.toDouble(index))} on a single line</li>
 *   <li>{@code PACKED_COLLECTION_ARRAYCOPY} — {@code System.arraycopy} or
 *       {@code Arrays.copyOf} near {@code PackedCollection} usage</li>
 *   <li>{@code PACKED_COLLECTION_CPU_ROUNDTRIP} — {@code toArray()} followed by
 *       {@code setMem()}, forcing a full GPU-CPU round trip</li>
 * </ul>
 *
 * <p>Files in {@link #LEGITIMATE_CPU_DOMAINS} and loops inside
 * {@link #INITIALIZATION_METHOD_PATTERNS initialization methods} are exempt.
 *
 * @see PolicyViolationDetector
 */
public class PackedCollectionDetector extends PolicyViolationDetector {

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

	/** Detects {@code setMem(index, expr.toDouble(index))} on a single line. */
	private static final Pattern SETMEM_WITH_INDEX = Pattern.compile(
			"\\.setMem\\s*\\(\\s*\\w+\\s*,.*\\.toDouble\\s*\\(\\s*\\w+\\s*\\)"
	);

	/** Detects calls to {@code System.arraycopy}. */
	private static final Pattern SYSTEM_ARRAYCOPY = Pattern.compile(
			"System\\.arraycopy\\s*\\("
	);

	/** Detects calls to {@code Arrays.copyOf}. */
	private static final Pattern ARRAYS_COPYOF = Pattern.compile(
			"Arrays\\.copyOf\\s*\\("
	);

	/** Detects a {@code toArray()} call followed by {@code setMem()} in a multi-line pattern. */
	private static final Pattern TOARRAY_SETMEM_PATTERN = Pattern.compile(
			"\\.toArray\\s*\\([^)]*\\)[^;]*;[^}]*\\.setMem\\s*\\(",
			Pattern.DOTALL
	);

	/**
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public PackedCollectionDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Scans a single file for PackedCollection GPU memory model violations.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public PackedCollectionDetector scanFile(Path file) {
		try {
			String content = Files.readString(file);

			// Only check PackedCollection rules if the file uses PackedCollection
			boolean usesPackedCollection = PACKED_COLLECTION_IMPORT.matcher(content).find()
					|| content.contains("PackedCollection");

			if (usesPackedCollection) {
				List<String> lines = Files.readAllLines(file);
				checkPackedCollectionViolations(file, content, lines);
			}
		} catch (IOException e) {
			warn("Could not read file " + file, e);
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

			// Check for System.arraycopy - only flag if it appears to involve PackedCollection data
			if (SYSTEM_ARRAYCOPY.matcher(line).find() && !inInitMethod) {
				String context = getContext(lines, i, 10);
				String lineLower = line.toLowerCase();
				if (context.contains("PackedCollection") &&
						!lineLower.contains("producer") && !lineLower.contains("supplier") &&
						!lineLower.contains("string") && !line.contains("Object") &&
						!lineLower.contains("sources")) {
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
	 * Checks if a file is in a domain where CPU loops with PackedCollection are legitimate.
	 *
	 * @param path  the file path to test
	 * @return      true if the file is in a legitimate CPU domain
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
	 * Returns {@code true} if the surrounding context of the given line suggests
	 * that a CPU-side manipulation loop is being used (e.g., combined with {@code toDouble()}).
	 *
	 * @param lines        all lines of the file
	 * @param currentLine  0-based index of the line to check
	 * @return             whether the context looks like a CPU manipulation loop
	 */
	private boolean looksLikeCpuManipulationLoop(List<String> lines, int currentLine) {
		String context = getContext(lines, currentLine, 5);
		return context.contains(".toDouble(") ||
				context.contains(".toFloat(") ||
				context.contains(".toArray(") ||
				(context.contains("for") && context.contains(".setMem("));
	}
}
