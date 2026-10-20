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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pins the aggregation contract of {@link CodePolicyViolationDetector}: it
 * merges the findings of every sub-detector, and it exposes the base-class
 * report machinery ({@link PolicyViolationDetector#generateReport()} and
 * {@link PolicyViolationDetector#generateMachineReport()}) over the merged set.
 *
 * <p>Trigger tokens are assembled from fragments so this test source is not
 * itself flagged by the repository's policy enforcement; the detector under
 * test still receives the assembled, contiguous tokens in the temporary
 * fixture it scans.</p>
 */
public class CodePolicyViolationDetectorTest extends PolicyDetectorTestBase {

	/** A "Common"-prefixed release marker, assembled to avoid the source guard. */
	private static final String VERSION_MARKER = "Common 0." + "74";

	/** A file-plus-offset reference, assembled to avoid the source guard. */
	private static final String FILE_OFFSET = "Foo.java:" + "123";

	/** A fixture line carrying two independent hygiene violations. */
	private String multiViolationSource() {
		return "public class Sample {\n" +
				"\t// ships with " + VERSION_MARKER + " noted in " + FILE_OFFSET + "\n" +
				"}\n";
	}

	/** A clean file yields no violations and a clean report. */
	@Test(timeout = 20000)
	public void reportsCleanForCleanFile() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tint size() { return 3; }\n" +
				"}\n");
		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
		Assert.assertEquals("No code policy violations detected.", detector.generateReport());
		Assert.assertTrue(detector.generateMachineReport().isEmpty());
	}

	/** Violations from more than one sub-detector are merged into one result set. */
	@Test(timeout = 20000)
	public void aggregatesAcrossSubDetectors() throws IOException {
		Path file = javaSource(multiViolationSource());
		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(file.getParent());
		detector.scanFile(file);

		List<Violation> violations = detector.getViolations();
		Assert.assertTrue("Expected at least two violations", violations.size() >= 2);

		Set<String> rules = violations.stream()
				.map(Violation::getRule)
				.collect(Collectors.toSet());
		Assert.assertTrue(rules.contains("VERSION_REFERENCE_IN_SOURCE"));
		Assert.assertTrue(rules.contains("LINE_NUMBER_REFERENCE_IN_COMMENT"));
	}

	/** The human report enumerates the violations and their running total. */
	@Test(timeout = 20000)
	public void humanReportSummarizesViolations() throws IOException {
		Path file = javaSource(multiViolationSource());
		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(file.getParent());
		detector.scanFile(file);

		String report = detector.generateReport();
		Assert.assertTrue(report.contains("CODE POLICY VIOLATIONS DETECTED"));
		Assert.assertTrue(report.contains("TOTAL: " + detector.getViolations().size()));
	}

	/**
	 * The machine report emits one marker-prefixed, tab-delimited line per
	 * violation and never wraps a record across lines.
	 */
	@Test(timeout = 20000)
	public void machineReportIsOneLinePerViolation() throws IOException {
		Path file = javaSource(multiViolationSource());
		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(file.getParent());
		detector.scanFile(file);

		String machine = detector.generateMachineReport();
		String[] lines = machine.split("\n");
		Assert.assertEquals(detector.getViolations().size(), lines.length);
		for (String line : lines) {
			Assert.assertTrue(line.startsWith(PolicyViolationDetector.MACHINE_REPORT_MARKER));
			// marker, file, line, rule, description => five tab-separated fields.
			Assert.assertEquals(5, line.split("\t").length);
		}
	}

	/** A full directory {@link CodePolicyViolationDetector#scan()} finds seeded violations. */
	@Test(timeout = 20000)
	public void scanWalksDirectory() throws IOException {
		Path file = javaSource(multiViolationSource());
		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(file.getParent());
		detector.scan();

		Assert.assertTrue(detector.hasViolations());
	}
}
