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
 * Detects naming convention violations for {@code *Cell}, {@code *Block}, and
 * {@code *Features} types.
 *
 * <p>The Almost Realism framework uses naming conventions to enforce architectural
 * contracts:
 * <ul>
 *   <li>Any class whose name ends in {@code Cell} <b>MUST</b> implement
 *       {@code org.almostrealism.graph.Cell}.</li>
 *   <li>Any class whose name ends in {@code Block} <b>MUST</b> implement
 *       {@code org.almostrealism.model.Block}.</li>
 *   <li>Any interface whose name ends in {@code Features} <b>MUST</b> contain only
 *       {@code default} or {@code static} methods — abstract methods force
 *       implementations and break the Features composition pattern.</li>
 * </ul>
 *
 * <p>Detected violation codes:
 * <ul>
 *   <li>{@code CELL_NAMING_VIOLATION}</li>
 *   <li>{@code BLOCK_NAMING_VIOLATION}</li>
 *   <li>{@code FEATURES_INTERFACE_ABSTRACT_METHOD}</li>
 * </ul>
 *
 * @see PolicyViolationDetector
 */
public class NamingConventionDetector extends PolicyViolationDetector {

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
	 * Creates a detector that will scan Java source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public NamingConventionDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Scans a single file for naming convention violations.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public NamingConventionDetector scanFile(Path file) {
		try {
			String content = Files.readString(file);
			List<String> lines = Files.readAllLines(file);

			checkCellBlockNaming(file, content, lines);
			checkFeaturesInterfaceViolations(file, content, lines);
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
	}

	/**
	 * Checks that classes whose names end in "Cell" implement
	 * {@code org.almostrealism.graph.Cell} and classes ending in "Block"
	 * implement {@code org.almostrealism.model.Block}.
	 *
	 * @param file     the file being checked
	 * @param content  the full file content as a string
	 * @param lines    the file content split into lines
	 */
	private void checkCellBlockNaming(Path file, String content, List<String> lines) {
		// Check for *Cell classes
		Matcher cellMatcher = CELL_CLASS_PATTERN.matcher(content);
		while (cellMatcher.find()) {
			String className = cellMatcher.group(1);
			int lineNum = countLines(content, cellMatcher.start());

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
	 * Checks for "Features" interfaces that have abstract methods.
	 *
	 * <p>"Features" interfaces should only contain default methods — they exist to
	 * provide capabilities via composition, not to require implementations.</p>
	 *
	 * @param file     the file being checked
	 * @param content  the full file content as a string
	 * @param lines    the file content split into lines
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

			// Skip if we're on/before the interface declaration line
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

			// Skip lines that contain statements (not declarations)
			if (trimmedLine.startsWith("return ") || trimmedLine.startsWith("if ") ||
					trimmedLine.startsWith("if(") || trimmedLine.startsWith("for ") ||
					trimmedLine.startsWith("for(") || trimmedLine.startsWith("while ") ||
					trimmedLine.startsWith("throw ") || trimmedLine.startsWith("try ")) {
				continue;
			}

			// Skip lines that are just braces
			if (trimmedLine.equals("{") || trimmedLine.equals("}") ||
					trimmedLine.equals("};") || trimmedLine.endsWith("{")) {
				continue;
			}

			// Check if this looks like an abstract method declaration
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
}
