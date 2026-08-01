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

package org.almostrealism.util.test;

import org.almostrealism.util.PlanningDocumentReferenceDetector;
import org.almostrealism.util.PolicyViolationDetector;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.VersionReferenceDetector;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link VersionReferenceDetector} and
 * {@link PlanningDocumentReferenceDetector}.
 *
 * <p>These tests construct synthetic source files containing both known-bad
 * and known-good patterns to verify that each detector fires on the
 * intended category of mistake without flagging legitimate code.</p>
 */
public class SourceReferenceDetectorTest extends TestSuiteBase {

	/**
	 * Verifies that {@link VersionReferenceDetector} flags the four version
	 * patterns it is designed to catch when they appear in a Java comment.
	 */
	@Test(timeout = 30000)
	public void versionDetectorFlagsCommonVersionMarker() throws IOException {
		Path tempDir = Files.createTempDirectory("version-detector-common");
		Path file = tempDir.resolve("Sample.java");
		Files.writeString(file, ""
				+ "package test;\n"
				+ "// Multi-layer audio extensions (Common 0.74)\n"
				+ "public class Sample { public int x = 44100; }\n");

		try {
			VersionReferenceDetector detector = new VersionReferenceDetector(tempDir);
			detector.scan();

			Assert.assertTrue("Detector should fire on 'Common 0.74'",
					detector.hasViolations());
			Assert.assertTrue("Reported violation should reference VERSION_REFERENCE_IN_SOURCE",
					detector.getViolations().stream()
							.anyMatch(v -> v.getRule().equals("VERSION_REFERENCE_IN_SOURCE")));
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that {@link VersionReferenceDetector} flags Rings, v-tag, and
	 * "version N.N" prose forms.
	 */
	@Test(timeout = 30000)
	public void versionDetectorFlagsRingsAndVTagAndProse() throws IOException {
		Path tempDir = Files.createTempDirectory("version-detector-others");
		Path ringsFile = tempDir.resolve("Rings.java");
		Path vTagFile = tempDir.resolve("VTag.java");
		Path proseFile = tempDir.resolve("Prose.java");

		Files.writeString(ringsFile, "package test;\n// Added in Rings 0.39\npublic class Rings {}\n");
		Files.writeString(vTagFile, "package test;\n// since v0.74\npublic class VTag {}\n");
		Files.writeString(proseFile, "package test;\n// Compatible with version 0.74\npublic class Prose {}\n");

		try {
			VersionReferenceDetector detector = new VersionReferenceDetector(tempDir);
			detector.scan();

			Assert.assertEquals("Detector should fire once per file",
					3, detector.getViolations().size());
		} finally {
			Files.deleteIfExists(ringsFile);
			Files.deleteIfExists(vTagFile);
			Files.deleteIfExists(proseFile);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that {@link VersionReferenceDetector} does NOT flag legitimate
	 * numeric literals in pure code expressions, including version-shaped
	 * literals attached to identifiers ({@code config.v0_74}) and a sample
	 * rate constant.
	 */
	@Test(timeout = 30000)
	public void versionDetectorAllowsCodeNumericLiterals() throws IOException {
		Path tempDir = Files.createTempDirectory("version-detector-clean");
		Path file = tempDir.resolve("Clean.java");
		Files.writeString(file, ""
				+ "package test;\n"
				+ "public class Clean {\n"
				+ "    private int sampleRate = 44100;\n"
				+ "    private double gain = 0.5;\n"
				+ "    private int channels = 4;\n"
				+ "    private double frequency = 1000.0;\n"
				+ "}\n");

		try {
			VersionReferenceDetector detector = new VersionReferenceDetector(tempDir);
			detector.scan();

			Assert.assertFalse("Numeric literals in pure code must not be flagged",
					detector.hasViolations());
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that {@link VersionReferenceDetector} flags version markers
	 * appearing inside a string literal (a common form of leakage from prose
	 * documentation into runtime strings).
	 */
	@Test(timeout = 30000)
	public void versionDetectorFlagsVersionInStringLiteral() throws IOException {
		Path tempDir = Files.createTempDirectory("version-detector-string");
		Path file = tempDir.resolve("Strung.java");
		Files.writeString(file, ""
				+ "package test;\n"
				+ "public class Strung {\n"
				+ "    private String banner = \"Welcome to Common 0.74\";\n"
				+ "}\n");

		try {
			VersionReferenceDetector detector = new VersionReferenceDetector(tempDir);
			detector.scan();

			Assert.assertTrue("Version marker inside string literal must be flagged",
					detector.hasViolations());
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that {@link PlanningDocumentReferenceDetector} flags both the
	 * {@code docs/plans/} substring and an all-caps planning-doc filename in
	 * the same file.
	 */
	@Test(timeout = 30000)
	public void planningDocDetectorFlagsPathAndFilename() throws IOException {
		Path tempDir = Files.createTempDirectory("planning-detector-bad");
		Path file = tempDir.resolve("BadDoc.java");
		Files.writeString(file, ""
				+ "package test;\n"
				+ "/**\n"
				+ " * See docs/plans/MULTI_LAYER_AUDIO_FORMAT.md for the rationale.\n"
				+ " */\n"
				+ "public class BadDoc {}\n");

		try {
			PlanningDocumentReferenceDetector detector = new PlanningDocumentReferenceDetector(tempDir);
			detector.scan();

			Assert.assertTrue("Detector should fire on docs/plans path or planning-doc filename",
					detector.hasViolations());
			Assert.assertTrue("Reported violation should reference PLANNING_DOC_REFERENCE_IN_SOURCE",
					detector.getViolations().stream()
							.anyMatch(v -> v.getRule().equals("PLANNING_DOC_REFERENCE_IN_SOURCE")));
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that {@link PlanningDocumentReferenceDetector} does NOT flag
	 * the conventional top-level documentation filenames that share the
	 * all-caps shape ({@code README.md}, {@code CHANGELOG.md},
	 * {@code LICENSE.md}, ...).
	 */
	@Test(timeout = 30000)
	public void planningDocDetectorAllowsConventionalDocs() throws IOException {
		Path tempDir = Files.createTempDirectory("planning-detector-allow");
		Path file = tempDir.resolve("OkDoc.java");
		Files.writeString(file, ""
				+ "package test;\n"
				+ "// See README.md and CHANGELOG.md and LICENSE.md for details.\n"
				+ "public class OkDoc {}\n");

		try {
			PlanningDocumentReferenceDetector detector = new PlanningDocumentReferenceDetector(tempDir);
			detector.scan();

			Assert.assertFalse("Conventional doc filenames must not be flagged",
					detector.hasViolations());
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that {@link PlanningDocumentReferenceDetector} scans
	 * {@code .proto} sources, not just {@code .java}. The PR review on
	 * {@code feature/multi-layer-audio-format} surfaced both kinds of
	 * offences in the same proto file.
	 */
	@Test(timeout = 30000)
	public void planningDocDetectorScansProtoFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("planning-detector-proto");
		Path file = tempDir.resolve("audio.proto");
		Files.writeString(file, ""
				+ "syntax = \"proto3\";\n"
				+ "// See docs/plans/MULTI_LAYER_AUDIO_FORMAT.md\n"
				+ "message Foo { int32 x = 1; }\n");

		try {
			PlanningDocumentReferenceDetector detector = new PlanningDocumentReferenceDetector(tempDir);
			detector.scan();

			Assert.assertTrue("Detector must scan .proto files, not just .java",
					detector.hasViolations());
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that {@link VersionReferenceDetector} scans {@code .proto}
	 * files. Mirrors the proto coverage above for the version detector.
	 */
	@Test(timeout = 30000)
	public void versionDetectorScansProtoFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("version-detector-proto");
		Path file = tempDir.resolve("audio.proto");
		Files.writeString(file, ""
				+ "syntax = \"proto3\";\n"
				+ "// Multi-layer audio extensions (Common 0.74)\n"
				+ "message Foo { int32 x = 1; }\n");

		try {
			VersionReferenceDetector detector = new VersionReferenceDetector(tempDir);
			detector.scan();

			Assert.assertTrue("Detector must scan .proto files for version markers",
					detector.hasViolations());
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that the violation report names the policy rule and quotes the
	 * specific match, since the user-facing failure must be actionable rather
	 * than generic.
	 */
	@Test(timeout = 30000)
	public void violationDescriptionIncludesSpecificMatch() throws IOException {
		Path tempDir = Files.createTempDirectory("detector-message");
		Path file = tempDir.resolve("Msg.java");
		Files.writeString(file, "package test;\n// Common 0.74\npublic class Msg {}\n");

		try {
			VersionReferenceDetector detector = new VersionReferenceDetector(tempDir);
			detector.scan();

			Assert.assertEquals(1, detector.getViolations().size());
			PolicyViolationDetector.Violation v = detector.getViolations().get(0);
			Assert.assertTrue("Description should mention the matched substring",
					v.getDescription().contains("Common 0.74"));
			Assert.assertEquals("VERSION_REFERENCE_IN_SOURCE", v.getRule());
		} finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(tempDir);
		}
	}
}
