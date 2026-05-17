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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the enforcement rule framework on {@link CodingAgentJob}:
 * the {@link EnforcementRule} interface, built-in rule configuration,
 * Maven dependency protection, and serialisation round-trips.
 */
public class CodingAgentJobEnforcementTest extends TestSuiteBase {

	// ── EnforcementRule interface defaults ──────────────────────────────────

	@Test(timeout = 30000)
	public void defaultMaxRetriesMatchesConstant() {
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "test"; }
			@Override
			public boolean isViolated(CodingAgentJob job) { return false; }
			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
		};
		assertEquals(CodingAgentJob.DEFAULT_MAX_RULE_RETRIES, rule.getMaxRetries());
	}

	@Test(timeout = 30000)
	public void defaultMaxRetriesValue() {
		assertEquals(5, CodingAgentJob.DEFAULT_MAX_RULE_RETRIES);
	}

	// ── enforceMavenDependencies flag ────────────────────────────────────────

	@Test(timeout = 30000)
	public void enforceMavenDependenciesDefaultFalse() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertFalse(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void setEnforceMavenDependenciesTrue() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		job.setEnforceMavenDependencies(true);
		assertTrue(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void setEnforceMavenDependenciesRoundTrip() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		job.setEnforceMavenDependencies(true);
		assertTrue(job.isEnforceMavenDependencies());
		job.setEnforceMavenDependencies(false);
		assertFalse(job.isEnforceMavenDependencies());
	}

	// ── Serialisation — enforceMavenDependencies ─────────────────────────────

	@Test(timeout = 30000)
	public void enforceMavenDepsAppearsInWireFormatWhenTrue() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setEnforceMavenDependencies(true);
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Expected enforceMavenDeps:=true in: " + encoded,
				encoded.contains("enforceMavenDeps:=true"));
	}

	@Test(timeout = 30000)
	public void enforceMavenDepsAbsentInWireFormatWhenFalse() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		String encoded = job.encode();
		assertNotNull(encoded);
		assertFalse("Did not expect enforceMavenDeps in: " + encoded,
				encoded.contains("enforceMavenDeps"));
	}

	@Test(timeout = 30000)
	public void enforceMavenDepsDeserialises() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setEnforceMavenDependencies(true);

		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertTrue(restored.isEnforceMavenDependencies());
	}

	// ── addEnforcementRule ───────────────────────────────────────────────────

	@Test(timeout = 30000)
	public void addEnforcementRuleAcceptsCustomRule() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "my-custom-rule"; }
			@Override
			public boolean isViolated(CodingAgentJob j) { return false; }
			@Override
			public String buildCorrectionPrompt(CodingAgentJob j) { return "fix it"; }
		};
		job.addEnforcementRule(rule);
		// No exception thrown means the rule was accepted; correctness of
		// execution is verified by integration / functional tests.
	}

	@Test(timeout = 30000)
	public void customRuleMaxRetriesOverridable() {
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "limited-rule"; }
			@Override
			public boolean isViolated(CodingAgentJob j) { return false; }
			@Override
			public String buildCorrectionPrompt(CodingAgentJob j) { return "fix it"; }
			@Override
			public int getMaxRetries() { return 2; }
		};
		assertEquals(2, rule.getMaxRetries());
	}

	// ── CodingAgentJobFactory — enforceMavenDependencies ─────────────────────

	@Test(timeout = 30000)
	public void factoryEnforceMavenDependenciesDefaultFalse() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		assertFalse(factory.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void factorySetEnforceMavenDependenciesPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		factory.setEnforceMavenDependencies(true);
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertTrue(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void factoryEnforceMavenDependenciesDefaultDoesNotPropagateTrue() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertFalse(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void factoryEnforceMavenDepsRoundTripViaEncode() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setEnforceMavenDependencies(true);
		assertTrue(factory.isEnforceMavenDependencies());

		// Simulate wire serialization round-trip via set()
		factory.set("enforceMavenDeps", "false");
		assertFalse(factory.isEnforceMavenDependencies());

		factory.set("enforceMavenDeps", "true");
		assertTrue(factory.isEnforceMavenDependencies());
	}

	// ── onCorrectionAttempted callback ──────────────────────────────────────

	@Test(timeout = 30000)
	public void onCorrectionAttemptedDefaultIsNoOp() {
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "test"; }
			@Override
			public boolean isViolated(CodingAgentJob job) { return false; }
			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
		};
		// Default implementation must not throw and must have no visible side-effects.
		rule.onCorrectionAttempted(new CodingAgentJob("t1", "do something"));
	}

	@Test(timeout = 30000)
	public void onCorrectionAttemptedCanBeOverridden() {
		AtomicInteger callCount = new AtomicInteger();
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "counting-rule"; }
			@Override
			public boolean isViolated(CodingAgentJob job) { return false; }
			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
			@Override
			public void onCorrectionAttempted(CodingAgentJob job) { callCount.incrementAndGet(); }
		};
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		rule.onCorrectionAttempted(job);
		rule.onCorrectionAttempted(job);
		assertEquals(2, callCount.get());
	}

	// ── DeduplicationRule method-set exit condition ─────────────────────────

	/**
	 * Simulates the DeduplicationRule method-set comparison exit condition using
	 * a custom rule that tracks a synthetic method list. After a correction session
	 * that removes no methods, {@link EnforcementRule#onCorrectionAttempted} marks
	 * the rule as resolved and the next {@link EnforcementRule#isViolated} call must
	 * return {@code false} so the loop exits.
	 *
	 * <p>The simulation includes an extra pre-correction {@code isViolated()} call to
	 * model the {@code runEnforcementRules()} calling pattern: once for the outer
	 * {@code if} check and once for the {@code while} condition before the first
	 * correction attempt. Both pre-correction calls must return {@code true}.</p>
	 */
	@Test(timeout = 30000)
	public void methodSetComparisonExitsLoopWhenUnchanged() {
		// Simulate a rule whose "new methods" list does not change across calls.
		// This mimics a deduplication audit where the agent found no duplicates.
		AtomicInteger callCount = new AtomicInteger();
		Set<String> methodSet = new LinkedHashSet<>();
		methodSet.add("myMethod");

		AtomicReference<Set<String>> lastSeen = new AtomicReference<>();
		AtomicReference<Boolean> resolved = new AtomicReference<>(false);

		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "method-set-test"; }

			@Override
			public boolean isViolated(CodingAgentJob job) {
				if (resolved.get()) return false;
				Set<String> current = new LinkedHashSet<>(methodSet);
				lastSeen.set(current);
				callCount.incrementAndGet();
				return !current.isEmpty();
			}

			@Override
			public void onCorrectionAttempted(CodingAgentJob job) {
				Set<String> current = new LinkedHashSet<>(methodSet);
				if (lastSeen.get() != null && current.equals(lastSeen.get())) {
					resolved.set(true);
				}
			}

			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
		};

		CodingAgentJob job = new CodingAgentJob("t1", "do something");

		// Simulate runEnforcementRules():
		// 1. outer if (rule.isViolated(job)) — violation detected, record the set.
		assertTrue(rule.isViolated(job));
		// 2. while (rule.isViolated(job) && ...) — no correction has run yet, must
		//    still return true so the loop body is entered.
		assertTrue(rule.isViolated(job));
		// 3. Correction session runs (agent found no duplicates, nothing removed).
		rule.onCorrectionAttempted(job);
		// 4. while condition re-checked: method set unchanged after correction — exit.
		assertFalse(rule.isViolated(job));
		// Both pre-correction isViolated calls should have incremented the counter.
		assertEquals(2, callCount.get());
	}

	/**
	 * Simulates a deduplication pass that removes one method, then finds no more.
	 * The loop should continue after the first correction (set changed), then exit
	 * after the second correction (set unchanged).
	 *
	 * <p>The simulation mirrors the {@code runEnforcementRules()} calling pattern:
	 * two {@code isViolated()} calls precede the first correction attempt (outer
	 * {@code if} + first {@code while} condition), and the comparison that triggers
	 * loop exit is performed in {@link EnforcementRule#onCorrectionAttempted}.</p>
	 */
	@Test(timeout = 30000)
	public void methodSetComparisonContinuesLoopWhenMethodsRemoved() {
		// Simulate a deduplication pass that removes one method, then finds no more.
		List<String> methodList = new ArrayList<>();
		methodList.add("methodA");
		methodList.add("methodB");

		AtomicReference<Set<String>> lastSeen = new AtomicReference<>();
		AtomicReference<Boolean> resolved = new AtomicReference<>(false);
		AtomicInteger violationCount = new AtomicInteger();

		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "method-set-removal-test"; }

			@Override
			public boolean isViolated(CodingAgentJob job) {
				if (resolved.get()) return false;
				Set<String> current = new LinkedHashSet<>(methodList);
				lastSeen.set(current);
				boolean violated = !current.isEmpty();
				if (violated) violationCount.incrementAndGet();
				return violated;
			}

			@Override
			public void onCorrectionAttempted(CodingAgentJob job) {
				Set<String> current = new LinkedHashSet<>(methodList);
				if (lastSeen.get() != null && current.equals(lastSeen.get())) {
					resolved.set(true);
				}
			}

			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
		};

		CodingAgentJob job = new CodingAgentJob("t1", "do something");

		// Simulate runEnforcementRules():
		// 1. outer if: both methods present.
		assertTrue(rule.isViolated(job));
		// 2. while condition 1st iteration: no correction yet, must still return true.
		assertTrue(rule.isViolated(job));
		// First correction: agent removes one method.
		methodList.remove("methodB");
		rule.onCorrectionAttempted(job);
		// 3. while condition 2nd iteration: set changed — loop should continue.
		assertTrue(rule.isViolated(job));
		// Second correction: agent finds no more duplicates (nothing removed).
		rule.onCorrectionAttempted(job);
		// 4. while condition 3rd iteration: set unchanged after second correction — exit.
		assertFalse(rule.isViolated(job));
		// Three isViolated calls returned true (outer if + two while iterations).
		assertEquals(3, violationCount.get());
	}

	// ── Correction session activity tagging ─────────────────────────────────

	/**
	 * {@link DeduplicationRule} must return {@code "deduplication"} from
	 * {@link EnforcementRule#getName()} so that correction sessions started
	 * by that rule pass {@code "deduplication"} as the activity to
	 * {@code runCorrectionSession}, which propagates it to the
	 * {@code AR_AGENT_ACTIVITY} environment variable.
	 */
	@Test(timeout = 30000)
	public void deduplicationRuleNameIsDeduplication() {
		DeduplicationRule rule = new DeduplicationRule();
		assertEquals("deduplication", rule.getName());
	}

	/**
	 * Verifies that {@link CodingAgentJob#runEnforcementRules()} passes
	 * {@link EnforcementRule#getName()} as the {@code activity} parameter to
	 * {@code runCorrectionSession}, which propagates it to {@code AR_AGENT_ACTIVITY}
	 * in the subprocess environment.
	 *
	 * <p>A spy subclass overrides {@code runCorrectionSession} to capture the
	 * {@code activity} argument without launching a real subprocess.  The test
	 * calls {@code runEnforcementRules()} directly (package-private access) so
	 * the full enforcement loop is exercised.</p>
	 */
	@Test(timeout = 30000)
	public void correctionSessionActivityMatchesRuleName() {
		AtomicReference<String> capturedActivity = new AtomicReference<>();

		EnforcementRule rule = new EnforcementRule() {
			private boolean done = false;

			@Override
			public String getName() { return "maven_dependency_protection"; }

			@Override
			public boolean isViolated(CodingAgentJob job) { return !done; }

			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) {
				done = true;
				return "correct the dependency violation";
			}
		};

		// Spy subclass: capture the activity argument without launching a subprocess.
		CodingAgentJob job = new CodingAgentJob("t1", "test") {
			@Override
			protected void runCorrectionSession(String correctionPrompt, String activity) {
				capturedActivity.set(activity);
			}
		};

		// Disable built-in rules so only the spy rule fires.
		job.setEnforceOrganizationalPlacement(false);
		job.addEnforcementRule(rule);

		// Exercise the real enforcement path.
		job.runEnforcementRules();

		assertEquals("maven_dependency_protection", capturedActivity.get());
	}

	// ── enforce_changes suppression during rule correction sessions ─────────

	/**
	 * When {@code enforce_changes} is enabled on the outer job, the primary
	 * work prompt carries the strict "Code Changes Are Required" preamble.
	 * Verifies the wiring through {@link CodingAgentJob#buildInstructionPrompt()}.
	 */
	@Test(timeout = 30000)
	public void primaryPromptCarriesEnforceChangesStrictBlock() {
		CodingAgentJob job = new CodingAgentJob("t1", "do the work");
		job.setEnforceChanges(true);
		job.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1");

		String primary = job.buildInstructionPrompt();
		assertTrue("Primary prompt with enforceChanges should carry strict block",
				primary.contains("Code Changes Are Required"));
	}

	/**
	 * The enforcement-rule correction sessions for rules whose
	 * {@code buildCorrectionPrompt} returns a non-null value (deduplication,
	 * organizational placement, maven dependency protection, post-completion
	 * command) must NOT carry the outer {@code enforce_changes} strict preamble.
	 * The rule's own correction prompt is self-contained and may legitimately
	 * accept "no changes needed" as a valid outcome.
	 *
	 * <p>This test verifies the wiring from {@link CodingAgentJob#setCurrentActivity(String)}
	 * through {@link CodingAgentJob#buildInstructionPrompt()} to
	 * {@link InstructionPromptBuilder#setCorrectionSession(boolean)}.</p>
	 */
	@Test(timeout = 30000)
	public void correctionSessionPromptOmitsEnforceChangesStrictBlock() {
		CodingAgentJob job = new CodingAgentJob("t1", "rule correction prompt");
		job.setEnforceChanges(true);
		job.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1");

		// Simulate the state runCorrectionSession sets while invoking the agent.
		job.setCurrentActivity("deduplication");

		String correction = job.buildInstructionPrompt();
		assertFalse("Correction-session prompt must not include 'Code Changes Are Required'",
				correction.contains("Code Changes Are Required"));
		assertFalse("Correction-session prompt must not include the 'Exiting without code changes' threat",
				correction.contains("Exiting without code changes"));
		assertTrue("Correction-session prompt must include the permissive Non-Code Requests block",
				correction.contains("Non-Code Requests"));
	}

	/**
	 * Sanity check: clearing the activity restores primary-session behaviour
	 * so the outer enforce_changes pressure resumes for the next primary run.
	 */
	@Test(timeout = 30000)
	public void clearingCurrentActivityRestoresPrimaryPromptBehaviour() {
		CodingAgentJob job = new CodingAgentJob("t1", "primary prompt");
		job.setEnforceChanges(true);
		job.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1");

		job.setCurrentActivity("organizational-placement");
		assertFalse(job.buildInstructionPrompt().contains("Code Changes Are Required"));

		job.setCurrentActivity(null);
		assertTrue("Strict block returns once activity is cleared",
				job.buildInstructionPrompt().contains("Code Changes Are Required"));
	}

	/**
	 * The harness_feedback invitation is included in primary prompts when a
	 * workstream URL is configured.  Verifies the wiring through
	 * {@link CodingAgentJob#buildInstructionPrompt()}.
	 */
	@Test(timeout = 30000)
	public void primaryPromptIncludesHarnessFeedbackInvitation() {
		CodingAgentJob job = new CodingAgentJob("t1", "do the work");
		job.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1");

		String primary = job.buildInstructionPrompt();
		assertTrue("Primary prompt should invite harness_feedback messages",
				primary.contains("harness_feedback"));
		assertTrue("Primary prompt should include the Feedback to the Harness section",
				primary.contains("Feedback to the Harness"));
	}

	// ── Backward compatibility ───────────────────────────────────────────────

	@Test(timeout = 30000)
	public void enforceChangesStillFunctionsAfterRefactor() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		assertFalse(job.isEnforceChanges());

		job.setEnforceChanges(true);
		assertTrue(job.isEnforceChanges());
	}

	@Test(timeout = 30000)
	public void factoryDeduplicationModeDefaultIsLocal() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		assertEquals(CodingAgentJob.DEDUP_LOCAL, factory.getDeduplicationMode());
	}

	// ── OrganizationalPlacementRule — flag defaults ──────────────────────────

	@Test(timeout = 30000)
	public void enforceOrganizationalPlacementDefaultTrue() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertTrue(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void setEnforceOrganizationalPlacementFalse() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		job.setEnforceOrganizationalPlacement(false);
		assertFalse(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void setEnforceOrganizationalPlacementRoundTrip() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertTrue(job.isEnforceOrganizationalPlacement());
		job.setEnforceOrganizationalPlacement(false);
		assertFalse(job.isEnforceOrganizationalPlacement());
		job.setEnforceOrganizationalPlacement(true);
		assertTrue(job.isEnforceOrganizationalPlacement());
	}

	// ── OrganizationalPlacementRule — serialisation ──────────────────────────

	@Test(timeout = 30000)
	public void enforceOrgPlacementAbsentInWireFormatWhenTrue() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		// default is true — should not appear in wire format
		String encoded = job.encode();
		assertNotNull(encoded);
		assertFalse("Did not expect enforceOrgPlacement in: " + encoded,
				encoded.contains("enforceOrgPlacement"));
	}

	@Test(timeout = 30000)
	public void enforceOrgPlacementAppearsInWireFormatWhenFalse() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setEnforceOrganizationalPlacement(false);
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Expected enforceOrgPlacement:=false in: " + encoded,
				encoded.contains("enforceOrgPlacement:=false"));
	}

	@Test(timeout = 30000)
	public void enforceOrgPlacementDeserialises() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setEnforceOrganizationalPlacement(false);

		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertFalse(restored.isEnforceOrganizationalPlacement());
	}

	// ── OrganizationalPlacementRule — factory propagation ───────────────────

	@Test(timeout = 30000)
	public void factoryEnforceOrganizationalPlacementDefaultTrue() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		assertTrue(factory.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void factorySetEnforceOrganizationalPlacementFalsePropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		factory.setEnforceOrganizationalPlacement(false);
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertFalse(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void factoryEnforceOrganizationalPlacementDefaultPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertTrue(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void factoryEnforceOrgPlacementRoundTripViaSet() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		assertTrue(factory.isEnforceOrganizationalPlacement());

		factory.set("enforceOrgPlacement", "false");
		assertFalse(factory.isEnforceOrganizationalPlacement());

		factory.set("enforceOrgPlacement", "true");
		assertTrue(factory.isEnforceOrganizationalPlacement());
	}

	// ── Outer do-while loop — cycling between rules ──────────────────────────

	/**
	 * Simulates the outer do-while loop in {@code runEnforcementRules()}: when rule A
	 * runs a correction session during a pass, the loop restarts and both rules are
	 * checked again. The loop exits only when a full pass produces no correction sessions.
	 */
	@Test(timeout = 30000)
	public void outerLoopRestartsCycleWhenAnyRuleCorrectionRan() {
		AtomicInteger ruleACorrections = new AtomicInteger();
		AtomicInteger ruleBChecks = new AtomicInteger();

		// Rule A: violated on first check only (simulates one correction needed)
		AtomicInteger ruleACheckCount = new AtomicInteger();
		EnforcementRule ruleA = new EnforcementRule() {
			@Override
			public String getName() { return "rule-a"; }

			@Override
			public boolean isViolated(CodingAgentJob job) {
				// Violated only on the first check; clean afterwards
				return ruleACheckCount.incrementAndGet() <= 2;
			}

			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) { return "fix A"; }

			@Override
			public void onCorrectionAttempted(CodingAgentJob job) {
				ruleACorrections.incrementAndGet();
			}
		};

		// Rule B: always clean — just counts how many times it is checked
		EnforcementRule ruleB = new EnforcementRule() {
			@Override
			public String getName() { return "rule-b"; }

			@Override
			public boolean isViolated(CodingAgentJob job) {
				ruleBChecks.incrementAndGet();
				return false;
			}

			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) { return "fix B"; }
		};

		// Simulate one outer loop pass where rule A corrects and rule B passes
		// Pass 1: ruleA violated (outer if) → violated (while condition) → correction → resolved
		//         ruleB: not violated → skip
		// anyRuleCorrectionRan = true → restart
		// Pass 2: ruleA not violated → skip; ruleB not violated → skip
		// anyRuleCorrectionRan = false → exit

		boolean anyRuleCorrectionRan;
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		List<EnforcementRule> rules = List.of(ruleA, ruleB);

		do {
			anyRuleCorrectionRan = false;
			for (EnforcementRule rule : rules) {
				if (!rule.isViolated(job)) continue;
				int attempts = 0;
				while (attempts < rule.getMaxRetries() && rule.isViolated(job)) {
					attempts++;
					anyRuleCorrectionRan = true;
					rule.buildCorrectionPrompt(job); // simulate correction session
					rule.onCorrectionAttempted(job);
				}
			}
		} while (anyRuleCorrectionRan);

		// Rule A should have had exactly one correction
		assertEquals(1, ruleACorrections.get());
		// Rule B should have been checked in both passes (2 times)
		assertEquals(2, ruleBChecks.get());
	}

	// ── Total-attempt cap (regression for unbounded retry loop) ─────────────

	/**
	 * Reproduces the failure mode that produced 4000+ enforcement attempts on
	 * a stuck job: an enforcement rule that always reports a violation, no
	 * agent commit, no progress.  Prior to the cap the outer
	 * {@code do-while} in {@link CodingAgentJob#runEnforcementRules()} reset
	 * the per-rule {@code attempts} counter every pass and looped forever.
	 *
	 * <p>The test uses a spy {@code runCorrectionSession} so no real Claude
	 * subprocess is launched.  It asserts the call count is bounded by
	 * {@link CodingAgentJob#DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS}.</p>
	 */
	@Test(timeout = 30000)
	public void runEnforcementRulesAbortsWhenTotalAttemptCapHit() {
		AtomicInteger correctionCalls = new AtomicInteger();

		EnforcementRule alwaysViolated = new EnforcementRule() {
			@Override
			public String getName() { return "test-always-violated"; }
			@Override
			public boolean isViolated(CodingAgentJob job) { return true; }
			@Override
			public String buildCorrectionPrompt(CodingAgentJob job) {
				return "this will never resolve";
			}
		};

		CodingAgentJob job = new CodingAgentJob("t1", "test") {
			@Override
			protected void runCorrectionSession(String correctionPrompt, String activity) {
				correctionCalls.incrementAndGet();
			}
		};

		// Disable built-in rules so only the chronically-broken spy rule fires.
		job.setEnforceOrganizationalPlacement(false);
		job.addEnforcementRule(alwaysViolated);

		job.runEnforcementRules();

		int attempts = correctionCalls.get();
		assertTrue("Expected attempts >= 1, got " + attempts, attempts >= 1);
		assertTrue("Expected attempts <= " + CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS
				+ ", got " + attempts,
				attempts <= CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS);
		assertEquals(CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS, attempts);
	}

	@Test(timeout = 30000)
	public void totalEnforcementAttemptCapDefaultValue() {
		// Pin the constant so silent regressions (e.g., raising the cap by an
		// order of magnitude) are caught.  25 is comfortably above the
		// 5-retries-per-rule × 4 built-in rules a healthy job could reach
		// once, and roughly 160× below the observed pathological case.
		assertEquals(25, CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS);
	}

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

	// ── PostCompletionCommandRule — CodingAgentJob integration ────────────────

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
		job.setPostCompletionCommand("mvn -pl flowtree test -Dtest=Foo");
		assertEquals("mvn -pl flowtree test -Dtest=Foo", job.getPostCompletionCommand());
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
		job.setPostCompletionCommand("mvn -pl flowtree test -Dtest=FooTest");
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Wire format must contain postCmd", encoded.contains("postCmd:="));

		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertEquals("mvn -pl flowtree test -Dtest=FooTest", restored.getPostCompletionCommand());
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
		factory.setPostCompletionCommand("mvn -pl flowtree test -Dtest=FooTest");
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertEquals("mvn -pl flowtree test -Dtest=FooTest", job.getPostCompletionCommand());
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

	// ── DeduplicationRule pass cap ───────────────────────────────────────────

	/**
	 * Pins the default cap constant so silent regressions are caught.
	 */
	@Test(timeout = 30000)
	public void defaultDeduplicationPassCapIsTwo() {
		assertEquals(2, CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES);
	}

	/**
	 * The pass cap must be exposed via {@link EnforcementRule#getMaxRetries()} so
	 * the enforcement framework can bound the correction loop. This keeps
	 * {@link EnforcementRule#isViolated(CodingAgentJob)} honest — it always reflects
	 * the actual violation state rather than returning {@code false} to stop the loop.
	 */
	@Test(timeout = 30000)
	public void deduplicationRuleCapExposedViaGetMaxRetries() {
		assertEquals(CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, new DeduplicationRule().getMaxRetries());
		assertEquals(5, new DeduplicationRule(5).getMaxRetries());
		assertEquals(1, new DeduplicationRule(1).getMaxRetries());
	}

	/**
	 * DeduplicationRule with default cap (2): the enforcement loop (simulated here
	 * using the same {@code attempts < getMaxRetries()} guard the framework uses)
	 * must stop after 2 correction attempts.
	 *
	 * <p>{@link EnforcementRule#isViolated} must continue to return {@code true}
	 * after the cap is reached if duplicates still exist — the cap is enforced by
	 * the framework counting attempts, not by {@code isViolated} returning
	 * {@code false}.</p>
	 */
	@Test(timeout = 30000)
	public void deduplicationRuleCapStopsAfterDefaultTwoPasses() {
		List<String> items = new ArrayList<>(List.of("methodA", "methodB", "methodC"));
		DeduplicationRule rule = new DeduplicationRule() {
			@Override
			protected List<String> extractItems(CodingAgentJob job) {
				return new ArrayList<>(items);
			}
		};
		CodingAgentJob job = new CodingAgentJob("t1", "test");

		// Simulate the runEnforcementRules() while loop using getMaxRetries() as cap.
		int attempts = 0;
		if (rule.isViolated(job)) {
			while (attempts < rule.getMaxRetries() && rule.isViolated(job)) {
				if (!items.isEmpty()) items.remove(0); // agent removes one method per pass
				rule.onCorrectionAttempted(job);
				attempts++;
			}
		}
		// Default cap of 2 must bound the enforcement loop.
		assertEquals("Default cap of 2 must bound enforcement loop", 2, attempts);
		// isViolated must still accurately report violations after the cap.
		assertTrue("isViolated must still be true because items remain", rule.isViolated(job));
	}

	/**
	 * DeduplicationRule with cap=5: the enforcement loop simulation runs exactly
	 * 5 correction attempts.
	 */
	@Test(timeout = 30000)
	public void deduplicationRuleCapFiveAllowsFivePasses() {
		List<String> items = new ArrayList<>();
		for (int i = 0; i < 10; i++) items.add("method" + i);

		DeduplicationRule rule = new DeduplicationRule(5) {
			@Override
			protected List<String> extractItems(CodingAgentJob job) {
				return new ArrayList<>(items);
			}
		};
		CodingAgentJob job = new CodingAgentJob("t1", "test");
		assertEquals(5, rule.getMaxRetries());

		int attempts = 0;
		if (rule.isViolated(job)) {
			while (attempts < rule.getMaxRetries() && rule.isViolated(job)) {
				if (!items.isEmpty()) items.remove(0);
				rule.onCorrectionAttempted(job);
				attempts++;
			}
		}
		assertEquals("Cap of 5 must bound enforcement loop to exactly 5 attempts", 5, attempts);
		assertTrue("isViolated must still be true because items remain", rule.isViolated(job));
	}

	/**
	 * DeduplicationRule with cap=1: the enforcement loop simulation runs exactly
	 * 1 correction attempt.
	 */
	@Test(timeout = 30000)
	public void deduplicationRuleCapOneAllowsSinglePass() {
		List<String> items = new ArrayList<>(List.of("methodA", "methodB"));

		DeduplicationRule rule = new DeduplicationRule(1) {
			@Override
			protected List<String> extractItems(CodingAgentJob job) {
				return new ArrayList<>(items);
			}
		};
		CodingAgentJob job = new CodingAgentJob("t1", "test");
		assertEquals(1, rule.getMaxRetries());

		int attempts = 0;
		if (rule.isViolated(job)) {
			while (attempts < rule.getMaxRetries() && rule.isViolated(job)) {
				items.remove("methodB");
				rule.onCorrectionAttempted(job);
				attempts++;
			}
		}
		assertEquals("Cap of 1 must bound enforcement loop to exactly 1 attempt", 1, attempts);
		assertTrue("isViolated must still be true because items remain", rule.isViolated(job));
	}

	/**
	 * The early-exit on unchanged method set still works independently of the cap.
	 * When the agent makes no changes, the rule should exit before hitting the cap.
	 */
	@Test(timeout = 30000)
	public void deduplicationRuleEarlyExitOnUnchangedSetBeforeCap() {
		List<String> items = new ArrayList<>(List.of("methodA"));

		DeduplicationRule rule = new DeduplicationRule(5) { // cap is 5
			@Override
			protected List<String> extractItems(CodingAgentJob job) {
				return new ArrayList<>(items);
			}
		};
		CodingAgentJob job = new CodingAgentJob("t1", "test");

		// outer if + while condition
		assertTrue(rule.isViolated(job));
		assertTrue(rule.isViolated(job));

		// Agent made no changes: onCorrectionAttempted should set resolved = true
		rule.onCorrectionAttempted(job); // set unchanged → resolved

		// Resolved by unchanged-set logic — must return false even though cap not yet reached
		assertFalse("Unchanged set must trigger early exit before cap", rule.isViolated(job));
	}

	// ── DeduplicationRule input validation ───────────────────────────────────

	/**
	 * Constructor must reject non-positive maxPasses to prevent accidental deduplication
	 * disablement.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void deduplicationRuleConstructorRejectsZero() {
		new DeduplicationRule(0);
	}

	/**
	 * Constructor must reject negative maxPasses.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void deduplicationRuleConstructorRejectsNegative() {
		new DeduplicationRule(-1);
	}

	/**
	 * {@link CodingAgentJob#setMaxDeduplicationPasses(int)} must reject non-positive values.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void setMaxDeduplicationPassesRejectsZero() {
		new CodingAgentJob("t1", "test").setMaxDeduplicationPasses(0);
	}

	/**
	 * {@link CodingAgentJob#setMaxDeduplicationPasses(int)} must reject negative values.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void setMaxDeduplicationPassesRejectsNegative() {
		new CodingAgentJob("t1", "test").setMaxDeduplicationPasses(-5);
	}

	/**
	 * {@link CodingAgentJobFactory#setMaxDeduplicationPasses(int)} must reject non-positive values.
	 */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void factorySetMaxDeduplicationPassesRejectsZero() {
		new CodingAgentJobFactory("prompt").setMaxDeduplicationPasses(0);
	}

	/**
	 * Deserialization must fall back to the default when the wire value is empty,
	 * rather than throwing {@code NumberFormatException}.
	 */
	@Test(timeout = 30000)
	public void maxDeduplicationPassesDeserializationFallsBackOnEmptyString() {
		CodingAgentJob job = new CodingAgentJob("t1", "test");
		job.setMaxDeduplicationPasses(3);
		job.set("maxDedupPasses", "");
		assertEquals("Empty string must fall back to default",
				CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, job.getMaxDeduplicationPasses());
	}

	/**
	 * Deserialization must fall back to the default when the wire value is non-numeric.
	 */
	@Test(timeout = 30000)
	public void maxDeduplicationPassesDeserializationFallsBackOnInvalidString() {
		CodingAgentJob job = new CodingAgentJob("t1", "test");
		job.set("maxDedupPasses", "notanumber");
		assertEquals("Non-numeric value must fall back to default",
				CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, job.getMaxDeduplicationPasses());
	}

	/**
	 * Deserialization must fall back to the default when the wire value is non-positive.
	 */
	@Test(timeout = 30000)
	public void maxDeduplicationPassesDeserializationFallsBackOnNonPositive() {
		CodingAgentJob job = new CodingAgentJob("t1", "test");
		job.set("maxDedupPasses", "0");
		assertEquals("Zero must fall back to default",
				CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, job.getMaxDeduplicationPasses());

		job.set("maxDedupPasses", "-3");
		assertEquals("Negative must fall back to default",
				CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, job.getMaxDeduplicationPasses());
	}

	/**
	 * Factory deserialization must fall back to the default when the wire value is empty.
	 */
	@Test(timeout = 30000)
	public void factoryMaxDeduplicationPassesDeserializationFallsBackOnEmptyString() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setMaxDeduplicationPasses(4);
		factory.set("maxDedupPasses", "");
		assertEquals("Factory: empty string must fall back to default",
				CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, factory.getMaxDeduplicationPasses());
	}

	/**
	 * Factory deserialization must fall back to the default when the wire value is non-positive.
	 */
	@Test(timeout = 30000)
	public void factoryMaxDeduplicationPassesDeserializationFallsBackOnNonPositive() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.set("maxDedupPasses", "0");
		assertEquals("Factory: zero must fall back to default",
				CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, factory.getMaxDeduplicationPasses());
	}

	/**
	 * maxDeduplicationPasses round-trips through encode/decode (non-default value).
	 */
	@Test(timeout = 30000)
	public void maxDeduplicationPassesRoundTripEncodeDecode() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
		job.setMaxDeduplicationPasses(5);
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Wire format must contain maxDedupPasses:=5",
				encoded.contains("maxDedupPasses:=5"));

		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertEquals(5, restored.getMaxDeduplicationPasses());
	}

	/**
	 * The default value (2) must not appear in the wire format to keep it compact.
	 */
	@Test(timeout = 30000)
	public void maxDeduplicationPassesDefaultAbsentFromWireFormat() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		String encoded = job.encode();
		assertFalse("Default maxDedupPasses must not appear in wire format",
				encoded.contains("maxDedupPasses"));
	}

	/**
	 * Factory propagates the non-default cap to the job created by nextJob().
	 */
	@Test(timeout = 30000)
	public void factoryMaxDeduplicationPassesPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
		factory.setMaxDeduplicationPasses(4);
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertEquals(4, job.getMaxDeduplicationPasses());
	}

	/**
	 * Factory default cap matches the CodingAgentJob constant.
	 */
	@Test(timeout = 30000)
	public void factoryMaxDeduplicationPassesDefaultIsTwo() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		assertEquals(CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES, factory.getMaxDeduplicationPasses());
	}

	/**
	 * Factory maxDeduplicationPasses round-trips through the set() deserialization path.
	 */
	@Test(timeout = 30000)
	public void factoryMaxDeduplicationPassesRoundTripViaSet() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.set("maxDedupPasses", "3");
		assertEquals(3, factory.getMaxDeduplicationPasses());

		factory.set("maxDedupPasses", "1");
		assertEquals(1, factory.getMaxDeduplicationPasses());
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
	 * Factory deserialization falls back to the default when the wire value is empty.
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
	 * Factory deserialization falls back to the default when the wire value is non-positive.
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
