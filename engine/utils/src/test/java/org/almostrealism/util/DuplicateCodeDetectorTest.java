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

import org.almostrealism.util.DuplicateCodeDetector.Violation;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Pins the detection contract of {@link DuplicateCodeDetector}: a run of
 * identical significant lines shared by two different files is reported, while
 * trivial lines (braces, imports, getters) are normalized away and known
 * intentional pairs are exempt.
 */
public class DuplicateCodeDetectorTest extends PolicyDetectorTestBase {

	/** Ten distinct, structurally significant lines shared by the duplicate fixtures. */
	private static final String BLOCK =
			"\t\tsum = sum + alpha * data[0];\n" +
			"\t\tsum = sum + beta * data[1];\n" +
			"\t\tsum = sum + gamma * data[2];\n" +
			"\t\tsum = sum + delta * data[3];\n" +
			"\t\tsum = sum + epsilon * data[4];\n" +
			"\t\tsum = sum + zeta * data[5];\n" +
			"\t\tsum = sum + eta * data[6];\n" +
			"\t\tsum = sum + theta * data[7];\n" +
			"\t\tsum = sum + iota * data[8];\n" +
			"\t\tsum = sum + kappa * data[9];\n";

	/** Two files sharing a ten-line block are reported as duplicates. */
	@Test(timeout = 20000)
	public void detectsSharedTenLineBlock() throws IOException {
		Path dir = sourceDir();
		write(dir, "Alpha.java", "public class Alpha {\n\tvoid alphaWork() {\n" + BLOCK + "\t}\n}\n");
		write(dir, "Beta.java", "public class Beta {\n\tvoid betaWork() {\n" + BLOCK + "\t}\n}\n");

		DuplicateCodeDetector detector = new DuplicateCodeDetector(dir);
		detector.scan();

		Assert.assertEquals(1, detector.getViolations().size());
		Violation v = detector.getViolations().get(0);
		Assert.assertEquals(DuplicateCodeDetector.DEFAULT_THRESHOLD, v.getBlockSize());
		Assert.assertNotEquals(v.getFileA().getFileName(), v.getFileB().getFileName());
		Assert.assertTrue(v.getPreview().contains("alpha * data[0]"));
	}

	/** The same block within a single file is not a cross-file duplicate. */
	@Test(timeout = 20000)
	public void ignoresBlockRepeatedInSameFile() throws IOException {
		Path dir = sourceDir();
		write(dir, "Solo.java",
				"public class Solo {\n\tvoid a() {\n" + BLOCK + "\t}\n\tvoid b() {\n" + BLOCK + "\t}\n}\n");

		DuplicateCodeDetector detector = new DuplicateCodeDetector(dir);
		detector.scan();

		Assert.assertFalse(detector.hasViolations());
	}

	/** With no duplication the report states the clean result and there are no violations. */
	@Test(timeout = 20000)
	public void reportsCleanWhenNoDuplicates() throws IOException {
		Path dir = sourceDir();
		write(dir, "Alpha.java", "public class Alpha {\n\tvoid alphaWork() {\n" + BLOCK + "\t}\n}\n");

		DuplicateCodeDetector detector = new DuplicateCodeDetector(dir);
		detector.scan();

		Assert.assertFalse(detector.hasViolations());
		Assert.assertTrue(detector.generateReport().contains("No duplicate code blocks"));
	}

	/**
	 * A lower threshold makes a shorter shared block a violation, and the
	 * reported block size follows the configured threshold.
	 */
	@Test(timeout = 20000)
	public void honorsCustomThreshold() throws IOException {
		String shortBlock =
				"\t\tsum = sum + alpha * data[0];\n" +
				"\t\tsum = sum + beta * data[1];\n" +
				"\t\tsum = sum + gamma * data[2];\n";
		Path dir = sourceDir();
		write(dir, "Alpha.java", "public class Alpha {\n\tvoid alphaWork() {\n" + shortBlock + "\t}\n}\n");
		write(dir, "Beta.java", "public class Beta {\n\tvoid betaWork() {\n" + shortBlock + "\t}\n}\n");

		DuplicateCodeDetector detector = new DuplicateCodeDetector(dir, 3);
		detector.scan();

		Assert.assertTrue(detector.hasViolations());
		Assert.assertEquals(3, detector.getViolations().get(0).getBlockSize());
		Assert.assertTrue(detector.generateReport().contains("DUPLICATE CODE VIOLATIONS"));
	}

	/** A known intentional pair (CollectionFeatures/ShapeFeatures) is exempt. */
	@Test(timeout = 20000)
	public void ignoresKnownPair() throws IOException {
		Path dir = sourceDir();
		write(dir, "CollectionFeatures.java",
				"public class CollectionFeatures {\n\tvoid work() {\n" + BLOCK + "\t}\n}\n");
		write(dir, "ShapeFeatures.java",
				"public class ShapeFeatures {\n\tvoid work() {\n" + BLOCK + "\t}\n}\n");

		DuplicateCodeDetector detector = new DuplicateCodeDetector(dir);
		detector.scan();

		Assert.assertFalse(detector.hasViolations());
	}
}
