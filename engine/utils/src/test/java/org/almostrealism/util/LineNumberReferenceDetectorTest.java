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

/**
 * Pins the detection contract of {@link LineNumberReferenceDetector}: both a
 * source-file-plus-offset reference and a prose offset reference in a comment
 * are flagged because they go stale on the next edit.
 *
 * <p>The stale-reference tokens the detector recognizes are assembled here
 * from fragments rather than written as contiguous literals, so that this test
 * source is not itself flagged by the repository's line-reference
 * enforcement.</p>
 */
public class LineNumberReferenceDetectorTest extends PolicyDetectorTestBase {

	/** A file-plus-offset reference, assembled to avoid the source guard. */
	private static final String FILE_OFFSET = "Foo.java:" + "123";

	/** A shorter file-plus-offset reference for the precedence test. */
	private static final String FILE_OFFSET_SHORT = "Foo.java:" + "9";

	/** A prose offset reference, assembled to avoid the source guard. */
	private static final String PROSE_OFFSET = "line " + "42";

	/** A plural prose offset reference, assembled to avoid the source guard. */
	private static final String PROSE_OFFSET_PLURAL = "Lines " + "10";

	/** A file-plus-offset reference is flagged. */
	@Test(timeout = 10000)
	public void detectsFileLineReference() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// see " + FILE_OFFSET + " for the origin\n" +
				"}\n");
		LineNumberReferenceDetector detector = new LineNumberReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Violation v = detector.getViolations().get(0);
		Assert.assertEquals("LINE_NUMBER_REFERENCE_IN_COMMENT", v.getRule());
		Assert.assertEquals(2, v.getLineNumber());
		Assert.assertTrue(v.getDescription().contains(FILE_OFFSET));
	}

	/** A prose offset reference is flagged. */
	@Test(timeout = 10000)
	public void detectsProseLineReference() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// as noted on " + PROSE_OFFSET + " above\n" +
				"}\n");
		LineNumberReferenceDetector detector = new LineNumberReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertTrue(detector.getViolations().get(0).getDescription().contains(PROSE_OFFSET));
	}

	/**
	 * When a line carries a file-plus-offset reference the file branch fires and
	 * the prose branch is skipped, so it counts as a single violation.
	 */
	@Test(timeout = 10000)
	public void fileLineReferenceTakesPrecedence() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// " + FILE_OFFSET_SHORT + " and " + "lines " + "5 too\n" +
				"}\n");
		LineNumberReferenceDetector detector = new LineNumberReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertTrue(detector.getViolations().get(0).getDescription().contains(FILE_OFFSET_SHORT));
	}

	/** Ordinary prose with no offset or file-plus-offset reference produces nothing. */
	@Test(timeout = 10000)
	public void ignoresPlainComment() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// a well-behaved comment about behavior\n" +
				"}\n");
		LineNumberReferenceDetector detector = new LineNumberReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** The plural offset spelling is also flagged. */
	@Test(timeout = 10000)
	public void detectsPluralLinesReference() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// removed in " + PROSE_OFFSET_PLURAL + "\n" +
				"}\n");
		LineNumberReferenceDetector detector = new LineNumberReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
	}
}
