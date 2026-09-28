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
 * Pins the detection contract of {@link SetMemLiteralsDetector}: the host-to-device
 * write surface ({@code setMem}, {@code PackedCollection.of}, {@code fill}, {@code pack})
 * accepts numeric literals (and, for {@code setMem}, a single literal array or the
 * five-argument bulk form), but rejects host arrays, device read-backs, and computed
 * calls; sanctioned write-surface files and acknowledged burn-down exclusions are exempt.
 *
 * <p>The detector masks string and comment contents before matching, so the write-surface
 * call fragments used as fixtures here — which live inside Java string literals in this
 * source — are not themselves flagged by the repository's enforcement; the detector under
 * test still sees them as code once they are written to the temporary fixture file.</p>
 */
public class SetMemLiteralsDetectorTest extends PolicyDetectorTestBase {

	/**
	 * Wraps a single statement in a small class body so the detector has a
	 * realistic file to scan.
	 *
	 * @param statement the statement to place inside a method body
	 * @return the file body
	 */
	private String classWith(String statement) {
		return "public class Sample {\n\tvoid m() {\n\t\t" + statement + "\n\t}\n}\n";
	}

	/** Writing a host array element at an index is flagged. */
	@Test(timeout = 15000)
	public void detectsSetMemFromHostArray() throws IOException {
		Path file = javaSource(classWith("dest.setMem(i, host[i]);"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals(SetMemLiteralsDetector.RULE, detector.getViolations().get(0).getRule());
	}

	/** A single numeric literal written at an index is sanctioned. */
	@Test(timeout = 15000)
	public void allowsLiteralIndexedSetMem() throws IOException {
		Path file = javaSource(classWith("dest.setMem(0, 1.0);"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** The five-argument bulk copy form of setMem is sanctioned. */
	@Test(timeout = 15000)
	public void allowsFiveArgumentBulkSetMem() throws IOException {
		Path file = javaSource(classWith("dest.setMem(0, src, 0, 4, 8);"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A literal array initializer passed to setMem is sanctioned. */
	@Test(timeout = 15000)
	public void allowsLiteralArraySetMem() throws IOException {
		Path file = javaSource(classWith("dest.setMem(new double[]{1.0, 2.0});"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A device read-back handed to {@code PackedCollection.of} is flagged. */
	@Test(timeout = 15000)
	public void detectsOfFromDeviceReadback() throws IOException {
		Path file = javaSource(classWith("PackedCollection.of(src.toArray(0, 4));"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals(SetMemLiteralsDetector.OF_RULE, detector.getViolations().get(0).getRule());
	}

	/** {@code PackedCollection.of} with individual numeric literals is sanctioned. */
	@Test(timeout = 15000)
	public void allowsOfFromLiterals() throws IOException {
		Path file = javaSource(classWith("PackedCollection.of(1.0, 2.0, 3.0);"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A scatter write via {@code range(...).fill(...)} is always flagged. */
	@Test(timeout = 15000)
	public void detectsRangeViewFill() throws IOException {
		Path file = javaSource(classWith("dest.range(shape).fill(v);"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals(SetMemLiteralsDetector.RANGE_FILL_RULE,
				detector.getViolations().get(0).getRule());
	}

	/** {@code pack} of a device read-back exceeds the ingest allowance and is flagged. */
	@Test(timeout = 15000)
	public void detectsPackFromReadback() throws IOException {
		Path file = javaSource(classWith("pack(vals.toArray());"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals(SetMemLiteralsDetector.INGEST_RULE,
				detector.getViolations().get(0).getRule());
	}

	/** Files on the sanctioned write surface are skipped entirely. */
	@Test(timeout = 15000)
	public void skipsSanctionedWriteSurface() throws IOException {
		Path file = writeSourceAt("collect/PackedCollection.java",
				classWith("dest.setMem(i, host[i]);"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** An acknowledged burn-down exclusion line is not flagged. */
	@Test(timeout = 15000)
	public void respectsKnownExclusion() throws IOException {
		Path file = writeSourceAt("space/MeshData.java",
				classWith("destination.setMem(i, result.toDouble(i * 2));"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** The baseline inventory loads and the exemption summary reports it. */
	@Test(timeout = 15000)
	public void loadsBaselineAndSummarizes() throws IOException {
		Path file = javaSource("public class Sample {\n\tint size() { return 1; }\n}\n");
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), true);
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
		String summary = detector.exemptionSummary();
		Assert.assertTrue(summary.contains("Exemptions remaining"));
		Assert.assertTrue(summary.contains("inventory holds"));
	}

	/** A directory {@link SetMemLiteralsDetector#scan()} finds a seeded violation. */
	@Test(timeout = 15000)
	public void scanWalksDirectory() throws IOException {
		Path file = javaSource(classWith("dest.setMem(i, host[i]);"));
		SetMemLiteralsDetector detector = new SetMemLiteralsDetector(file.getParent(), false);
		detector.scan();

		List<Violation> violations = detector.getViolations();
		Assert.assertEquals(1, violations.size());
		Assert.assertEquals(SetMemLiteralsDetector.RULE, violations.get(0).getRule());
	}
}
