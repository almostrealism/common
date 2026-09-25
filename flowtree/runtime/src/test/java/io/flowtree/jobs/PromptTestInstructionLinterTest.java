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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PromptTestInstructionLinter}: the controller-side check that closes the
 * direct {@code /api/submit} bypass of {@code ar-manager}'s
 * {@code lint_prompt_for_broad_test_instructions}, mirroring
 * tools/mcp/manager/test_execution_limits.py's coverage of the Python-side linter.
 */
public class PromptTestInstructionLinterTest extends TestSuiteBase {

	/** Runs the linter on {@code prompt} and returns the violations found. */
	private List<String> violationsFor(String prompt) {
		return new PromptTestInstructionLinter(prompt).lint().getViolations();
	}

	/** An empty prompt has no violations. */
	@Test(timeout = 10000)
	public void emptyPromptHasNoViolations() {
		assertTrue(violationsFor("").isEmpty());
	}

	/** A null prompt has no violations. */
	@Test(timeout = 10000)
	public void nullPromptHasNoViolations() {
		assertTrue(violationsFor(null).isEmpty());
	}

	/** A prompt naming a specific failing test is accepted. */
	@Test(timeout = 10000)
	public void promptNamingSpecificTestAccepted() {
		assertTrue(violationsFor("Fix FooTest#testBar, which is failing on this branch.").isEmpty());
	}

	/** "run the full test suite" is a forbidden phrase. */
	@Test(timeout = 10000)
	public void fullTestSuitePhraseRejected() {
		assertFalse(violationsFor("Please run the full test suite before merging.").isEmpty());
	}

	/** "run all tests" is a forbidden phrase. */
	@Test(timeout = 10000)
	public void runAllTestsPhraseRejected() {
		assertFalse(violationsFor("Once done, run all tests to confirm.").isEmpty());
	}

	/** "run the engine/utils module tests" is a forbidden phrase. */
	@Test(timeout = 10000)
	public void moduleTestsPhraseRejected() {
		assertFalse(violationsFor("Run the engine/utils module tests after your change.").isEmpty());
	}

	/** "running the engine/utils shard" is a forbidden phrase. */
	@Test(timeout = 10000)
	public void shardPhraseRejected() {
		assertFalse(violationsFor("Try running the engine/utils shard to be sure.").isEmpty());
	}

	/** An AR_TEST_GROUP reference is forbidden. */
	@Test(timeout = 10000)
	public void arTestGroupReferenceRejected() {
		assertFalse(violationsFor("Set AR_TEST_GROUP=2 and confirm it passes.").isEmpty());
	}

	/** "mvn test" with no Class#method selector is forbidden. */
	@Test(timeout = 10000)
	public void mvnTestWithoutSelectorRejected() {
		assertFalse(violationsFor("Run mvn test -pl engine/utils to check your fix.").isEmpty());
	}

	/** The old matcher required the test phase immediately after the launcher, so tokens like a
	 * module flag in between let a broad command slip past undetected. */
	@Test(timeout = 10000)
	public void mvnTestWithModuleFlagBetweenLauncherAndPhaseRejected() {
		assertFalse(violationsFor("Run mvn -pl engine/utils test to check your fix.").isEmpty());
	}

	/** Same adjacency gap for a preceding lifecycle goal: "mvn clean test" is exactly as broad
	 * as "mvn test" even though "test" does not immediately follow "mvn". */
	@Test(timeout = 10000)
	public void mvnCleanTestWithGoalBetweenLauncherAndPhaseRejected() {
		assertFalse(violationsFor("Run mvn clean test to check your fix.").isEmpty());
	}

	/** "mvn test -Dtest=Class#method" is accepted. */
	@Test(timeout = 10000)
	public void mvnTestWithSelectorAccepted() {
		assertTrue(violationsFor("Run mvn test -Dtest=FooTest#testBar -pl engine/utils.").isEmpty());
	}

	/** "mvn test -DskipTests" is accepted -- a build, not a test run. */
	@Test(timeout = 10000)
	public void mvnTestWithSkipTestsAccepted() {
		assertTrue(violationsFor("Run mvn test -DskipTests -pl engine/utils to confirm it builds.").isEmpty());
	}

	/** A later "-DskipTests=false" overriding an earlier "-DskipTests=true" mention in the same
	 * fragment must not be exempted: Maven's last-value-wins semantics mean tests still run. */
	@Test(timeout = 10000)
	public void laterSkipTestsFalseOverridesEarlierTrueMentionRejected() {
		assertFalse(violationsFor(
				"Run mvn verify -DskipTests=true -DskipTests=false to confirm.").isEmpty());
	}

	/** A later, broader "-Dtest=" occurrence overriding an earlier narrow one must still be
	 * flagged, since Maven uses the later property value. */
	@Test(timeout = 10000)
	public void laterBroaderDtestOverrideRejected() {
		assertFalse(violationsFor(
				"Run mvn test -Dtest=Foo#bar -Dtest=WholeClass to confirm.").isEmpty());
	}

	/** A "-Dtest=Class#method1+method2" mention using Surefire's "+" method-list separator runs
	 * both methods in one invocation, so it must be flagged like the comma form. */
	@Test(timeout = 10000)
	public void surefirePlusMethodSeparatorDtestMentionRejected() {
		assertFalse(violationsFor(
				"Run mvn test -Dtest=FooTest#bar+qux to confirm.").isEmpty());
	}

	/** "python3 -m unittest discover" is forbidden. */
	@Test(timeout = 10000)
	public void unittestDiscoverPhraseRejected() {
		assertFalse(violationsFor("Run python3 -m unittest discover in that directory.").isEmpty());
	}

	/** "python3 -m unittest tests.test_foo.FooTest.test_bar" is accepted. */
	@Test(timeout = 10000)
	public void unittestSingleMethodPhraseAccepted() {
		assertTrue(violationsFor("Run python3 -m unittest tests.test_foo.FooTest.test_bar.").isEmpty());
	}

	/** A prompt naming TWO dotted test ids for the same unittest invocation still runs both
	 * tests together, even though each individually names a single dotted id -- a bare "a dotted
	 * id is present somewhere" check would miss this. */
	@Test(timeout = 10000)
	public void unittestTwoDottedIdsPhraseRejected() {
		assertFalse(violationsFor(
				"Run python3 -m unittest tests.test_foo.FooTest.test_bar "
						+ "tests.test_baz.BazTest.test_qux.").isEmpty());
	}

	/** An interpreter option before {@code -m} (here {@code -O}) breaks the literal
	 * {@code python3 -m} sequence; the matcher must still recognize the unittest discover run,
	 * matching the pytest matcher and the command-side {@code _index_of_module_flag}. */
	@Test(timeout = 10000)
	public void unittestDiscoverWithInterpreterOptionPhraseRejected() {
		assertFalse(violationsFor(
				"Run python3 -O -m unittest discover in that directory.").isEmpty());
	}

	/** A pytest instruction naming a directory, a whole file, no target, or two node ids is broad. */
	@Test(timeout = 10000)
	public void pytestWithoutSingleNodeIdRejected() {
		assertFalse(violationsFor("Run pytest tools/mcp/manager").isEmpty());
		assertFalse(violationsFor("run python3 -m pytest tools/mcp/manager/test_server.py").isEmpty());
		assertFalse(violationsFor("Run pytest").isEmpty());
		assertFalse(violationsFor("pytest -q a.py::test_one b.py::test_two").isEmpty());
	}

	/** A pytest instruction naming one node id, or prose merely naming pytest, is accepted. */
	@Test(timeout = 10000)
	public void pytestSingleNodeIdOrProseAccepted() {
		assertTrue(violationsFor(
				"Then run python -m pytest tools/mcp/manager/test_server.py::TestFoo::test_bar").isEmpty());
		assertTrue(violationsFor(
				"Verify with `pytest test_secrets.py::test_render` before finishing.").isEmpty());
		assertTrue(violationsFor(
				"Add a pytest regression test and use explicit pytest node ids.").isEmpty());
	}

	/** A bare positional (a directory or whole file) next to a node id still runs more than one
	 * test, so it must be flagged even though exactly one token contains "::". */
	@Test(timeout = 10000)
	public void pytestBarePositionalAlongsideNodeIdRejected() {
		assertFalse(violationsFor("Run pytest tests/ test_foo.py::test_bar").isEmpty());
		assertFalse(violationsFor(
				"run python3 -m pytest test_foo.py test_bar.py::test_qux").isEmpty());
	}

	/** Prose following a single node id must not be mistaken for extra pytest arguments. */
	@Test(timeout = 10000)
	public void pytestNodeIdFollowedByProseAccepted() {
		assertTrue(violationsFor(
				"Run pytest test_foo.py::test_bar to verify the fix before finishing.").isEmpty());
	}

	/** A prompt whose last -DskipTests value is a command substitution the shell expands to
	 * false must not be exempted by an earlier literal -DskipTests=true mention. */
	@Test(timeout = 10000)
	public void dynamicSkipValueInPromptDoesNotExemptBroadMaven() {
		assertFalse(violationsFor(
				"Run mvn verify -DskipTests=true -DskipTests=$(printf false) to finish.").isEmpty());
	}

	/** A -DskipTests value built from a parameter expansion is unknown, so the prompt must fail
	 * closed rather than read the earlier literal true as the effective skip value. */
	@Test(timeout = 10000)
	public void parameterExpansionSkipValueInPromptDoesNotExemptBroadMaven() {
		assertFalse(violationsFor(
				"Run mvn verify -DskipTests=true -DskipTests=$SKIP to finish.").isEmpty());
	}

	/** A plain literal -DskipTests build instruction (even with trailing sentence punctuation)
	 * is still accepted -- the dynamic-value rule must not regress the ordinary build case. */
	@Test(timeout = 10000)
	public void literalSkipTrueBuildInstructionStillAccepted() {
		assertTrue(violationsFor("Run mvn verify -DskipTests=true.").isEmpty());
		assertTrue(violationsFor("Run mvn install -DskipTests before finishing.").isEmpty());
	}

	/** The rejection message states there is no bypass and includes the line's snippet. */
	@Test(timeout = 10000)
	public void formatRejectionListsEachViolation() {
		PromptTestInstructionLinter linter = new PromptTestInstructionLinter(
				"Please run the full test suite before merging.").lint();
		String message = linter.formatRejection();
		assertTrue(message.contains("no bypass"));
		assertTrue(message.contains("full test suite"));
	}
}
