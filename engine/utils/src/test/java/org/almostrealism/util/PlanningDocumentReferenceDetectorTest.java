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
 * Pins the detection contract of {@link PlanningDocumentReferenceDetector}:
 * planning-directory paths and screaming-case markdown filenames are flagged,
 * while allow-listed project docs (README, CLAUDE, ...) are not.
 *
 * <p>The planning-document tokens the detector recognizes are assembled here
 * from fragments rather than written as contiguous literals, so that this test
 * source is not itself flagged by the repository's planning-reference
 * enforcement.</p>
 */
public class PlanningDocumentReferenceDetectorTest extends PolicyDetectorTestBase {

	/** The planning-directory path token, assembled to avoid the source guard. */
	private static final String PLANS_PATH = "docs/" + "plans/";

	/** A screaming-case planning-document filename, assembled to avoid the source guard. */
	private static final String DESIGN_DOC = "DESIGN_NOTES" + ".md";

	/** A second screaming-case planning-document filename for the combined test. */
	private static final String PLAN_DOC = "PLAN_ALPHA" + ".md";

	/** A planning-directory path reference is flagged. */
	@Test(timeout = 10000)
	public void detectsPlansPath() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// see " + PLANS_PATH + "roadmap for details\n" +
				"}\n");
		PlanningDocumentReferenceDetector detector =
				new PlanningDocumentReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Violation v = detector.getViolations().get(0);
		Assert.assertEquals("PLANNING_DOC_REFERENCE_IN_SOURCE", v.getRule());
		Assert.assertEquals(2, v.getLineNumber());
		Assert.assertTrue(v.getDescription().contains(PLANS_PATH));
	}

	/** A screaming-case planning-document filename is flagged. */
	@Test(timeout = 10000)
	public void detectsPlanningDocFilename() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// implements " + DESIGN_DOC + "\n" +
				"}\n");
		PlanningDocumentReferenceDetector detector =
				new PlanningDocumentReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertTrue(detector.getViolations().get(0).getDescription().contains(DESIGN_DOC));
	}

	/** Allow-listed documentation filenames such as README are not flagged. */
	@Test(timeout = 10000)
	public void ignoresAllowedDocNames() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// documented in README.md and CLAUDE.md\n" +
				"}\n");
		PlanningDocumentReferenceDetector detector =
				new PlanningDocumentReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** Both a path and a filename on separate lines produce two violations. */
	@Test(timeout = 10000)
	public void detectsBothPathAndFilename() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// " + PLANS_PATH + "x\n" +
				"\t// " + PLAN_DOC + "\n" +
				"}\n");
		PlanningDocumentReferenceDetector detector =
				new PlanningDocumentReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(2, detector.getViolations().size());
	}

	/** A lowercase or short markdown reference does not match the screaming-case pattern. */
	@Test(timeout = 10000)
	public void ignoresLowercaseMarkdownName() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\t// see notes.md for context\n" +
				"}\n");
		PlanningDocumentReferenceDetector detector =
				new PlanningDocumentReferenceDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}
}
