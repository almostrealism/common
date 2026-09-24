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

	/** The Maven Wrapper launcher (invoked as "./mvnw") with an explicit selector is accepted,
	 * the same as a plain "mvn" invocation. */
	@Test(timeout = 10000)
	public void mvnwLauncherWithSelectorAccepted() {
		assertTrue(violationsFor(
				"./mvnw -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo").isEmpty());
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

	/** pytest naming more than one node id still runs both tests in a single invocation,
	 * contradicting the "one test per invocation" rule -- even though every individual
	 * positional is itself an explicit node id. */
	@Test(timeout = 10000)
	public void pytestMultipleNodeIdsRejected() {
		List<String> violations = violationsFor(
				"pytest test_foo.py::test_bar test_baz.py::test_qux");
		assertFalse("multiple pytest node ids in one invocation must still be rejected",
				violations.isEmpty());
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

	/** The Maven Wrapper launcher must be rejected the same as a plain "mvn" invocation when it
	 * runs a test-executing phase with no selector -- without recognizing "mvnw" as a Maven
	 * launcher, "./mvnw test" would be waved through as an unrecognized custom command. */
	@Test(timeout = 10000)
	public void mvnwLauncherWithNoSelectorRejected() {
		List<String> violations = violationsFor("./mvnw test -pl engine/utils");
		assertFalse("./mvnw test must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** The Windows Maven Wrapper batch launcher is "mvnw.cmd", not "mvn.cmd" -- without
	 * recognizing it explicitly, "./mvnw.cmd test" would see a base name of "mvnw.cmd"
	 * (baseName() strips leading path components only, never file extensions) and be
	 * waved through as an unrelated custom command. */
	@Test(timeout = 10000)
	public void mvnwCmdLauncherWithNoSelectorRejected() {
		List<String> violations = violationsFor("./mvnw.cmd test -pl engine/utils");
		assertFalse("./mvnw.cmd test must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** A -Dtest value naming more than one Class#method entry still runs multiple tests in a
	 * single Maven invocation, contradicting the "one test per invocation" rule -- even though
	 * every individual entry is itself narrow. */
	@Test(timeout = 10000)
	public void multipleMethodDtestSelectorRejected() {
		List<String> violations = violationsFor(
				"mvn -pl engine/utils test -Dtest=FooTest#bar,FooTest#baz");
		assertFalse("a -Dtest value naming multiple methods must still be rejected",
				violations.isEmpty());
	}

	/** AR_TEST_GROUP must be rejected even on a phase not itself named "test". */
	@Test(timeout = 10000)
	public void arTestGroupAloneRejectedEvenWithoutTestPhaseKeyword() {
		List<String> violations = violationsFor("mvn package -DAR_TEST_GROUP=1 -DAR_TEST_GROUPS=4");
		assertFalse(violations.isEmpty());
		assertTrue(String.join(" ", violations).contains("AR_TEST_GROUP"));
	}

	/** The real shard invocation shape glues "AR_TEST_GROUP" directly to the "-D" property
	 * prefix with no boundary between "D" and "A" (both word characters), as documented in
	 * TestDepthRule's own javadoc ("mvn test -DAR_TEST_GROUP=0 -DAR_TEST_GROUPS=4"). A regex
	 * requiring a LEADING word boundary would never match this. Uses a phase outside
	 * TEST_RUNNING_PHASES and no -Dtest value, so the AR_TEST_GROUP check is the only
	 * possible source of a violation -- not a message that happens to embed the raw command
	 * text, which is how the sibling tests above kept passing despite that bug. */
	@Test(timeout = 10000)
	public void arTestGroupGluedToDashDPrefixIsDetectedOnItsOwn() {
		List<String> violations = violationsFor("mvn compile -DAR_TEST_GROUP=2");
		assertFalse("a bare -DAR_TEST_GROUP=2 with no other broad-run signal must still be caught",
				violations.isEmpty());
		assertTrue(violations.get(0).contains("AR_TEST_GROUP"));
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

	/** A mix of one node id and one bare directory/file must be rejected: the bare
	 * positional still runs a whole file or directory, even though the other
	 * positional is narrow. */
	@Test(timeout = 10000)
	public void pytestMixedDirectoryAndNodeIdRejected() {
		List<String> violations = violationsFor(
				"pytest tests/ test_foo.py::test_bar");
		assertFalse("a bare directory alongside a node id must still be rejected",
				violations.isEmpty());
		assertTrue(violations.get(0).contains("node id"));
	}

	/** -DskipTests=false explicitly re-enables tests and must not be treated as a skip flag. */
	@Test(timeout = 10000)
	public void skipTestsEqualsFalseIsNotTreatedAsSkip() {
		assertFalse(violationsFor("mvn install -DskipTests=false -pl engine/utils").isEmpty());
	}

	/** -Dmaven.test.skip=false explicitly re-enables tests and must not be treated as a skip flag. */
	@Test(timeout = 10000)
	public void mavenTestSkipEqualsFalseIsNotTreatedAsSkip() {
		assertFalse(violationsFor("mvn verify -Dmaven.test.skip=false").isEmpty());
	}

	/** When -DskipTests is followed by a later -DskipTests=false, Maven's last-value-wins
	 * semantics mean tests actually run -- an early-return on the first skip-shaped flag
	 * would wrongly treat this as build-only and miss the broad, selector-less test run. */
	@Test(timeout = 10000)
	public void laterSkipTestsFalseOverridesEarlierSkipTestsFlag() {
		List<String> violations = violationsFor("mvn test -DskipTests -DskipTests=false -pl engine/utils");
		assertFalse("a later -DskipTests=false must override an earlier bare -DskipTests",
				violations.isEmpty());
	}

	/** Same conflicting-property scenario for maven.test.skip. */
	@Test(timeout = 10000)
	public void laterMavenTestSkipFalseOverridesEarlierMavenTestSkipFlag() {
		List<String> violations = violationsFor(
				"mvn test -Dmaven.test.skip=true -Dmaven.test.skip=false -pl engine/utils");
		assertFalse("a later -Dmaven.test.skip=false must override an earlier true value",
				violations.isEmpty());
	}

	/** A later -DskipTests (bare, meaning true) overriding an earlier "=false" must still
	 * be treated as build-only -- last value wins in both directions. */
	@Test(timeout = 10000)
	public void laterSkipTestsTrueOverridesEarlierSkipTestsFalse() {
		assertTrue(violationsFor("mvn install -DskipTests=false -DskipTests -pl engine/utils").isEmpty());
	}

	/** A broad "mvn test" wrapped in "env" must not bypass detection just because
	 * the first token isn't literally "mvn". */
	@Test(timeout = 10000)
	public void envWrappedMavenTestRejected() {
		List<String> violations = violationsFor("env mvn test -pl engine/utils");
		assertFalse("env mvn test must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** "env" with a VAR=value assignment ahead of the wrapped command must still
	 * be unwrapped so the wrapped Maven invocation is checked. */
	@Test(timeout = 10000)
	public void envWithAssignmentWrappedMavenTestRejected() {
		List<String> violations = violationsFor("env FOO=bar mvn test -pl engine/utils");
		assertFalse(violations.isEmpty());
	}

	/** A broad "mvn test" wrapped in "sh -c '...'" must not bypass detection --
	 * the controller sees only the shell invocation unless it recurses into
	 * the inline script. */
	@Test(timeout = 10000)
	public void shDashCWrappedMavenTestRejected() {
		List<String> violations = violationsFor("sh -c 'mvn test -pl engine/utils'");
		assertFalse("sh -c 'mvn test' must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** "bash -c" with an explicit selector inside the script is still accepted. */
	@Test(timeout = 10000)
	public void bashDashCWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"bash -c 'mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo'").isEmpty());
	}

	/** "bash -ec" combines "-e" (errexit) and "-c" (inline script) in one token -- only
	 * recognizing a literal "-c" token would let this form's embedded broad Maven run
	 * slip through unchecked. */
	@Test(timeout = 10000)
	public void bashDashEcCombinedOptionWrappedMavenTestRejected() {
		List<String> violations = violationsFor("bash -ec 'mvn test -pl engine/utils'");
		assertFalse("bash -ec 'mvn test' must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** "bash -e -c" (separated short options) must be recognized the same as a combined
	 * "-ec" or a bare "-c". */
	@Test(timeout = 10000)
	public void bashDashEDashCSeparatedOptionWrappedMavenTestRejected() {
		List<String> violations = violationsFor("bash -e -c 'mvn test -pl engine/utils'");
		assertFalse("bash -e -c 'mvn test' must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** "bash -ec" with an explicit selector inside the script is still accepted. */
	@Test(timeout = 10000)
	public void bashDashEcCombinedOptionWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"bash -ec 'mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo'").isEmpty());
	}

	/** A broad pytest run wrapped in "sh -c" must also be rejected. */
	@Test(timeout = 10000)
	public void shDashCWrappedPytestOnDirectoryRejected() {
		assertFalse(violationsFor("sh -c 'pytest tools/mcp/manager'").isEmpty());
	}

	/** "command" is a shell builtin that runs its argument as a normal command,
	 * bypassing a shell function/alias of the same name -- it must not hide
	 * the wrapped mvn invocation either. */
	@Test(timeout = 10000)
	public void commandWrappedMavenTestRejected() {
		List<String> violations = violationsFor("command mvn test -pl engine/utils");
		assertFalse("command mvn test must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** "command mvn test" with an explicit selector inside is still accepted. */
	@Test(timeout = 10000)
	public void commandWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"command mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo").isEmpty());
	}

	/** "sudo" must not hide the wrapped mvn invocation either. */
	@Test(timeout = 10000)
	public void sudoWrappedMavenTestRejected() {
		assertFalse(violationsFor("sudo mvn test -pl engine/utils").isEmpty());
	}

	/** Prefix wrappers chain: sudo wraps env, env wraps the real command. */
	@Test(timeout = 10000)
	public void sudoEnvWrappedMavenTestRejected() {
		List<String> violations = violationsFor("sudo env FOO=bar mvn test -pl engine/utils");
		assertFalse(violations.isEmpty());
	}

	/** "nice -n 10" previously stripped only "nice", leaving "-n" as the apparent
	 * command -- the wrapped mvn invocation must still be reached and rejected. */
	@Test(timeout = 10000)
	public void niceWithOperandOptionWrappedMavenTestRejected() {
		List<String> violations = violationsFor("nice -n 10 mvn test -pl engine/utils");
		assertFalse("nice -n 10 mvn test must be rejected like a direct mvn test",
				violations.isEmpty());
	}

	/** "nice -n 10" wrapping a Maven command with an explicit selector is still accepted. */
	@Test(timeout = 10000)
	public void niceWithOperandOptionWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"nice -n 10 mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo")
				.isEmpty());
	}

	/** "sudo -u user" previously stripped only "sudo", leaving "-u" as the apparent
	 * command -- the wrapped mvn invocation must still be reached and rejected. */
	@Test(timeout = 10000)
	public void sudoWithOperandOptionWrappedMavenTestRejected() {
		List<String> violations = violationsFor("sudo -u user mvn test -pl engine/utils");
		assertFalse("sudo -u user mvn test must be rejected like a direct mvn test",
				violations.isEmpty());
	}

	/** "sudo -u user" wrapping a Maven command with an explicit selector is still accepted. */
	@Test(timeout = 10000)
	public void sudoWithOperandOptionWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"sudo -u user mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo")
				.isEmpty());
	}

	/** A bare "VAR=value" prefix with no "env" token is exactly as valid to the
	 * shell as one preceded by "env" -- it must be stripped the same way, not
	 * waved through because the first token isn't literally "mvn"/"env". */
	@Test(timeout = 10000)
	public void bareAssignmentWrappedMavenTestRejected() {
		List<String> violations = violationsFor("FOO=bar mvn test -pl engine/utils");
		assertFalse("a bare VAR=value prefix must not bypass detection", violations.isEmpty());
	}

	/** Multiple bare assignments ahead of the real command must all be stripped. */
	@Test(timeout = 10000)
	public void multipleBareAssignmentsWrappedMavenTestRejected() {
		List<String> violations = violationsFor("FOO=bar BAZ=qux mvn test -pl engine/utils");
		assertFalse(violations.isEmpty());
	}

	/** A bare assignment prefix with an explicit selector inside is still accepted. */
	@Test(timeout = 10000)
	public void bareAssignmentWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"FOO=bar mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo").isEmpty());
	}

	/** Prefix wrappers chain: sudo wraps a bare assignment, which wraps the real command. */
	@Test(timeout = 10000)
	public void sudoBareAssignmentWrappedMavenTestRejected() {
		List<String> violations = violationsFor("sudo FOO=bar mvn test -pl engine/utils");
		assertFalse(violations.isEmpty());
	}

	/** A multi-line command must have each line checked independently: the
	 * tokenizer treats "\n" purely as whitespace between tokens, never as an
	 * operator token, so without an explicit newline split the narrow
	 * selector on the first line would mask the second line's broad mvn. */
	@Test(timeout = 10000)
	public void newlineSeparatedBroadMvnAfterNarrowOneRejected() {
		List<String> violations = violationsFor(
				"mvn test -Dtest=Foo#bar\nmvn test -pl engine/utils");
		assertFalse("the second line's broad mvn test must still be flagged", violations.isEmpty());
	}

	/** Two independently-narrow commands on separate lines are both accepted. */
	@Test(timeout = 10000)
	public void newlineSeparatedNarrowCommandsAccepted() {
		assertTrue(violationsFor(
				"mvn test -Dtest=Foo#bar\npytest tests/test_foo.py::test_bar").isEmpty());
	}

	/** "env -u FOO" consumes "FOO" as its own operand -- without handling this, "FOO"
	 * would be mistaken for the wrapped command's own first token instead of "mvn". */
	@Test(timeout = 10000)
	public void envWithUnsetOptionOperandWrappedMavenTestRejected() {
		List<String> violations = violationsFor("env -u FOO mvn test -pl engine/utils");
		assertFalse("env -u FOO mvn test must still be rejected like a direct mvn test",
				violations.isEmpty());
	}

	/** "env -u FOO" wrapping a Maven command with an explicit selector is still accepted. */
	@Test(timeout = 10000)
	public void envWithUnsetOptionOperandWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"env -u FOO mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo").isEmpty());
	}

	/** "eval" re-parses its concatenated arguments as a new command line -- it must not
	 * hide the wrapped mvn invocation either. */
	@Test(timeout = 10000)
	public void evalWrappedMavenTestRejected() {
		List<String> violations = violationsFor("eval mvn test -pl engine/utils");
		assertFalse("eval mvn test must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** "eval" wrapping a Maven command with an explicit selector is still accepted. */
	@Test(timeout = 10000)
	public void evalWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"eval mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo").isEmpty());
	}

	/** A backtick command substitution executes even when the outer command's own first
	 * token (here "echo") is not itself Maven or pytest -- the shell still runs the
	 * embedded mvn command to produce echo's argument. */
	@Test(timeout = 10000)
	public void backtickCommandSubstitutionWrappedMavenTestRejected() {
		List<String> violations = violationsFor("echo `mvn test -pl engine/utils`");
		assertFalse("a backtick-wrapped mvn test must be rejected even though the outer "
				+ "command is \"echo\"", violations.isEmpty());
	}

	/** A $(...) command substitution must be detected the same way as a backtick one. */
	@Test(timeout = 10000)
	public void dollarParenCommandSubstitutionWrappedMavenTestRejected() {
		List<String> violations = violationsFor("echo $(mvn test -pl engine/utils)");
		assertFalse("a $(...)-wrapped mvn test must be rejected even though the outer "
				+ "command is \"echo\"", violations.isEmpty());
	}

	/** A backtick command substitution with an explicit selector inside is still accepted. */
	@Test(timeout = 10000)
	public void backtickCommandSubstitutionWithSelectorAccepted() {
		assertTrue(violationsFor(
				"echo `mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo`").isEmpty());
	}

	/** "env -S" word-splits its operand and executes the result as a brand-new command line --
	 * it must not hide the wrapped mvn invocation as if the operand were an ordinary argument. */
	@Test(timeout = 10000)
	public void envDashSSplitStringWrappedMavenTestRejected() {
		List<String> violations = violationsFor("env -S 'mvn test -pl engine/utils'");
		assertFalse("env -S 'mvn test' must be rejected like a direct mvn test", violations.isEmpty());
	}

	/** The glued "-S<script>" form must be recognized the same as the separated "-S <script>". */
	@Test(timeout = 10000)
	public void envDashSGluedSplitStringWrappedMavenTestRejected() {
		List<String> violations = violationsFor("env -S'mvn test -pl engine/utils'");
		assertFalse(violations.isEmpty());
	}

	/** The long-option "--split-string=<script>" form must be recognized too. */
	@Test(timeout = 10000)
	public void envDashDashSplitStringEqualsWrappedMavenTestRejected() {
		List<String> violations = violationsFor("env --split-string='mvn test -pl engine/utils'");
		assertFalse(violations.isEmpty());
	}

	/** "env -S" wrapping a Maven command with an explicit selector is still accepted. */
	@Test(timeout = 10000)
	public void envDashSSplitStringWithSelectorAccepted() {
		assertTrue(violationsFor(
				"env -S 'mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo'")
				.isEmpty());
	}

	/** "if COND; then mvn test; fi" splits on ";" into a segment beginning with "then" --
	 * the control word must be stripped so the wrapped mvn invocation is still reached. */
	@Test(timeout = 10000)
	public void ifThenWrappedMavenTestRejected() {
		List<String> violations = violationsFor("if true; then mvn test -pl engine/utils; fi");
		assertFalse("if/then must not hide a broad mvn test", violations.isEmpty());
	}

	/** "for i in 1 2 3; do mvn test; done" must have its "do"-prefixed body inspected. */
	@Test(timeout = 10000)
	public void forDoWrappedMavenTestRejected() {
		List<String> violations = violationsFor(
				"for i in 1 2 3; do mvn test -pl engine/utils; done");
		assertFalse("for/do must not hide a broad mvn test", violations.isEmpty());
	}

	/** A control-word-wrapped Maven command with an explicit selector is still accepted. */
	@Test(timeout = 10000)
	public void ifThenWrappedMavenTestWithSelectorAccepted() {
		assertTrue(violationsFor(
				"if true; then mvn -pl flowtree/runtime test -Dtest=NotifierRegistryTest#testFoo; fi")
				.isEmpty());
	}

	/** Surefire treats "*" as a wildcard: "FooTest#test*" can match and run several methods
	 * despite naming exactly one comma-separated entry with a "#" in it. */
	@Test(timeout = 10000)
	public void wildcardMethodDtestSelectorRejected() {
		List<String> violations = violationsFor("mvn -pl engine/utils test -Dtest=FooTest#test*");
		assertFalse("a wildcard method selector must be rejected", violations.isEmpty());
	}

	/** Surefire treats "*" in the class half as a wildcard too. */
	@Test(timeout = 10000)
	public void wildcardClassDtestSelectorRejected() {
		List<String> violations = violationsFor("mvn -pl engine/utils test -Dtest=Foo*#bar");
		assertFalse("a wildcard class selector must be rejected", violations.isEmpty());
	}

	/** Surefire also treats "?" as a single-character wildcard. */
	@Test(timeout = 10000)
	public void questionMarkWildcardDtestSelectorRejected() {
		List<String> violations = violationsFor("mvn -pl engine/utils test -Dtest=FooTest#test?");
		assertFalse(violations.isEmpty());
	}

	/** "python3 -m unittest discover" runs the whole test tree and must be rejected --
	 * this is the CI documentation's own example of a forbidden broad run. */
	@Test(timeout = 10000)
	public void unittestDiscoverRejected() {
		List<String> violations = violationsFor("python3 -m unittest discover");
		assertFalse("unittest discover must be rejected", violations.isEmpty());
	}

	/** A bare "python -m unittest" with no target discovers and runs everything. */
	@Test(timeout = 10000)
	public void unittestWithNoArgsRejected() {
		assertFalse(violationsFor("python -m unittest").isEmpty());
	}

	/** "python -m unittest package.module" (no class/method) still runs every test in it. */
	@Test(timeout = 10000)
	public void unittestBareModuleRejected() {
		assertFalse(violationsFor("python3 -m unittest tests.test_foo").isEmpty());
	}

	/** A fully-qualified "module.Class.method" test id is accepted. */
	@Test(timeout = 10000)
	public void unittestSingleMethodAccepted() {
		assertTrue(violationsFor("python3 -m unittest tests.test_foo.FooTest.test_bar").isEmpty());
	}

	/** A shell interpreter piped a constructed script from stdin cannot be validated as
	 * written, since the script text never appears in the command line this validator sees. */
	@Test(timeout = 10000)
	public void bareShWithNoScriptArgumentRejected() {
		List<String> violations = violationsFor("printf 'mvn test -pl engine/utils' | sh");
		assertFalse("a bare sh reading a script from stdin must be rejected", violations.isEmpty());
	}

	/** A shell interpreter invoked with an explicit script file is accepted -- this is the
	 * same documented, trusted custom-script use as {@link #customScriptAccepted()}. */
	@Test(timeout = 10000)
	public void shWithScriptFileArgumentAccepted() {
		assertTrue(violationsFor("bash scripts/verify-foo.sh").isEmpty());
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
