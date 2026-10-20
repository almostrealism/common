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

import org.almostrealism.util.TestTimeoutEnforcementScanner.Violation;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Pins the detection contract of {@link TestTimeoutEnforcementScanner}: a
 * {@code @Test} annotation with no {@code timeout} parameter is a violation
 * (including {@code @Test} annotations that carry other attributes and
 * annotations whose parameter list spans multiple lines), while a
 * {@code @Test(timeout = ...)} is accepted.
 */
public class TestTimeoutEnforcementScannerTest extends PolicyDetectorTestBase {

	/** A bare {@code @Test} with no parameters is flagged and its method named. */
	@Test(timeout = 10000)
	public void detectsBareTestAnnotation() throws IOException {
		Path file = writeSourceAt("src/test/java/SampleTest.java",
				"public class SampleTest {\n" +
				"\t@Test\n" +
				"\tpublic void checksBehavior() {\n" +
				"\t}\n" +
				"}\n");
		TestTimeoutEnforcementScanner scanner = new TestTimeoutEnforcementScanner(file.getParent());
		scanner.scan();

		Assert.assertEquals(1, scanner.getViolations().size());
		Violation v = scanner.getViolations().get(0);
		Assert.assertEquals(2, v.getLineNumber());
		Assert.assertEquals("checksBehavior", v.getMethodName());
	}

	/** A {@code @Test(timeout = ...)} annotation is accepted. */
	@Test(timeout = 10000)
	public void acceptsTestWithTimeout() throws IOException {
		Path file = writeSourceAt("src/test/java/SampleTest.java",
				"public class SampleTest {\n" +
				"\t@Test(timeout = 5000)\n" +
				"\tpublic void checksBehavior() {\n" +
				"\t}\n" +
				"}\n");
		TestTimeoutEnforcementScanner scanner = new TestTimeoutEnforcementScanner(file.getParent());
		scanner.scan();

		Assert.assertFalse(scanner.hasViolations());
		Assert.assertTrue(scanner.generateReport().contains("All @Test annotations include a timeout"));
	}

	/** A {@code @Test} carrying only a non-timeout attribute is still flagged. */
	@Test(timeout = 10000)
	public void detectsTestWithOtherAttributeButNoTimeout() throws IOException {
		Path file = writeSourceAt("src/test/java/SampleTest.java",
				"public class SampleTest {\n" +
				"\t@Test(expected = RuntimeException.class)\n" +
				"\tpublic void checksBehavior() {\n" +
				"\t}\n" +
				"}\n");
		TestTimeoutEnforcementScanner scanner = new TestTimeoutEnforcementScanner(file.getParent());
		scanner.scan();

		Assert.assertEquals(1, scanner.getViolations().size());
	}

	/** A {@code @Test} whose parameter list spans multiple lines is inspected whole. */
	@Test(timeout = 10000)
	public void detectsMultiLineAnnotationWithoutTimeout() throws IOException {
		Path file = writeSourceAt("src/test/java/SampleTest.java",
				"public class SampleTest {\n" +
				"\t@Test(\n" +
				"\t\texpected = RuntimeException.class)\n" +
				"\tpublic void checksBehavior() {\n" +
				"\t}\n" +
				"}\n");
		TestTimeoutEnforcementScanner scanner = new TestTimeoutEnforcementScanner(file.getParent());
		scanner.scan();

		Assert.assertEquals(1, scanner.getViolations().size());
		Assert.assertTrue(scanner.generateReport().contains("missing a timeout parameter"));
	}

	/** A production source file (not under src/test) is never scanned. */
	@Test(timeout = 10000)
	public void ignoresNonTestSources() throws IOException {
		Path file = writeSourceAt("src/main/java/Sample.java",
				"public class Sample {\n" +
				"\t@Test\n" +
				"\tpublic void checksBehavior() {\n" +
				"\t}\n" +
				"}\n");
		TestTimeoutEnforcementScanner scanner = new TestTimeoutEnforcementScanner(file.getParent());
		scanner.scan();

		Assert.assertFalse(scanner.hasViolations());
	}
}
