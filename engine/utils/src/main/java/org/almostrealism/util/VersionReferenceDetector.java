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
import java.util.stream.Stream;

/**
 * Detects release version markers embedded in source-code comments and string
 * literals.
 *
 * <p>Version numbers in source code rot the moment a release moves: a comment
 * that says "added in Common 0.74" is wrong on the day Common 0.75 ships. The
 * canonical record of which release introduced what lives in {@code pom.xml}
 * and the project changelog. Source code should describe what code does, not
 * which release shipped it.</p>
 *
 * <h2>What this detector flags</h2>
 * <ul>
 *   <li>{@code Common N.N(.N)?} — e.g. "Common 0.74"</li>
 *   <li>{@code Rings N.N(.N)?} — e.g. "Rings 0.39"</li>
 *   <li>{@code vN.N(.N)?} — e.g. "v0.74", standalone version-tag form</li>
 *   <li>{@code version N.N(.N)?} (case-insensitive) — e.g. "version 0.74"</li>
 * </ul>
 *
 * <p>The detector restricts itself to comment lines and string-literal regions
 * so that legitimate numeric literals in code expressions (e.g.
 * {@code int sampleRate = 44100}) are never flagged.</p>
 *
 * <h2>Scanned files</h2>
 * <p>{@code .java}, {@code .proto}, and {@code .pdsl} sources under the project
 * root, including test sources — a release marker is stale in a test comment
 * just as in production code. The policy framework's own files are excluded.
 * Build configuration ({@code pom.xml}) and documentation ({@code .md},
 * {@code .txt}, {@code .html}) are not scanned — version numbers are
 * legitimate there.</p>
 *
 * <h2>Detected violation code</h2>
 * <ul>
 *   <li>{@code VERSION_REFERENCE_IN_SOURCE}</li>
 * </ul>
 *
 * @see PolicyViolationDetector
 * @see PlanningDocumentReferenceDetector
 */
public class VersionReferenceDetector extends PolicyViolationDetector {

	/** File extensions this detector scans (in addition to {@code /test/} exclusion). */
	private static final List<String> SCANNED_EXTENSIONS = List.of(".java", ".proto", ".pdsl");

	/** "Common N.N" or "Common N.N.N" — the framework's own release marker. */
	private static final Pattern COMMON_VERSION = Pattern.compile(
			"\\bCommon\\s+\\d+\\.\\d+(?:\\.\\d+)?\\b");

	/** "Rings N.N" or "Rings N.N.N" — the rings application release marker. */
	private static final Pattern RINGS_VERSION = Pattern.compile(
			"\\bRings\\s+\\d+\\.\\d+(?:\\.\\d+)?\\b");

	/**
	 * Standalone version tag like "v0.74" or "v1.2.3". Word boundary on both
	 * sides so it does not match inside identifiers.
	 */
	private static final Pattern V_TAG_VERSION = Pattern.compile(
			"\\bv\\d+\\.\\d+(?:\\.\\d+)?\\b");

	/** "version N.N" — case-insensitive prose form. */
	private static final Pattern PROSE_VERSION = Pattern.compile(
			"(?i)\\bversion\\s+\\d+\\.\\d+(?:\\.\\d+)?\\b");

	/** All patterns scanned in order. The first match per line is reported. */
	private static final List<Pattern> ALL_PATTERNS = List.of(
			COMMON_VERSION, RINGS_VERSION, V_TAG_VERSION, PROSE_VERSION);

	/**
	 * Per-line skip phrases. When the inspectable text of a line contains any
	 * of these substrings (case-insensitive), the line is not scanned. The
	 * Apache-2.0 license boilerplate that appears at the top of every source
	 * file in this project contains "Apache License, Version 2.0", which would
	 * otherwise be matched by {@link #PROSE_VERSION}.
	 */
	private static final List<String> LINE_SKIP_PHRASES = List.of(
			"apache license",
			"license, version");

	/**
	 * File-level allow-list. A path-suffix or path-substring match is
	 * sufficient. Each entry must be justified by a real reason that the
	 * version-shaped text is genuinely not a release marker (typically a
	 * protocol or format version that just happens to look like a release
	 * tag).
	 */
	private static final List<String> ALLOWLISTED_PATH_FRAGMENTS = List.of(
			// NFS protocol versions ("v3", "v4.1"). These are protocol versions
			// of an external standard, not project release tags.
			"flowtree/graphpersist/src/main/java/io/almostrealism/nfs/NetworkFileSystemServer.java",
			// The detector's own unit test. Its fixtures embed deliberate release
			// markers ("Common 0.74", "Rings 0.39", "v0.74") as inputs that the
			// detector is expected to flag; they are not real version references.
			"tools/src/test/java/org/almostrealism/util/test/SourceReferenceDetectorTest.java");

	/**
	 * Creates a detector that will scan source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public VersionReferenceDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Walks the project tree and scans every {@code .java}, {@code .proto},
	 * and {@code .pdsl} file that is not excluded.
	 *
	 * @return this detector for chaining
	 * @throws IOException if directory traversal fails
	 */
	@Override
	public VersionReferenceDetector scan() throws IOException {
		violations.clear();

		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(this::hasScannedExtension)
					.filter(p -> !isExcluded(p))
					.filter(p -> !isAllowlisted(p))
					.forEach(this::scanFile);
		}

		return this;
	}

	/**
	 * Returns {@code true} if the file is exempt by the file-level allow-list.
	 *
	 * @param path  candidate file path
	 * @return      whether matches in this file should be ignored
	 */
	private boolean isAllowlisted(Path path) {
		String s = path.toString().replace('\\', '/');
		for (String fragment : ALLOWLISTED_PATH_FRAGMENTS) {
			if (s.endsWith(fragment) || s.contains(fragment)) return true;
		}
		return false;
	}

	/**
	 * Returns {@code true} if {@code path} ends with one of the scanned
	 * extensions.
	 *
	 * @param path  candidate file path
	 * @return      whether the file should be considered for scanning
	 */
	private boolean hasScannedExtension(Path path) {
		String s = path.toString();
		for (String ext : SCANNED_EXTENSIONS) {
			if (s.endsWith(ext)) return true;
		}
		return false;
	}

	/**
	 * Scans a single file line-by-line, restricting matches to comments and
	 * string-literal regions. The first matched pattern per line is recorded
	 * as a violation; subsequent matches on the same line are skipped to keep
	 * the report short.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public VersionReferenceDetector scanFile(Path file) {
		try {
			List<String> lines = Files.readAllLines(file);
			boolean inBlockComment = false;

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				String inspectable = extractInspectableText(line, inBlockComment);
				inBlockComment = updateBlockCommentState(line, inBlockComment);

				if (inspectable.isEmpty()) continue;
				if (containsSkipPhrase(inspectable)) continue;

				for (Pattern p : ALL_PATTERNS) {
					Matcher m = p.matcher(inspectable);
					if (m.find()) {
						String match = m.group();
						violations.add(new Violation(file, i + 1, line,
								"VERSION_REFERENCE_IN_SOURCE",
								"File contains apparent version reference '" + match
										+ "'. Source code should not embed release version markers — "
										+ "pom.xml is the source of truth for versions. "
										+ "Remove the reference or rephrase without naming a release."));
						break;
					}
				}
			}
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
	}

	/**
	 * Returns {@code true} if the inspectable text contains any phrase that
	 * disqualifies the line from version-marker scanning.
	 *
	 * @param inspectable the comment / string-literal text of a source line
	 * @return            whether the line should be skipped
	 */
	private boolean containsSkipPhrase(String inspectable) {
		String lower = inspectable.toLowerCase();
		for (String phrase : LINE_SKIP_PHRASES) {
			if (lower.contains(phrase)) return true;
		}
		return false;
	}

	/**
	 * Returns the portion of {@code line} that lies inside a comment or a
	 * string literal. Code outside those regions is replaced with spaces so
	 * patterns matched against the result cannot land on identifiers or
	 * numeric literals in pure code.
	 *
	 * <p>Recognises:
	 * <ul>
	 *   <li>Whole-line {@code /*} block comments and javadoc continuations
	 *       starting with {@code *}</li>
	 *   <li>The body of a multi-line block comment when {@code wasInBlockComment}
	 *       is {@code true}</li>
	 *   <li>Inline {@code //} comments — text after the marker</li>
	 *   <li>Double-quoted string literals</li>
	 * </ul>
	 *
	 * @param line              the raw source line
	 * @param wasInBlockComment whether the previous line ended inside a
	 *                          {@code /* ... *}{@code /} block
	 * @return                  a string of the same length where non-comment,
	 *                          non-string positions are replaced with spaces
	 */
	private String extractInspectableText(String line, boolean wasInBlockComment) {
		StringBuilder result = new StringBuilder(line.length());
		boolean inBlockComment = wasInBlockComment;
		boolean inLineComment = false;
		boolean inString = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';

			if (inBlockComment) {
				result.append(c);
				if (c == '*' && next == '/') {
					result.append('/');
					inBlockComment = false;
					i++;
				}
				continue;
			}
			if (inLineComment) {
				result.append(c);
				continue;
			}
			if (inString) {
				result.append(c);
				if (c == '\\' && next != '\0') {
					result.append(next);
					i++;
					continue;
				}
				if (c == '"') inString = false;
				continue;
			}

			if (c == '/' && next == '/') {
				inLineComment = true;
				result.append("//");
				i++;
				continue;
			}
			if (c == '/' && next == '*') {
				inBlockComment = true;
				result.append("/*");
				i++;
				continue;
			}
			if (c == '"') {
				inString = true;
				result.append('"');
				continue;
			}
			result.append(' ');
		}
		return result.toString();
	}

	/**
	 * Computes the new block-comment state after consuming {@code line}.
	 *
	 * @param line              the source line just consumed
	 * @param wasInBlockComment whether the previous line ended inside a block
	 * @return                  whether this line ended inside a block
	 */
	private boolean updateBlockCommentState(String line, boolean wasInBlockComment) {
		boolean inBlock = wasInBlockComment;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
			if (inBlock) {
				if (c == '*' && next == '/') {
					inBlock = false;
					i++;
				}
			} else {
				if (c == '/' && next == '*') {
					inBlock = true;
					i++;
				} else if (c == '/' && next == '/') {
					return inBlock;
				} else if (c == '"') {
					i++;
					while (i < line.length() && line.charAt(i) != '"') {
						if (line.charAt(i) == '\\' && i + 1 < line.length()) i++;
						i++;
					}
				}
			}
		}
		return inBlock;
	}
}
