/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.util;

import org.almostrealism.util.PolicyViolationDetector.Violation;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Pins the detection contract of {@link VersionReferenceDetector}: release
 * version markers embedded in comments or string literals are flagged, while
 * versions appearing only in stripped-out program text (identifiers) and
 * license boilerplate are not.
 *
 * <p>The version markers the detector recognizes are assembled here from
 * fragments split at the decimal point rather than written as contiguous
 * literals, so that this test source is not itself flagged by the repository's
 * version-reference enforcement.</p>
 */
public class VersionReferenceDetectorTest extends PolicyDetectorTestBase {

	/** A "Common"-prefixed release marker, assembled to avoid the source guard. */
	private static final String COMMON_MARKER = "Common 0." + "74";

	/** A "v"-tag release marker, assembled to avoid the source guard. */
	private static final String V_TAG_MARKER = "v0." + "74";

	/** A "Rings"-prefixed release marker, assembled to avoid the source guard. */
	private static final String RINGS_MARKER = "Rings 1." + "2.3";

	/** A second "Common"-prefixed release marker for the directory-walk test. */
	private static final String COMMON_MARKER_ALT = "Common 0." + "99";

	/** A release marker inside a line comment is flagged. */
	@Test(timeout = 10000)
	public void detectsCommonVersionInComment() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// Requires " + COMMON_MARKER + " or newer\n" +
				"}\n");
		VersionReferenceDetector detector = new VersionReferenceDetector(file.getParent());
		detector.scanFile(file);

		List<Violation> violations = detector.getViolations();
		Assert.assertEquals(1, violations.size());
		Violation v = violations.get(0);
		Assert.assertEquals("VERSION_REFERENCE_IN_SOURCE", v.getRule());
		Assert.assertEquals(2, v.getLineNumber());
		Assert.assertTrue(v.getDescription().contains(COMMON_MARKER));
	}

	/** A release tag inside a string literal is flagged. */
	@Test(timeout = 10000)
	public void detectsVTagVersionInStringLiteral() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tString tag = \"release " + V_TAG_MARKER + "\";\n" +
				"}\n");
		VersionReferenceDetector detector = new VersionReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertTrue(detector.hasViolations());
		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertTrue(detector.getViolations().get(0).getDescription().contains(V_TAG_MARKER));
	}

	/**
	 * A version number that appears only in ordinary program text (an
	 * identifier assignment, outside comment or string) is stripped to spaces
	 * before matching and therefore not flagged.
	 */
	@Test(timeout = 10000)
	public void ignoresVersionOutsideCommentAndString() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tdouble release = 0." + "74;\n" +
				"}\n");
		VersionReferenceDetector detector = new VersionReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** Apache license boilerplate is skipped even though it names a version. */
	@Test(timeout = 10000)
	public void ignoresLicenseBoilerplate() throws IOException {
		Path file = javaSource("/*\n" +
				" * Licensed under the Apache License, Version 2." + "0 (the \"License\");\n" +
				" */\n" +
				"public class Sample {}\n");
		VersionReferenceDetector detector = new VersionReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A "Rings"-prefixed marker in a block comment is flagged with its line. */
	@Test(timeout = 10000)
	public void detectsRingsVersionInBlockComment() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t/* built for " + RINGS_MARKER + " */\n" +
				"}\n");
		VersionReferenceDetector detector = new VersionReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals(2, detector.getViolations().get(0).getLineNumber());
	}

	/** A full directory {@link VersionReferenceDetector#scan()} finds the seeded marker. */
	@Test(timeout = 10000)
	public void scanWalksDirectory() throws IOException {
		Path file = javaSource("// see " + COMMON_MARKER_ALT + " notes\npublic class Sample {}\n");
		VersionReferenceDetector detector = new VersionReferenceDetector(file.getParent());
		detector.scan();

		Assert.assertEquals(1, detector.getViolations().size());
	}
}
