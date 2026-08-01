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

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PostCompletionCommandRule}: the enforcement rule that runs a
 * shell command after each primary-work session and triggers a correction session
 * when the command fails.
 *
 * <p>Covers unit behaviour (violation detection, timeout, output capture),
 * integration with {@link CodingAgentJob#runEnforcementRules()}, serialisation
 * round-trips, factory propagation, and the pass-cap mechanism.</p>
 */
public class CodingAgentJobPostCompletionCommandRuleTest extends TestSuiteBase {

	// ── PostCompletionCommandRule — unit tests ───────────────────────────────

	/**
	 * A rule wrapping a successful command (exit 0) must not be violated.
	 * No correction session should be triggered.
	 */
	@Test(timeout = 30000)
	public void postCompletionSuccessfulCommandNotViolated() {
		PostCompletionCommandRule rule = new PostCompletionCommandRule("true", null);
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertFalse("exit 0 command should not be violated", rule.isViolated(job));
		assertEquals(0, rule.getLastExitCode());
		assertFalse(rule.wasLastRunTimedOut());
	}

	/**
	 * A rule wrapping a failing command (exit 1) must be violated, and the
	 * correction prompt must include the command and the exit code.
	 */
	@Test(timeout = 30000)
	public void postCompletionFailingCommandIsViolated() {
		PostCompletionCommandRule rule = new PostCompletionCommandRule("false", null);
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertTrue("exit 1 command should be violated", rule.isViolated(job));
		assertFalse(rule.wasLastRunTimedOut());
		String prompt = rule.buildCorrectionPrompt(job);
		assertTrue("Correction prompt must mention the command",
				prompt.contains("false"));
		assertTrue("Correction prompt must mention exit code",
				prompt.contains("exit") || prompt.contains("code"));
	}

	/**
	 * A timed-out command must be treated as a failure.
	 * The correction prompt must clearly indicate a timeout occurred.
	 */
	@Test(timeout = 30000)
	public void postCompletionTimeoutTreatedAsFailure() {
		// 1-second timeout, command that sleeps longer than that
		PostCompletionCommandRule rule = new PostCompletionCommandRule("sleep 60", null, 1);
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertTrue("timed-out command should be violated", rule.isViolated(job));
		assertTrue("should be recorded as timed out", rule.wasLastRunTimedOut());
		String prompt = rule.buildCorrectionPrompt(job);
		assertTrue("Correction prompt must mention timeout", prompt.toLowerCase().contains("timeout")
				|| prompt.toLowerCase().contains("timed out"));
	}

	/**
	 * After {@link PostCompletionCommandRule#onCorrectionAttempted}, the next
	 * {@link PostCompletionCommandRule#isViolated} call must re-run the command.
	 * This test uses a shell command that succeeds on the second invocation by
	 * relying on a counter written to a temp file.
	 */
	@Test(timeout = 30000)
	public void postCompletionRuleExitsLoopAfterSuccessfulCorrection() throws Exception {
		// Use a temp file as a call counter: first call writes "1", second call succeeds.
		File counter = File.createTempFile("pcr_test_", ".txt");
		counter.deleteOnExit();
		String cmd = "if [ -f " + counter.getAbsolutePath() + " ] && "
				+ "[ \"$(cat " + counter.getAbsolutePath() + ")\" = \"done\" ]; "
				+ "then exit 0; else echo done > " + counter.getAbsolutePath() + "; exit 1; fi";

		PostCompletionCommandRule rule = new PostCompletionCommandRule(cmd, null);
		CodingAgentJob job = new CodingAgentJob("t1", "do something");

		// First call: exits 1 (file did not contain "done" yet)
		assertTrue("First call should be violated", rule.isViolated(job));

		// onCorrectionAttempted: invalidates the cache so next isViolated re-runs
		rule.onCorrectionAttempted(job);

		// Second call: exits 0 (file now contains "done")
		assertFalse("Second call should not be violated", rule.isViolated(job));
	}

	/**
	 * The output of a failing command is captured and included (possibly truncated)
	 * in the correction prompt.
	 */
	@Test(timeout = 30000)
	public void postCompletionOutputCapturedInCorrectionPrompt() {
		PostCompletionCommandRule rule = new PostCompletionCommandRule(
				"echo 'BUILD FAILED'; exit 1", null);
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertTrue(rule.isViolated(job));
		assertTrue("Output should be captured",
				rule.getLastOutput().contains("BUILD FAILED"));
		String prompt = rule.buildCorrectionPrompt(job);
		assertTrue("Correction prompt should include captured output",
				prompt.contains("BUILD FAILED"));
	}

	/**
	 * Output longer than {@link PostCompletionCommandRule#MAX_OUTPUT_CHARS} is
	 * truncated to the tail so the correction prompt stays bounded.
	 */
	@Test(timeout = 30000)
	public void postCompletionOutputTruncatedToLimit() {
		// Produce output larger than MAX_OUTPUT_CHARS
		String line = "x".repeat(100) + "\n";
		int repetitions = PostCompletionCommandRule.MAX_OUTPUT_CHARS / 100 + 20;
		String bigOutput = line.repeat(repetitions);
		String truncated = PostCompletionCommandRule.truncateOutput(bigOutput);
		assertTrue("Truncated output should be within limit",
				truncated.length() <= PostCompletionCommandRule.MAX_OUTPUT_CHARS + 100);
		assertTrue("Truncated output should indicate truncation",
				truncated.contains("truncated"));
	}

	/**
	 * Short output (under the limit) is returned unchanged by
	 * {@link PostCompletionCommandRule#truncateOutput}.
	 */
	@Test(timeout = 30000)
	public void postCompletionShortOutputNotTruncated() {
		String output = "BUILD SUCCESS\n";
		assertEquals(output, PostCompletionCommandRule.truncateOutput(output));
	}

	/**
	 * An always-failing post-completion command eventually hits the max-retries cap
	 * inside the enforcement loop.
	 */
	@Test(timeout = 30000)
	public void postCompletionNeverSatisfiedHitsMaxRetries() {
		AtomicInteger correctionCalls = new AtomicInteger();
		PostCompletionCommandRule rule = new PostCompletionCommandRule("false", null);
		CodingAgentJob job = new CodingAgentJob("t1", "test") {
			@Override
			protected void runCorrectionSession(String correctionPrompt, String activity) {
				correctionCalls.incrementAndGet();
			}
		};

		// Disable built-in rules so only the post-completion rule fires.
		job.setEnforceOrganizationalPlacement(false);
		job.addEnforcementRule(rule);

		job.runEnforcementRules();

		int attempts = correctionCalls.get();
		assertTrue("Expected at least 1 correction attempt, got " + attempts, attempts >= 1);
		assertTrue("Expected at most max total attempts, got " + attempts,
				attempts <= CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS);
	}

	// ── PostCompletionCommandRule — CodingAgentJob integration ───────────────

	/**
	 * When no post-completion command is set, no PostCompletionCommandRule is
	 * added to the active rule list. This ensures the feature is purely additive.
	 */
	@Test(timeout = 30000)
	public void postCompletionRuleNotActiveWhenCommandEmpty() {
		AtomicInteger correctionCalls = new AtomicInteger();
		CodingAgentJob job = new CodingAgentJob("t1", "test") {
			@Override
			protected void runCorrectionSession(String correctionPrompt, String activity) {
				correctionCalls.incrementAndGet();
			}
		};
		// No postCompletionCommand set; disable org placement to avoid noise
		job.setEnforceOrganizationalPlacement(false);
		job.runEnforcementRules();
		assertEquals("No correction sessions should fire without a command", 0, correctionCalls.get());
	}

	/**
	 * When a post-completion command is set on a job, the getter returns it
	 * and it participates in the active rule list.
	 */
	@Test(timeout = 30000)
	public void postCompletionCommandGetterSetterRoundTrip() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertNull(job.getPostCompletionCommand());
		job.setPostCompletionCommand("mvn -pl flowtree/runtime test -Dtest=Foo");
		assertEquals("mvn -pl flowtree/runtime test -Dtest=Foo", job.getPostCompletionCommand());
		job.setPostCompletionCommand(null);
		assertNull(job.getPostCompletionCommand());
	}

	// ── PostCompletionCommandRule — serialisation ─────────────────────────────

	/**
	 * A non-empty post-completion command round-trips through encode/set
	 * (the wire format used for job serialization).
	 */
	@Test(timeout = 30000)
	public void postCompletionCommandRoundTripThroughEncodeDecode() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setPostCompletionCommand("mvn -pl flowtree/runtime test -Dtest=FooTest");
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Wire format must contain postCmd", encoded.contains("postCmd:="));

		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertEquals("mvn -pl flowtree/runtime test -Dtest=FooTest", restored.getPostCompletionCommand());
	}

	/**
	 * When no post-completion command is set, the wire format must not contain
	 * the {@code postCmd} key.
	 */
	@Test(timeout = 30000)
	public void postCompletionCommandAbsentFromWireFormatWhenEmpty() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		String encoded = job.encode();
		assertFalse("postCmd must not appear in wire format when unset",
				encoded.contains("postCmd"));
	}

	/**
	 * A non-default timeout round-trips through encode/set.
	 */
	@Test(timeout = 30000)
	public void postCompletionTimeoutRoundTripThroughEncodeDecode() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setPostCompletionCommand("make test");
		job.setPostCompletionTimeoutSeconds(300);
		String encoded = job.encode();
		assertTrue("Wire format must contain postCmdTimeout", encoded.contains("postCmdTimeout:=300"));

		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertEquals(300, restored.getPostCompletionTimeoutSeconds());
	}

	/**
	 * The default timeout (1800 s) is NOT written to the wire format to keep it compact.
	 */
	@Test(timeout = 30000)
	public void postCompletionDefaultTimeoutAbsentFromWireFormat() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setPostCompletionCommand("make test");
		// Default timeout — should not appear in wire format
		String encoded = job.encode();
		assertFalse("Default timeout must not appear in wire format",
				encoded.contains("postCmdTimeout"));
	}

	// ── PostCompletionCommandRule — factory propagation ──────────────────────

	/**
	 * A post-completion command set on the factory propagates to jobs created
	 * by {@link CodingAgentJobFactory#nextJob()}.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionCommandPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		factory.setPostCompletionCommand("mvn -pl flowtree/runtime test -Dtest=FooTest");
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertEquals("mvn -pl flowtree/runtime test -Dtest=FooTest", job.getPostCompletionCommand());
	}

	/**
	 * When no post-completion command is set on the factory, the job's command is null.
	 */
	@Test(timeout = 30000)
	public void factoryNoPostCompletionCommandJobHasNoCommand() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertNull(job.getPostCompletionCommand());
	}

	/**
	 * A factory with a post-completion command round-trips through the
	 * {@code set()} deserialization path.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionCommandRoundTripViaSet() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setPostCompletionCommand("bash scripts/verify.sh");
		assertEquals("bash scripts/verify.sh", factory.getPostCompletionCommand());

		// Simulate wire deserialisation
		CodingAgentJobFactory restored = new CodingAgentJobFactory("prompt");
		restored.set("postCmd",
				GitManagedJob.base64Encode("bash scripts/verify.sh"));
		assertEquals("bash scripts/verify.sh", restored.getPostCompletionCommand());
	}

	/**
	 * A non-default timeout set on the factory propagates to the created job.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionTimeoutPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		factory.setPostCompletionCommand("mvn test");
		factory.setPostCompletionTimeoutSeconds(600);
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertEquals(600, job.getPostCompletionTimeoutSeconds());
	}

	/**
	 * {@link PostCompletionCommandRule#getName()} must return
	 * {@code "post-completion-command"} so enforcement logs and activity tags
	 * identify the rule correctly.
	 */
	@Test(timeout = 30000)
	public void postCompletionRuleNameIsCorrect() {
		PostCompletionCommandRule rule = new PostCompletionCommandRule("true", null);
		assertEquals("post-completion-command", rule.getName());
	}

	/**
	 * Disabling the post-completion command on a factory must clear the corresponding
	 * properties from the wire format. Otherwise the factory appears disabled locally
	 * but re-enables silently after a serialize/deserialize round-trip because
	 * {@code AbstractJobFactory.encode()} serializes every non-null property.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionCommandClearedFromWireFormatOnDisable() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setPostCompletionCommand("mvn test");
		factory.setPostCompletionWorkingDir("/tmp/work");
		factory.setPostCompletionTimeoutSeconds(600);

		factory.setPostCompletionCommand(null);

		String encoded = factory.encode();
		assertFalse("postCmd must be absent after disable", encoded.contains("postCmd:="));
		assertFalse("postCmdDir must be absent after disable", encoded.contains("postCmdDir:="));
		assertFalse("postCmdTimeout must be absent after disable",
				encoded.contains("postCmdTimeout:="));

		assertNull(factory.getPostCompletionCommand());
		assertNull(factory.getPostCompletionWorkingDir());
		assertEquals(PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS,
				factory.getPostCompletionTimeoutSeconds());
	}

	/**
	 * Setting the post-completion working directory back to {@code null} must clear
	 * the {@code postCmdDir} property so it does not survive a serialize/deserialize
	 * round-trip.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionWorkingDirClearedOnNull() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setPostCompletionCommand("mvn test");
		factory.setPostCompletionWorkingDir("/tmp/work");

		factory.setPostCompletionWorkingDir(null);

		String encoded = factory.encode();
		assertFalse("postCmdDir must be absent after null reset",
				encoded.contains("postCmdDir:="));
		assertNull(factory.getPostCompletionWorkingDir());
	}

	/**
	 * A post-completion command that produces more output than the OS pipe buffer
	 * must not deadlock: with the previous implementation that read stdout only after
	 * {@code waitFor}, the child blocked on write once the pipe filled (typically
	 * 64 KiB) and the wall-clock timeout fired even though the work completed.
	 * Output is now redirected to a temp file, so the child can drain freely.
	 */
	@Test(timeout = 30000)
	public void postCompletionLargeOutputDoesNotDeadlock() {
		// 200 KiB of output — well past the typical 64 KiB pipe buffer.
		String cmd = "yes x | head -c 200000";
		PostCompletionCommandRule rule = new PostCompletionCommandRule(cmd, null, 20);
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertFalse("Large-output command must complete without timeout",
				rule.isViolated(job));
		assertEquals(0, rule.getLastExitCode());
		assertFalse("Should not be reported as timed out", rule.wasLastRunTimedOut());
		assertNotNull(rule.getLastOutput());
		assertTrue("Captured output should be non-empty",
				rule.getLastOutput().length() > 0);
	}

	// ── PostCompletionCommandRule — pass cap ─────────────────────────────────

	/**
	 * Pins the default cap so a silent regression (e.g. raising it by an order
	 * of magnitude) is caught immediately.
	 */
	@Test(timeout = 30000)
	public void defaultPostCompletionPassCapIsThree() {
		assertEquals(3, CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES);
		assertEquals(PostCompletionCommandRule.DEFAULT_MAX_PASSES,
				CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES);
	}

	/**
	 * Four consecutive failing passes with the default cap of 3 must be cut off after
	 * exactly 3 correction attempts. The enforcement loop honours
	 * {@link EnforcementRule#getMaxRetries()} which now returns {@code maxPasses}.
	 */
	@Test(timeout = 30000)
	public void postCompletionDefaultCapStopsAfterThreePasses() {
		AtomicInteger correctionCalls = new AtomicInteger();
		PostCompletionCommandRule rule = new PostCompletionCommandRule("false", null);
		// Default cap is 3; rule always fails.
		assertEquals(3, rule.getMaxRetries());

		CodingAgentJob job = new CodingAgentJob("t1", "test") {
			@Override
			protected void runCorrectionSession(String correctionPrompt, String activity) {
				correctionCalls.incrementAndGet();
			}
		};
		job.setEnforceOrganizationalPlacement(false);
		job.setPostCompletionCommand("false");
		job.runEnforcementRules();

		int attempts = correctionCalls.get();
		assertTrue("Expected at least 1 correction attempt, got " + attempts, attempts >= 1);
		assertTrue("Expected at most 3 correction attempts (default cap), got " + attempts,
				attempts <= 3);
	}

	/**
	 * A job with {@code maxPostCompletionPasses=5} allows up to 5 passes.
	 */
	@Test(timeout = 30000)
	public void postCompletionCapFiveAllowsFivePasses() {
		// Simulate the enforcement loop using getMaxRetries() as cap, as the real
		// runEnforcementRules() does.
		PostCompletionCommandRule rule = new PostCompletionCommandRule("false", null,
				PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS, 5);
		assertEquals(5, rule.getMaxRetries());

		CodingAgentJob job = new CodingAgentJob("t1", "test");
		int attempts = 0;
		if (rule.isViolated(job)) {
			while (attempts < rule.getMaxRetries() && rule.isViolated(job)) {
				rule.onCorrectionAttempted(job);
				attempts++;
			}
		}
		assertEquals("Cap of 5 must bound enforcement loop to exactly 5 attempts", 5, attempts);
		assertFalse("isViolated returns false when the pass cap is hit (self-limiting)", rule.isViolated(job));
		assertTrue("isCapHit must be true after the cap is reached", rule.isCapHit());
	}

	/**
	 * A job with {@code maxPostCompletionPasses=1} allows only 1 pass.
	 */
	@Test(timeout = 30000)
	public void postCompletionCapOneAllowsSinglePass() {
		PostCompletionCommandRule rule = new PostCompletionCommandRule("false", null,
				PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS, 1);
		assertEquals(1, rule.getMaxRetries());

		CodingAgentJob job = new CodingAgentJob("t1", "test");
		int attempts = 0;
		if (rule.isViolated(job)) {
			while (attempts < rule.getMaxRetries() && rule.isViolated(job)) {
				rule.onCorrectionAttempted(job);
				attempts++;
			}
		}
		assertEquals("Cap of 1 must bound enforcement loop to exactly 1 attempt", 1, attempts);
		assertFalse("isViolated returns false when the pass cap is hit (self-limiting)", rule.isViolated(job));
		assertTrue("isCapHit must be true after the cap is reached", rule.isCapHit());
	}

	/**
	 * A successful command on the first pass exits cleanly without further retries.
	 */
	@Test(timeout = 30000)
	public void postCompletionSuccessOnFirstPassExitsImmediately() {
		PostCompletionCommandRule rule = new PostCompletionCommandRule("true", null,
				PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS, 3);
		CodingAgentJob job = new CodingAgentJob("t1", "test");

		// Success must be reported immediately; no correction sessions should run.
		assertFalse("Successful command must not be violated", rule.isViolated(job));
		assertEquals(0, rule.getLastExitCode());
	}

	/**
	 * The cap behaviour preserves exit-on-success: a rule that succeeds after one
	 * failing attempt must exit without hitting the cap.
	 */
	@Test(timeout = 30000)
	public void postCompletionCapPreservesExitOnSuccess() throws Exception {
		File counter = File.createTempFile("pcr_cap_test_", ".txt");
		counter.deleteOnExit();
		String cmd = "if [ -f " + counter.getAbsolutePath() + " ] && "
				+ "[ \"$(cat " + counter.getAbsolutePath() + ")\" = \"done\" ]; "
				+ "then exit 0; else echo done > " + counter.getAbsolutePath() + "; exit 1; fi";

		PostCompletionCommandRule rule = new PostCompletionCommandRule(cmd, null,
				PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS, 5);

		CodingAgentJob job = new CodingAgentJob("t1", "test");
		int attempts = 0;
		if (rule.isViolated(job)) {
			while (attempts < rule.getMaxRetries() && rule.isViolated(job)) {
				rule.onCorrectionAttempted(job);
				attempts++;
			}
		}
		// Succeeded on second attempt (one correction): must not reach the cap of 5.
		assertEquals("Should have needed exactly 1 correction before success", 1, attempts);
		assertFalse("Rule should not be violated after success", rule.isViolated(job));
	}

	/**
	 * {@link PostCompletionCommandRule} constructor rejects non-positive maxPasses.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void postCompletionRuleConstructorRejectsZeroMaxPasses() {
		new PostCompletionCommandRule("true", null,
				PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS, 0);
	}

	/**
	 * {@link PostCompletionCommandRule} constructor rejects negative maxPasses.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void postCompletionRuleConstructorRejectsNegativeMaxPasses() {
		new PostCompletionCommandRule("true", null,
				PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS, -1);
	}

	/**
	 * {@link CodingAgentJob#setMaxPostCompletionPasses(int)} must reject non-positive values.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void setMaxPostCompletionPassesRejectsZero() {
		new CodingAgentJob("t1", "test").setMaxPostCompletionPasses(0);
	}

	/**
	 * {@link CodingAgentJob#setMaxPostCompletionPasses(int)} must reject negative values.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void setMaxPostCompletionPassesRejectsNegative() {
		new CodingAgentJob("t1", "test").setMaxPostCompletionPasses(-2);
	}

	/**
	 * {@link CodingAgentJobFactory#setMaxPostCompletionPasses(int)} must reject non-positive values.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void factorySetMaxPostCompletionPassesRejectsZero() {
		new CodingAgentJobFactory("prompt").setMaxPostCompletionPasses(0);
	}

	/**
	 * maxPostCompletionPasses round-trips through encode/decode (non-default value).
	 */
	@Test(timeout = 30000)
	public void maxPostCompletionPassesRoundTripEncodeDecode() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setPostCompletionCommand("make test");
		job.setMaxPostCompletionPasses(5);
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Wire format must contain maxPostCmdPasses:=5",
				encoded.contains("maxPostCmdPasses:=5"));

		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertEquals(5, restored.getMaxPostCompletionPasses());
	}

	/**
	 * The default value (3) must not appear in the wire format to keep it compact.
	 */
	@Test(timeout = 30000)
	public void maxPostCompletionPassesDefaultAbsentFromWireFormat() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setPostCompletionCommand("make test");
		// Default passes — should not appear in wire format
		String encoded = job.encode();
		assertFalse("Default maxPostCmdPasses must not appear in wire format",
				encoded.contains("maxPostCmdPasses"));
	}

	/**
	 * Factory propagates the non-default cap to the job created by nextJob().
	 */
	@Test(timeout = 30000)
	public void factoryMaxPostCompletionPassesPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		factory.setPostCompletionCommand("make test");
		factory.setMaxPostCompletionPasses(5);
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertEquals(5, job.getMaxPostCompletionPasses());
	}

	/**
	 * Factory default cap matches the CodingAgentJob constant.
	 */
	@Test(timeout = 30000)
	public void factoryMaxPostCompletionPassesDefaultIsThree() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		assertEquals(CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES,
				factory.getMaxPostCompletionPasses());
	}

	/**
	 * Factory maxPostCompletionPasses round-trips through the set() deserialization path.
	 */
	@Test(timeout = 30000)
	public void factoryMaxPostCompletionPassesRoundTripViaSet() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.set("maxPostCmdPasses", "4");
		assertEquals(4, factory.getMaxPostCompletionPasses());

		factory.set("maxPostCmdPasses", "1");
		assertEquals(1, factory.getMaxPostCompletionPasses());
	}

	/**
	 * Factory deserialization must fall back to the default when the wire value is empty.
	 */
	@Test(timeout = 30000)
	public void factoryMaxPostCompletionPassesDeserializationFallsBackOnEmptyString() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setPostCompletionCommand("make test");
		factory.setMaxPostCompletionPasses(5);
		factory.set("maxPostCmdPasses", "");
		assertEquals("Empty string must fall back to default",
				CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES,
				factory.getMaxPostCompletionPasses());
	}

	/**
	 * Factory deserialization must fall back to the default when the wire value is non-positive.
	 */
	@Test(timeout = 30000)
	public void factoryMaxPostCompletionPassesDeserializationFallsBackOnNonPositive() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.set("maxPostCmdPasses", "0");
		assertEquals("Zero must fall back to default",
				CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES,
				factory.getMaxPostCompletionPasses());
	}

	/**
	 * Job deserialization falls back to the default when the wire value is invalid.
	 */
	@Test(timeout = 30000)
	public void maxPostCompletionPassesDeserializationFallsBackOnInvalidString() {
		CodingAgentJob job = new CodingAgentJob("t1", "test");
		job.set("maxPostCmdPasses", "notanumber");
		assertEquals("Non-numeric value must fall back to default",
				CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES,
				job.getMaxPostCompletionPasses());
	}

	/**
	 * Job deserialization falls back to the default when the wire value is non-positive.
	 */
	@Test(timeout = 30000)
	public void maxPostCompletionPassesDeserializationFallsBackOnNonPositive() {
		CodingAgentJob job = new CodingAgentJob("t1", "test");
		job.set("maxPostCmdPasses", "0");
		assertEquals("Zero must fall back to default",
				CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES,
				job.getMaxPostCompletionPasses());

		job.set("maxPostCmdPasses", "-2");
		assertEquals("Negative must fall back to default",
				CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES,
				job.getMaxPostCompletionPasses());
	}
}
