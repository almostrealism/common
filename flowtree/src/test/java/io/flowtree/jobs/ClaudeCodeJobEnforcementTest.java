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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
}
