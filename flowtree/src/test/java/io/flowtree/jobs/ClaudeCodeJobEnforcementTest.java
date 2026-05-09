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
 * Tests for the enforcement rule framework on {@link ClaudeCodeJob}:
 * the {@link EnforcementRule} interface, built-in rule configuration,
 * Maven dependency protection, and serialisation round-trips.
 */
public class ClaudeCodeJobEnforcementTest extends TestSuiteBase {

	// ── EnforcementRule interface defaults ──────────────────────────────────

	@Test(timeout = 30000)
	public void defaultMaxRetriesMatchesConstant() {
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "test"; }
			@Override
			public boolean isViolated(ClaudeCodeJob job) { return false; }
			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix it"; }
		};
		assertEquals(ClaudeCodeJob.DEFAULT_MAX_RULE_RETRIES, rule.getMaxRetries());
	}

	@Test(timeout = 30000)
	public void defaultMaxRetriesValue() {
		assertEquals(5, ClaudeCodeJob.DEFAULT_MAX_RULE_RETRIES);
	}

	// ── enforceMavenDependencies flag ────────────────────────────────────────

	@Test(timeout = 30000)
	public void enforceMavenDependenciesDefaultFalse() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		assertFalse(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void setEnforceMavenDependenciesTrue() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		job.setEnforceMavenDependencies(true);
		assertTrue(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void setEnforceMavenDependenciesRoundTrip() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		job.setEnforceMavenDependencies(true);
		assertTrue(job.isEnforceMavenDependencies());
		job.setEnforceMavenDependencies(false);
		assertFalse(job.isEnforceMavenDependencies());
	}

	// ── Serialisation — enforceMavenDependencies ─────────────────────────────

	@Test(timeout = 30000)
	public void enforceMavenDepsAppearsInWireFormatWhenTrue() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setEnforceMavenDependencies(true);
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Expected enforceMavenDeps:=true in: " + encoded,
				encoded.contains("enforceMavenDeps:=true"));
	}

	@Test(timeout = 30000)
	public void enforceMavenDepsAbsentInWireFormatWhenFalse() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		String encoded = job.encode();
		assertNotNull(encoded);
		assertFalse("Did not expect enforceMavenDeps in: " + encoded,
				encoded.contains("enforceMavenDeps"));
	}

	@Test(timeout = 30000)
	public void enforceMavenDepsDeserialises() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setEnforceMavenDependencies(true);
		String encoded = job.encode();

		ClaudeCodeJob restored = new ClaudeCodeJob();
		for (String part : encoded.split("::")) {
			int sep = part.indexOf(":=");
			if (sep > 0) {
				restored.set(part.substring(0, sep), part.substring(sep + 2));
			}
		}
		assertTrue(restored.isEnforceMavenDependencies());
	}

	// ── addEnforcementRule ───────────────────────────────────────────────────

	@Test(timeout = 30000)
	public void addEnforcementRuleAcceptsCustomRule() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "my-custom-rule"; }
			@Override
			public boolean isViolated(ClaudeCodeJob j) { return false; }
			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob j) { return "fix it"; }
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
			public boolean isViolated(ClaudeCodeJob j) { return false; }
			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob j) { return "fix it"; }
			@Override
			public int getMaxRetries() { return 2; }
		};
		assertEquals(2, rule.getMaxRetries());
	}

	// ── ClaudeCodeJobFactory — enforceMavenDependencies ─────────────────────

	@Test(timeout = 30000)
	public void factoryEnforceMavenDependenciesDefaultFalse() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
		assertFalse(factory.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void factorySetEnforceMavenDependenciesPropagatesToJob() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
		factory.setEnforceMavenDependencies(true);
		ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
		assertNotNull(job);
		assertTrue(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void factoryEnforceMavenDependenciesDefaultDoesNotPropagateTrue() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
		ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
		assertNotNull(job);
		assertFalse(job.isEnforceMavenDependencies());
	}

	@Test(timeout = 30000)
	public void factoryEnforceMavenDepsRoundTripViaEncode() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
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
			public boolean isViolated(ClaudeCodeJob job) { return false; }
			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix it"; }
		};
		// Default implementation must not throw and must have no visible side-effects.
		rule.onCorrectionAttempted(new ClaudeCodeJob("t1", "do something"));
	}

	@Test(timeout = 30000)
	public void onCorrectionAttemptedCanBeOverridden() {
		AtomicInteger callCount = new AtomicInteger();
		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "counting-rule"; }
			@Override
			public boolean isViolated(ClaudeCodeJob job) { return false; }
			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix it"; }
			@Override
			public void onCorrectionAttempted(ClaudeCodeJob job) { callCount.incrementAndGet(); }
		};
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
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
			public boolean isViolated(ClaudeCodeJob job) {
				if (resolved.get()) return false;
				Set<String> current = new LinkedHashSet<>(methodSet);
				lastSeen.set(current);
				callCount.incrementAndGet();
				return !current.isEmpty();
			}

			@Override
			public void onCorrectionAttempted(ClaudeCodeJob job) {
				Set<String> current = new LinkedHashSet<>(methodSet);
				if (lastSeen.get() != null && current.equals(lastSeen.get())) {
					resolved.set(true);
				}
			}

			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix it"; }
		};

		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");

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
			public boolean isViolated(ClaudeCodeJob job) {
				if (resolved.get()) return false;
				Set<String> current = new LinkedHashSet<>(methodList);
				lastSeen.set(current);
				boolean violated = !current.isEmpty();
				if (violated) violationCount.incrementAndGet();
				return violated;
			}

			@Override
			public void onCorrectionAttempted(ClaudeCodeJob job) {
				Set<String> current = new LinkedHashSet<>(methodList);
				if (lastSeen.get() != null && current.equals(lastSeen.get())) {
					resolved.set(true);
				}
			}

			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix it"; }
		};

		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");

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
	 * Verifies that {@link ClaudeCodeJob#runEnforcementRules()} passes
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
			public boolean isViolated(ClaudeCodeJob job) { return !done; }

			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) {
				done = true;
				return "correct the dependency violation";
			}
		};

		// Spy subclass: capture the activity argument without launching a subprocess.
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "test") {
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

	// ── Backward compatibility ───────────────────────────────────────────────

	@Test(timeout = 30000)
	public void enforceChangesStillFunctionsAfterRefactor() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		assertFalse(job.isEnforceChanges());

		job.setEnforceChanges(true);
		assertTrue(job.isEnforceChanges());
	}

	@Test(timeout = 30000)
	public void factoryDeduplicationModeDefaultIsLocal() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
		assertEquals(ClaudeCodeJob.DEDUP_LOCAL, factory.getDeduplicationMode());
	}

	// ── OrganizationalPlacementRule — flag defaults ──────────────────────────

	@Test(timeout = 30000)
	public void enforceOrganizationalPlacementDefaultTrue() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		assertTrue(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void setEnforceOrganizationalPlacementFalse() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		job.setEnforceOrganizationalPlacement(false);
		assertFalse(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void setEnforceOrganizationalPlacementRoundTrip() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		assertTrue(job.isEnforceOrganizationalPlacement());
		job.setEnforceOrganizationalPlacement(false);
		assertFalse(job.isEnforceOrganizationalPlacement());
		job.setEnforceOrganizationalPlacement(true);
		assertTrue(job.isEnforceOrganizationalPlacement());
	}

	// ── OrganizationalPlacementRule — serialisation ──────────────────────────

	@Test(timeout = 30000)
	public void enforceOrgPlacementAbsentInWireFormatWhenTrue() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		// default is true — should not appear in wire format
		String encoded = job.encode();
		assertNotNull(encoded);
		assertFalse("Did not expect enforceOrgPlacement in: " + encoded,
				encoded.contains("enforceOrgPlacement"));
	}

	@Test(timeout = 30000)
	public void enforceOrgPlacementAppearsInWireFormatWhenFalse() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setEnforceOrganizationalPlacement(false);
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Expected enforceOrgPlacement:=false in: " + encoded,
				encoded.contains("enforceOrgPlacement:=false"));
	}

	@Test(timeout = 30000)
	public void enforceOrgPlacementDeserialises() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setEnforceOrganizationalPlacement(false);
		String encoded = job.encode();

		ClaudeCodeJob restored = new ClaudeCodeJob();
		for (String part : encoded.split("::")) {
			int sep = part.indexOf(":=");
			if (sep > 0) {
				restored.set(part.substring(0, sep), part.substring(sep + 2));
			}
		}
		assertFalse(restored.isEnforceOrganizationalPlacement());
	}

	// ── OrganizationalPlacementRule — factory propagation ───────────────────

	@Test(timeout = 30000)
	public void factoryEnforceOrganizationalPlacementDefaultTrue() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
		assertTrue(factory.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void factorySetEnforceOrganizationalPlacementFalsePropagatesToJob() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
		factory.setEnforceOrganizationalPlacement(false);
		ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
		assertNotNull(job);
		assertFalse(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void factoryEnforceOrganizationalPlacementDefaultPropagatesToJob() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
		ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
		assertNotNull(job);
		assertTrue(job.isEnforceOrganizationalPlacement());
	}

	@Test(timeout = 30000)
	public void factoryEnforceOrgPlacementRoundTripViaSet() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
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
			public boolean isViolated(ClaudeCodeJob job) {
				// Violated only on the first check; clean afterwards
				return ruleACheckCount.incrementAndGet() <= 2;
			}

			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix A"; }

			@Override
			public void onCorrectionAttempted(ClaudeCodeJob job) {
				ruleACorrections.incrementAndGet();
			}
		};

		// Rule B: always clean — just counts how many times it is checked
		EnforcementRule ruleB = new EnforcementRule() {
			@Override
			public String getName() { return "rule-b"; }

			@Override
			public boolean isViolated(ClaudeCodeJob job) {
				ruleBChecks.incrementAndGet();
				return false;
			}

			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix B"; }
		};

		// Simulate one outer loop pass where rule A corrects and rule B passes
		// Pass 1: ruleA violated (outer if) → violated (while condition) → correction → resolved
		//         ruleB: not violated → skip
		// anyRuleCorrectionRan = true → restart
		// Pass 2: ruleA not violated → skip; ruleB not violated → skip
		// anyRuleCorrectionRan = false → exit

		boolean anyRuleCorrectionRan;
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
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
	 * {@code do-while} in {@link ClaudeCodeJob#runEnforcementRules()} reset
	 * the per-rule {@code attempts} counter every pass and looped forever.
	 *
	 * <p>The test uses a spy {@code runCorrectionSession} so no real Claude
	 * subprocess is launched.  It asserts the call count is bounded by
	 * {@link ClaudeCodeJob#DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS}.</p>
	 */
	@Test(timeout = 30000)
	public void runEnforcementRulesAbortsWhenTotalAttemptCapHit() {
		AtomicInteger correctionCalls = new AtomicInteger();

		EnforcementRule alwaysViolated = new EnforcementRule() {
			@Override
			public String getName() { return "test-always-violated"; }
			@Override
			public boolean isViolated(ClaudeCodeJob job) { return true; }
			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) {
				return "this will never resolve";
			}
		};

		ClaudeCodeJob job = new ClaudeCodeJob("t1", "test") {
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
		assertTrue("Expected attempts <= " + ClaudeCodeJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS
				+ ", got " + attempts,
				attempts <= ClaudeCodeJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS);
		assertEquals(ClaudeCodeJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS, attempts);
	}

	@Test(timeout = 30000)
	public void totalEnforcementAttemptCapDefaultValue() {
		// Pin the constant so silent regressions (e.g., raising the cap by an
		// order of magnitude) are caught.  25 is comfortably above the
		// 5-retries-per-rule × 4 built-in rules a healthy job could reach
		// once, and roughly 160× below the observed pathological case.
		assertEquals(25, ClaudeCodeJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS);
	}

	// ── PostCompletionCommandRule — unit tests ───────────────────────────────

	/**
	 * A rule wrapping a successful command (exit 0) must not be violated.
	 * No correction session should be triggered.
	 */
	@Test(timeout = 30000)
	public void postCompletionSuccessfulCommandNotViolated() {
		PostCompletionCommandRule rule = new PostCompletionCommandRule("true", null);
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
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
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
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
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
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
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");

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
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
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
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "test") {
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
				attempts <= ClaudeCodeJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS);
	}

	// ── PostCompletionCommandRule — ClaudeCodeJob integration ────────────────

	/**
	 * When no post-completion command is set, no PostCompletionCommandRule is
	 * added to the active rule list. This ensures the feature is purely additive.
	 */
	@Test(timeout = 30000)
	public void postCompletionRuleNotActiveWhenCommandEmpty() {
		AtomicInteger correctionCalls = new AtomicInteger();
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "test") {
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
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
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
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setPostCompletionCommand("mvn -pl flowtree test -Dtest=FooTest");
		String encoded = job.encode();
		assertNotNull(encoded);
		assertTrue("Wire format must contain postCmd", encoded.contains("postCmd:="));

		ClaudeCodeJob restored = new ClaudeCodeJob();
		for (String part : encoded.split("::")) {
			int sep = part.indexOf(":=");
			if (sep > 0) {
				restored.set(part.substring(0, sep), part.substring(sep + 2));
			}
		}
		assertEquals("mvn -pl flowtree test -Dtest=FooTest", restored.getPostCompletionCommand());
	}

	/**
	 * When no post-completion command is set, the wire format must not contain
	 * the {@code postCmd} key.
	 */
	@Test(timeout = 30000)
	public void postCompletionCommandAbsentFromWireFormatWhenEmpty() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		String encoded = job.encode();
		assertFalse("postCmd must not appear in wire format when unset",
				encoded.contains("postCmd"));
	}

	/**
	 * A non-default timeout round-trips through encode/set.
	 */
	@Test(timeout = 30000)
	public void postCompletionTimeoutRoundTripThroughEncodeDecode() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setPostCompletionCommand("make test");
		job.setPostCompletionTimeoutSeconds(300);
		String encoded = job.encode();
		assertTrue("Wire format must contain postCmdTimeout", encoded.contains("postCmdTimeout:=300"));

		ClaudeCodeJob restored = new ClaudeCodeJob();
		for (String part : encoded.split("::")) {
			int sep = part.indexOf(":=");
			if (sep > 0) {
				restored.set(part.substring(0, sep), part.substring(sep + 2));
			}
		}
		assertEquals(300, restored.getPostCompletionTimeoutSeconds());
	}

	/**
	 * The default timeout (1800 s) is NOT written to the wire format to keep it compact.
	 */
	@Test(timeout = 30000)
	public void postCompletionDefaultTimeoutAbsentFromWireFormat() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setPostCompletionCommand("make test");
		// Default timeout — should not appear in wire format
		String encoded = job.encode();
		assertFalse("Default timeout must not appear in wire format",
				encoded.contains("postCmdTimeout"));
	}

	// ── PostCompletionCommandRule — factory propagation ──────────────────────

	/**
	 * A post-completion command set on the factory propagates to jobs created
	 * by {@link ClaudeCodeJobFactory#nextJob()}.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionCommandPropagatesToJob() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
		factory.setPostCompletionCommand("mvn -pl flowtree test -Dtest=FooTest");
		ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
		assertNotNull(job);
		assertEquals("mvn -pl flowtree test -Dtest=FooTest", job.getPostCompletionCommand());
	}

	/**
	 * When no post-completion command is set on the factory, the job's command is null.
	 */
	@Test(timeout = 30000)
	public void factoryNoPostCompletionCommandJobHasNoCommand() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
		ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
		assertNotNull(job);
		assertNull(job.getPostCompletionCommand());
	}

	/**
	 * A factory with a post-completion command round-trips through the
	 * {@code set()} deserialization path.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionCommandRoundTripViaSet() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
		factory.setPostCompletionCommand("bash scripts/verify.sh");
		assertEquals("bash scripts/verify.sh", factory.getPostCompletionCommand());

		// Simulate wire deserialisation
		ClaudeCodeJobFactory restored = new ClaudeCodeJobFactory("prompt");
		restored.set("postCmd",
				GitManagedJob.base64Encode("bash scripts/verify.sh"));
		assertEquals("bash scripts/verify.sh", restored.getPostCompletionCommand());
	}

	/**
	 * A non-default timeout set on the factory propagates to the created job.
	 */
	@Test(timeout = 30000)
	public void factoryPostCompletionTimeoutPropagatesToJob() {
		ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
		factory.setPostCompletionCommand("mvn test");
		factory.setPostCompletionTimeoutSeconds(600);
		ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
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
}
