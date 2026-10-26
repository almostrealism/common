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

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Pins the GPU-memory-model detection contract of {@link PackedCollectionDetector}:
 * host-side manipulation of {@code PackedCollection} — {@code fill(0)},
 * {@code setMem} with {@code toDouble}, {@code System.arraycopy}, and
 * {@code toArray()} round-trips — is flagged, while a Producer-pattern body is
 * left alone.
 *
 * <p>The synthetic sources here use a method named {@code mutate} deliberately:
 * the detector exempts one-time initialization methods (names containing
 * {@code init}, {@code create}, {@code compute}, and similar), so a neutral
 * name is required to exercise the violation paths.</p>
 */
public class PackedCollectionDetectorTest extends PolicyDetectorTestBase {

	/**
	 * A host-side zero-fill on a PackedCollection is flagged as building content
	 * on the host. The fill token is assembled from fragments so this test source
	 * is not itself flagged by the repository's GPU-memory-model enforcement.
	 */
	@Test(timeout = 10000)
	public void detectsFillZero() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid mutate(PackedCollection c) {\n" +
				"\t\tc.fill(" + "0);\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PACKED_COLLECTION_FILL_ZERO",
				detector.getViolations().get(0).getRule());
		Assert.assertEquals(4, detector.getViolations().get(0).getLineNumber());
	}

	/** {@code setMem(i, x.toDouble(i))} is flagged as a CPU element-wise loop pattern. */
	@Test(timeout = 10000)
	public void detectsSetMemWithToDouble() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid mutate(PackedCollection c, PackedCollection s) {\n" +
				"\t\tc.setMem(i, s.toDouble(i));\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PACKED_COLLECTION_CPU_LOOP",
				detector.getViolations().get(0).getRule());
	}

	/** {@code System.arraycopy} near PackedCollection cannot move device memory and is flagged. */
	@Test(timeout = 10000)
	public void detectsArrayCopy() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid mutate(PackedCollection a, double[] b) {\n" +
				"\t\tSystem.arraycopy(a, 0, b, 0, 4);\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PACKED_COLLECTION_ARRAYCOPY",
				detector.getViolations().get(0).getRule());
	}

	/** A {@code toArray()} read followed by {@code setMem} is flagged as a CPU round-trip. */
	@Test(timeout = 10000)
	public void detectsToArrayRoundtrip() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid mutate(PackedCollection c) {\n" +
				"\t\tdouble[] d = c.toArray(0, 4);\n" +
				"\t\tc.setMem(0, d, 0, 4);\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PACKED_COLLECTION_CPU_ROUNDTRIP",
				detector.getViolations().get(0).getRule());
	}

	/** A Producer-pattern body over a PackedCollection produces no violation. */
	@Test(timeout = 10000)
	public void ignoresProducerPatternBody() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid mutate(PackedCollection c) {\n" +
				"\t\tcp(c).multiply(2.0).evaluate();\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** {@code Arrays.copyOf} near PackedCollection cannot copy device memory and is flagged. */
	@Test(timeout = 10000)
	public void detectsArraysCopyOf() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid mutate(PackedCollection a, double[] b) {\n" +
				"\t\tb = Arrays.copyOf(b, 8);\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PACKED_COLLECTION_ARRAYCOPY",
				detector.getViolations().get(0).getRule());
	}

	/** An element-wise store inside a for loop over host values is flagged as a CPU loop. */
	@Test(timeout = 10000)
	public void detectsSetMemInsideForLoop() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid mutate(PackedCollection dest, PackedCollection src, int n) {\n" +
				"\t\tfor (int i = 0; i < n; i++) {\n" +
				"\t\t\tdouble v = src.toDouble(i);\n" +
				"\t\t\tdest.setMem(i, v + 1.0);\n" +
				"\t\t}\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertEquals(1, detector.getViolations().size());
		Assert.assertEquals("PACKED_COLLECTION_CPU_LOOP",
				detector.getViolations().get(0).getRule());
	}

	/**
	 * A {@code System.arraycopy} inside a one-time initialization method is
	 * exempt, since setup code runs once and does not defeat GPU parallelism.
	 */
	@Test(timeout = 10000)
	public void exemptsArrayCopyInInitializationMethod() throws IOException {
		Path file = javaSource("import org.almostrealism.collect.PackedCollection;\n" +
				"public class Sample {\n" +
				"\tvoid initialize(PackedCollection a, double[] b) {\n" +
				"\t\tSystem.arraycopy(a, 0, b, 0, 4);\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}

	/** A file that never mentions PackedCollection is skipped entirely. */
	@Test(timeout = 10000)
	public void ignoresFileWithoutPackedCollection() throws IOException {
		Path file = javaSource("public class Sample {\n" +
				"\tvoid mutate(double[] c) {\n" +
				"\t\tSystem.arraycopy(c, 0, c, 1, 2);\n" +
				"\t}\n" +
				"}\n");
		PackedCollectionDetector detector = new PackedCollectionDetector(file.getParent());
		detector.scanFile(file);

		Assert.assertFalse(detector.hasViolations());
	}
}
