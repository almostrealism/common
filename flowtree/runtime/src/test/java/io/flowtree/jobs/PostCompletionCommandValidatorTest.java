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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PostCompletionCommandValidator}: the controller-side half
 * of the "no broad test runs" rule, mirroring
 * tools/mcp/manager/test_test_execution_limits.py's coverage of the
 * Python-side validator.
 */
public class PostCompletionCommandValidatorTest extends TestSuiteBase {

	/** Runs the validator on {@code command} and returns the violations found. */
	private List<String> violationsFor(String command) {
		return new PostCompletionCommandValidator(command).validate().getViolations();
	}

	// -- Accepted commands ----------------------------------------------------

	/** An empty command has no violations. */
	@Test(timeout = 10000)
	public void emptyCommandHasNoViolations() {
		assertTrue(violationsFor("").isEmpty());
	}

	/** A null command has no violations. */
	@Test(timeout = 10000)
	public void nullCommandHasNoViolations() {
		assertTrue(violationsFor(null).isEmpty());
	}

	/** An explicit Class#method Maven selector is accepted. */
	@Test(timeout = 10000)
	public void singleMethodMavenSelectorAccepted() {
		assertTrue(violationsFor(
				"mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo").isEmpty());
	}

	/** mvn install -DskipTests is a build, not a test run, and is accepted. */
	@Test(timeout = 10000)
	public void mavenInstallSkipTestsIsABuildNotATestRun() {
		assertTrue(violationsFor("mvn install -q -DskipTests -pl engine/utils -am").isEmpty());
	}

	/** A Maven invocation with no test-executing phase is accepted. */
	@Test(timeout = 10000)
	public void mavenCompileWithNoTestPhaseAccepted() {
		assertTrue(violationsFor("mvn clean compile").isEmpty());
	}

	/** A pytest invocation naming an explicit node id is accepted. */
	@Test(timeout = 10000)
	public void pytestSingleNodeIdAccepted() {
		assertTrue(violationsFor("cd tools/mcp/manager && pytest test_secrets.py::test_render").isEmpty());
	}

	/** A non-Maven, non-pytest command is accepted. */
	@Test(timeout = 10000)
	public void customScriptAccepted() {
		assertTrue(violationsFor("bash scripts/verify-foo.sh").isEmpty());
	}

	// -- Rejected commands ------------------------------------------------------

	/** The exact command from the 2026-09-16 incident described in the class javadoc must be rejected. */
	@Test(timeout = 10000)
	public void incidentCommandIsRejected() {
		String command = "mvn install -q -DskipTests -pl engine/utils -am && "
				+ "mvn test -pl engine/utils -DAR_TEST_GROUP=2 -DAR_TEST_GROUPS=8";
		List<String> violations = violationsFor(command);
		assertFalse("incident command must be rejected", violations.isEmpty());
		assertTrue(String.join(" ", violations).contains("AR_TEST_GROUP"));
	}

	/** mvn test with no -Dtest selector runs the whole module and must be rejected. */
	@Test(timeout = 10000)
	public void mvnTestWithNoSelectorRejected() {
		List<String> violations = violationsFor("mvn test -pl engine/utils");
		assertFalse(violations.isEmpty());
		assertTrue(violations.get(0).contains("no -Dtest selector"));
	}

	/** mvn verify also runs tests unless skipped, and must be rejected without a selector. */
	@Test(timeout = 10000)
	public void mvnVerifyWithNoSelectorRejected() {
		assertFalse(violationsFor("mvn verify").isEmpty());
	}

	/** mvn install without -DskipTests and without a selector must be rejected. */
	@Test(timeout = 10000)
	public void mvnInstallWithoutSkipAndNoSelectorRejected() {
		assertFalse(violationsFor("mvn install -pl engine/utils").isEmpty());
	}

	/** A bare -Dtest=Class selector (no #method) still runs the whole class and must be rejected. */
	@Test(timeout = 10000)
	public void bareClassDtestSelectorRejected() {
		List<String> violations = violationsFor(
				"mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest");
		assertFalse(violations.isEmpty());
		assertTrue(violations.get(0).contains("Class#method"));
	}

	/** AR_TEST_GROUP must be rejected even on a phase not itself named "test". */
	@Test(timeout = 10000)
	public void arTestGroupAloneRejectedEvenWithoutTestPhaseKeyword() {
		List<String> violations = violationsFor("mvn package -DAR_TEST_GROUP=1 -DAR_TEST_GROUPS=4");
		assertFalse(violations.isEmpty());
		assertTrue(String.join(" ", violations).contains("AR_TEST_GROUP"));
	}

	/** pytest against a directory (no node id) must be rejected. */
	@Test(timeout = 10000)
	public void pytestOnDirectoryRejected() {
		List<String> violations = violationsFor("pytest tools/mcp/manager");
		assertFalse(violations.isEmpty());
		assertTrue(violations.get(0).contains("node id"));
	}

	/** pytest against a whole file (no node id) must be rejected. */
	@Test(timeout = 10000)
	public void pytestOnWholeFileRejected() {
		assertFalse(violationsFor("pytest tools/mcp/manager/test_server.py").isEmpty());
	}

	/** pytest with no arguments discovers and runs everything, and must be rejected. */
	@Test(timeout = 10000)
	public void pytestWithNoArgsRejected() {
		assertFalse(violationsFor("pytest").isEmpty());
	}

	// -- Timeout ceiling --------------------------------------------------------

	/** Pins the timeout ceiling so a silent regression is caught immediately. */
	@Test(timeout = 10000)
	public void maxTimeoutSecondsIs2400() {
		assertEquals(2400, PostCompletionCommandValidator.MAX_TIMEOUT_SECONDS);
	}

	// -- Rejection message ------------------------------------------------------

	/** The rejection message states there is no bypass and lists each violation. */
	@Test(timeout = 10000)
	public void formatRejectionListsEachViolation() {
		PostCompletionCommandValidator validator = new PostCompletionCommandValidator(
				"mvn test -pl engine/utils -DAR_TEST_GROUP=2").validate();
		String message = validator.formatRejection();
		assertTrue(message.contains("no bypass"));
		assertTrue(message.contains("AR_TEST_GROUP"));
	}
}
