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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects references to planning documents from inside source code.
 *
 * <p>Planning documents (under {@code docs/plans/}, or any other
 * all-caps {@code .md} file outside the standard top-level set) are
 * collaboration artefacts — drafts, design rationale, open questions.
 * They get renamed, archived, and rewritten as work moves through the
 * project. A comment that says "see {@code MULTI_LAYER_AUDIO_FORMAT.md}"
 * becomes wrong the moment the doc is renamed; the code's documentation
 * should stand on its own.</p>
 *
 * <h2>What this detector flags</h2>
 * <ul>
 *   <li>The substring {@code docs/plans/} appearing anywhere in source.</li>
 *   <li>Any all-caps Markdown filename matching
 *       {@code [A-Z][A-Z0-9_]{4,}\.md}, e.g. {@code MULTI_LAYER_AUDIO_FORMAT.md}.
 *       A small allow-list lets through the conventional top-level docs
 *       ({@code README.md}, {@code CHANGELOG.md}, ...) so those names do not
 *       fire false positives.</li>
 * </ul>
 *
 * <h2>Scanned files</h2>
 * <p>{@code .java}, {@code .proto}, and {@code .pdsl} sources under the project
 * root, excluding {@code /test/} paths and the policy framework's own files.
 * Documentation files ({@code .md}, {@code .txt}, {@code .html}) are not
 * scanned — they are the right place to reference planning documents.</p>
 *
 * <h2>File-level allow-list</h2>
 * <p>A small set of files is exempt because the planning-document reference is
 * the legitimate subject of the code (an API field whose value is a path, an
 * agent prompt that documents a directory convention, etc.) rather than a
 * pointer the reader is expected to follow. Adding a file here is a deliberate
 * act and should be justified in the comment alongside its entry.</p>
 *
 * <h2>Detected violation code</h2>
 * <ul>
 *   <li>{@code PLANNING_DOC_REFERENCE_IN_SOURCE}</li>
 * </ul>
 *
 * @see PolicyViolationDetector
 * @see VersionReferenceDetector
 */
public class PlanningDocumentReferenceDetector extends PolicyViolationDetector {

	/** File extensions this detector scans. */
	private static final List<String> SCANNED_EXTENSIONS = List.of(".java", ".proto", ".pdsl");

	/** The forbidden directory prefix for planning documents. */
	private static final Pattern DOCS_PLANS_PATH = Pattern.compile("docs/plans/");

	/**
	 * All-caps Markdown filename pattern. Matches names with at least five
	 * uppercase / digit / underscore characters before {@code .md}, which is
	 * the convention used for planning, journaling, and review documents in
	 * this project.
	 */
	private static final Pattern PLANNING_DOC_NAME = Pattern.compile(
			"\\b[A-Z][A-Z0-9_]{4,}\\.md\\b");

	/**
	 * Conventional top-level documentation filenames that share the all-caps
	 * shape but are not planning documents. Matches against these names are
	 * never treated as violations.
	 */
	private static final Set<String> ALLOWED_DOC_NAMES = Set.of(
			"README.md",
			"CHANGELOG.md",
			"CONTRIBUTING.md",
			"LICENSE.md",
			"NOTICE.md",
			"CLAUDE.md",
			"COPYING.md");

	/**
	 * File-level allow-list. A path component match is sufficient (the path
	 * separator is included so substring matches do not collide across
	 * directories). Each entry must be justified by a real reason that the
	 * file's references are not pointers a reader would follow.
	 */
	private static final List<String> ALLOWLISTED_PATH_FRAGMENTS = List.of(
			// API endpoint javadoc that documents a planningDocument request field
			// (workstreams accept a path to a planning document; the example body
			// uses a representative path, not a reader pointer).
			"flowtree/core/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java",
			// Runtime instruction string that tells agents the directory convention
			// for new documents (planning docs go under docs/plans/, etc.). The
			// reference describes WHERE planning docs live, not WHICH one to read.
			"flowtree/core/src/main/java/io/flowtree/jobs/OrganizationalPlacementRule.java");

	/**
	 * Creates a detector that will scan source files under the given directory.
	 *
	 * @param rootDir  the root directory to scan
	 */
	public PlanningDocumentReferenceDetector(Path rootDir) {
		super(rootDir);
	}

	/**
	 * Walks the project tree and scans every {@code .java}, {@code .proto},
	 * and {@code .pdsl} file that is not excluded or allow-listed.
	 *
	 * @return this detector for chaining
	 * @throws IOException if directory traversal fails
	 */
	@Override
	public PlanningDocumentReferenceDetector scan() throws IOException {
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
	 * Scans a single file line-by-line. Both the {@code docs/plans/} substring
	 * and any non-allow-listed all-caps {@code .md} filename produce a
	 * violation; multiple matches on the same line each produce one.
	 *
	 * @param file  the file to scan
	 * @return this detector for chaining
	 */
	@Override
	public PlanningDocumentReferenceDetector scanFile(Path file) {
		try {
			List<String> lines = Files.readAllLines(file);

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				int lineNum = i + 1;

				Matcher pathMatcher = DOCS_PLANS_PATH.matcher(line);
				if (pathMatcher.find()) {
					violations.add(new Violation(file, lineNum, line,
							"PLANNING_DOC_REFERENCE_IN_SOURCE",
							"File contains the planning-document path 'docs/plans/'. "
									+ "Source code should not point at planning documents — "
									+ "make the inline documentation self-contained or remove the reference."));
				}

				Matcher nameMatcher = PLANNING_DOC_NAME.matcher(line);
				while (nameMatcher.find()) {
					String name = nameMatcher.group();
					if (ALLOWED_DOC_NAMES.contains(name)) continue;
					violations.add(new Violation(file, lineNum, line,
							"PLANNING_DOC_REFERENCE_IN_SOURCE",
							"File references planning-document filename '" + name + "'. "
									+ "Source code should not point at planning documents — "
									+ "make the inline documentation self-contained or remove the reference."));
				}
			}
		} catch (IOException e) {
			warn("Could not read file " + file, e);
		}

		return this;
	}
}
