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
 * Detects references to specific source-line numbers in comments and javadoc.
 *
 * <p>A reference like {@code Foo.java:123} or "{@code see createEfx() line 660-664}"
 * is stale the instant any line is added or removed above it — which happens on
 * nearly every edit. Such a pointer is worse than no pointer: a future reader
 * follows it to the wrong place and is actively misled. Refer to the stable thing
 * (the class, the method, the symbol) and let the reader find it; never cite a line
 * number.</p>
 *
 * <h2>What this detector flags</h2>
 * <ul>
 *   <li>A source-file-and-line reference: an identifier followed by a source
 *       extension and a colon-line, e.g. {@code MixdownManager.java:770} or
 *       {@code DirectBuffer.java:42}.</li>
 *   <li>A line-number reference in prose: {@code line 660}, {@code lines 504-521},
 *       {@code Line 42}, etc.</li>
 * </ul>
 *
 * <p>Section and row references (e.g. "Section 10 rows 19-20") and other
 * non-line numbers are intentionally not flagged — only line numbers, which are
 * the uniquely stale kind.</p>
 *
 * <h2>Scanned files</h2>
 * <p>{@code .java}, {@code .proto}, and {@code .pdsl} sources under the project
 * root. Unlike the other detectors, this one <strong>does</strong> scan
 * {@code /test/} sources: a line-number reference is never a legitimate
 * "intentional example" — it is stale wherever it appears. Build output
 * ({@code /target/}) and the policy framework's own files are excluded.</p>
 *
 * <h2>Detected violation code</h2>
 * <ul>
 *   <li>{@code LINE_NUMBER_REFERENCE_IN_COMMENT}</li>
 * </ul>
 *
 * @see PolicyViolationDetector
 * @see PlanningDocumentReferenceDetector
 */
public class LineNumberReferenceDetector extends PolicyViolationDetector {

	/** File extensions this detector scans. */
	private static final List<String> SCANNED_EXTENSIONS = List.of(".java", ".proto", ".pdsl");

	/**
	 * Paths excluded from scanning. Unlike {@link #EXCLUDED_PATHS}, this list does
	 * <em>not</em> exempt {@code /test/} — line-number references are stale in test
	 * comments just as in production comments. Build output and the policy framework
	 * itself are excluded so the detector does not flag its own pattern strings.
	 */
	private static final List<String> EXCLUDED_FRAGMENTS = List.of(
			"/target/",
			"PolicyViolationDetector.java",
			"CodePolicyViolationDetector.java",
			"PackedCollectionDetector.java",
			"ProducerPatternDetector.java",
			"NamingConventionDetector.java",
			"VersionReferenceDetector.java",
			"PlanningDocumentReferenceDetector.java",
			"LineNumberReferenceDetector.java",
			"CodePolicyEnforcementTest.java");

	/**
	 * A source-file name immediately followed by a colon and a line number, e.g.
	 * {@code MixdownManager.java:770} or {@code DirectBuffer.java:42}.
	 */
	private static final Pattern FILE_LINE = Pattern.compile(
			"\\b[A-Za-z_][A-Za-z0-9_]*\\.(?:java|pdsl|proto|kt|cpp|cc|hpp|h|py|js|ts):\\d+");

	/**
	 * A line-number reference in prose: the word "line" or "lines" followed by a
	 * number. The {@code \\b} before "line" prevents matches inside words such as
	 * "pipeline", "newline", or "deadline".
	 */
	private static final Pattern LINE_REF = Pattern.compile("\\b[Ll]ines?\\s+\\d+");

	/**
	 * Creates a detector that will scan source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public LineNumberReferenceDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Walks the project tree and scans every {@code .java}, {@code .proto}, and
	 * {@code .pdsl} file that is not excluded.
	 *
	 * @return this detector for chaining
	 * @throws IOException if directory traversal fails
	 */
	@Override
	public LineNumberReferenceDetector scan() throws IOException {
		violations.clear();

		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(this::hasScannedExtension)
					.filter(p -> !isExcludedFromLineCheck(p))
					.forEach(this::scanFile);
		}

		return this;
	}

	/**
	 * Returns {@code true} if {@code path} ends with one of the scanned extensions.
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
	 * Returns {@code true} if the file is excluded from the line-number check
	 * (build output or the policy framework itself). Test sources are intentionally
	 * <em>not</em> excluded.
	 *
	 * @param path  candidate file path
	 * @return      whether the file should be skipped
	 */
	private boolean isExcludedFromLineCheck(Path path) {
		String s = path.toString().replace('\\', '/');
		for (String fragment : EXCLUDED_FRAGMENTS) {
			if (s.contains(fragment)) return true;
		}
		return false;
	}

	/**
	 * Scans a single file line-by-line, flagging each source-file-and-line reference
	 * and each prose line-number reference.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public LineNumberReferenceDetector scanFile(Path file) {
		try {
			List<String> lines = Files.readAllLines(file);

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				int lineNum = i + 1;

				Matcher fileLine = FILE_LINE.matcher(line);
				if (fileLine.find()) {
					violations.add(new Violation(file, lineNum, line,
							"LINE_NUMBER_REFERENCE_IN_COMMENT",
							"Comment contains a source-line reference ('" + fileLine.group()
									+ "'). Line numbers go stale on the next edit — reference the "
									+ "class, method, or symbol instead, never a line number."));
					continue;
				}

				Matcher lineRef = LINE_REF.matcher(line);
				if (lineRef.find()) {
					violations.add(new Violation(file, lineNum, line,
							"LINE_NUMBER_REFERENCE_IN_COMMENT",
							"Comment references a line number ('" + lineRef.group().trim()
									+ "'). Line numbers go stale on the next edit — reference the "
									+ "class, method, or symbol instead, never a line number."));
				}
			}
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
	}
}
