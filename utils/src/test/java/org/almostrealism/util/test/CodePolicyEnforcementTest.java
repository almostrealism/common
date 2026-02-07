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

import org.almostrealism.util.CodePolicyViolationDetector;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Build enforcement test that scans all source code for policy violations.
 *
 * <p><b>THIS TEST MUST PASS FOR THE BUILD TO SUCCEED.</b></p>
 *
 * <p>This test enforces the rules in CLAUDE.md by failing the build when
 * code violates the PackedCollection GPU memory model. Documentation alone
 * does not prevent violations - the build must fail.</p>
 *
 * <h2>What This Test Detects</h2>
 * <ul>
 *   <li>CPU loops with setMem() that defeat GPU parallelism</li>
 *   <li>toDouble()/toArray() patterns that force GPU-CPU round trips</li>
 *   <li>System.arraycopy near PackedCollection usage</li>
 * </ul>
 *
 * <h2>How to Fix Violations</h2>
 * <p>Use the Producer pattern: {@code cp(x).multiply(...).add(...).evaluate()}
 * The general pattern is to replace CPU loops with the Producer pattern:</p>
 *
 * <pre>{@code
 * // WRONG - CPU loop
 * for (int i = 0; i < size; i++) {
 *     result.setMem(i, source.toDouble(i) * 2);
 * }
 *
 * // CORRECT - GPU accelerated
 * CollectionProducer result = cp(source).multiply(2.0);
 * return result.evaluate();
 * }</pre>
 *
 * @see CodePolicyViolationDetector
 */
public class CodePolicyEnforcementTest extends TestSuiteBase {

	/**
	 * Root directory of the project.
	 * Adjusted to find the common/ directory from the utils module.
	 */
	private static final Path PROJECT_ROOT = findProjectRoot();

	/**
	 * Source directories to scan for violations.
	 */
	private static final List<String> SOURCE_DIRS = List.of(
			"algebra/src/main/java",
			"audio/src/main/java",
			"chemistry/src/main/java",
			"code/src/main/java",
			"collect/src/main/java",
			"color/src/main/java",
			"compose/src/main/java",
			"geometry/src/main/java",
			"graph/src/main/java",
			"hardware/src/main/java",
			"heredity/src/main/java",
			"io/src/main/java",
			"ml/src/main/java",
			"music/src/main/java",
			"optimize/src/main/java",
			"physics/src/main/java",
			"relation/src/main/java",
			"render/src/main/java",
			"space/src/main/java",
			"stats/src/main/java",
			"time/src/main/java",
			"uml/src/main/java",
			"utils/src/main/java"
	);

	private static Path findProjectRoot() {
		// Start from current directory and look for common/ or pom.xml
		Path current = Path.of("").toAbsolutePath();

		// If we're in a submodule, go up to find common/
		while (current != null) {
			if (Files.exists(current.resolve("pom.xml")) &&
					Files.exists(current.resolve("algebra")) &&
					Files.exists(current.resolve("ml"))) {
				return current;
			}
			current = current.getParent();
		}

		// Fallback to workspace path
		Path workspace = Path.of("/workspace/project/common");
		if (Files.exists(workspace)) {
			return workspace;
		}

		return Path.of("").toAbsolutePath();
	}

	/**
	 * Scans all source directories for code policy violations.
	 *
	 * <p>This test FAILS THE BUILD if any violations are detected.
	 * There is no way to skip or ignore violations - the code must be fixed.</p>
	 */
	@Test
	public void enforceCodePolicies() throws IOException {
		log("=== Code Policy Enforcement ===");
		log("Project root: " + PROJECT_ROOT);

		CodePolicyViolationDetector detector = new CodePolicyViolationDetector(PROJECT_ROOT);

		int filesScanned = 0;
		for (String sourceDir : SOURCE_DIRS) {
			Path dir = PROJECT_ROOT.resolve(sourceDir);
			if (Files.exists(dir)) {
				log("Scanning: " + sourceDir);
				detector.scan();
				filesScanned++;
			}
		}

		if (filesScanned == 0) {
			log("WARNING: No source directories found. Project root may be incorrect.");
			log("Attempting to scan from: " + PROJECT_ROOT);
			detector.scan();
		}

		if (detector.hasViolations()) {
			String report = detector.generateReport();
			log("\n" + report);

			Assert.fail("BUILD FAILED: " + detector.getViolations().size() +
					" code policy violation(s) detected.\n\n" +
					"These violations MUST be fixed before the build can succeed.\n" +
					"Use the Producer pattern: cp(x).multiply(...).add(...).evaluate()\n\n" +
					report);
		}

		log("No code policy violations detected.");
		log("=== Code Policy Enforcement PASSED ===");
	}

	/**
	 * Verifies that the detector correctly identifies known violation patterns.
	 *
	 * <p>This test ensures the detector itself is working correctly by
	 * testing it against synthetic examples.</p>
	 */
	@Test
	public void testDetectorAccuracy() throws IOException {
		// Create a temporary file with known violations
		Path tempDir = Files.createTempDirectory("policy-test");
		Path testFile = tempDir.resolve("TestViolation.java");

		String violatingCode = """
				package test;
				import org.almostrealism.collect.PackedCollection;
				public class TestViolation {
				    public void badMethod(PackedCollection source, PackedCollection result) {
				        for (int i = 0; i < 10; i++) {
				            result.setMem(i, source.toDouble(i) * 2);
				        }
				    }
				}
				""";

		Files.writeString(testFile, violatingCode);

		try {
			CodePolicyViolationDetector detector = new CodePolicyViolationDetector(tempDir);
			detector.scan();

			Assert.assertTrue("Detector should find violations in test code",
					detector.hasViolations());

			log("Detector correctly identified " + detector.getViolations().size() +
					" violation(s) in synthetic test code.");

		} finally {
			// Cleanup
			Files.deleteIfExists(testFile);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that clean code passes the detector.
	 */
	@Test
	public void testDetectorAllowsCleanCode() throws IOException {
		Path tempDir = Files.createTempDirectory("policy-test-clean");
		Path testFile = tempDir.resolve("CleanCode.java");

		String cleanCode = """
				package test;
				import org.almostrealism.collect.PackedCollection;
				import org.almostrealism.collect.CollectionProducer;
				import static org.almostrealism.collect.CollectionFeatures.cp;
				public class CleanCode {
				    public PackedCollection goodMethod(PackedCollection source) {
				        // This is the correct pattern - no CPU loop
				        CollectionProducer result = cp(source).multiply(2.0);
				        return result.evaluate();
				    }
				}
				""";

		Files.writeString(testFile, cleanCode);

		try {
			CodePolicyViolationDetector detector = new CodePolicyViolationDetector(tempDir);
			detector.scan();

			Assert.assertFalse("Detector should not flag clean Producer pattern code",
					detector.hasViolations());

			log("Detector correctly allowed clean code.");

		} finally {
			Files.deleteIfExists(testFile);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that the detector catches Features interfaces with abstract methods.
	 */
	@Test
	public void testDetectorCatchesFeaturesWithAbstractMethods() throws IOException {
		Path tempDir = Files.createTempDirectory("policy-test-features");
		Path testFile = tempDir.resolve("BadFeatures.java");

		String violatingCode = """
				package test;
				public interface BadFeatures {
				    // This abstract method violates the Features convention
				    String getSomething();

				    // This default method is fine
				    default void doSomething() {
				        System.out.println("OK");
				    }
				}
				""";

		Files.writeString(testFile, violatingCode);

		try {
			CodePolicyViolationDetector detector = new CodePolicyViolationDetector(tempDir);
			detector.scan();

			Assert.assertTrue("Detector should find Features interface with abstract methods",
					detector.hasViolations());

			boolean foundFeaturesViolation = detector.getViolations().stream()
					.anyMatch(v -> v.getRule().equals("FEATURES_INTERFACE_ABSTRACT_METHOD"));
			Assert.assertTrue("Should specifically detect FEATURES_INTERFACE_ABSTRACT_METHOD",
					foundFeaturesViolation);

			log("Detector correctly identified Features interface with abstract methods.");

		} finally {
			Files.deleteIfExists(testFile);
			Files.deleteIfExists(tempDir);
		}
	}

	/**
	 * Verifies that Features interfaces with only default methods are allowed.
	 */
	@Test
	public void testDetectorAllowsFeaturesWithOnlyDefaultMethods() throws IOException {
		Path tempDir = Files.createTempDirectory("policy-test-features-clean");
		Path testFile = tempDir.resolve("GoodFeatures.java");

		String cleanCode = """
				package test;
				public interface GoodFeatures {
				    // All methods are default - this is correct
				    default void doSomething() {
				        System.out.println("OK");
				    }

				    default String computeValue(int x) {
				        return String.valueOf(x * 2);
				    }

				    // Static methods are also allowed
				    static GoodFeatures create() {
				        return new GoodFeatures() {};
				    }
				}
				""";

		Files.writeString(testFile, cleanCode);

		try {
			CodePolicyViolationDetector detector = new CodePolicyViolationDetector(tempDir);
			detector.scan();

			Assert.assertFalse("Detector should not flag Features with only default methods",
					detector.hasViolations());

			log("Detector correctly allowed Features interface with only default methods.");

		} finally {
			Files.deleteIfExists(testFile);
			Files.deleteIfExists(tempDir);
		}
	}
}
