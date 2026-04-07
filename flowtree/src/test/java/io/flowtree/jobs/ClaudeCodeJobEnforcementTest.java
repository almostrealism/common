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
	 * that removes no methods (same set returned on re-check), the rule must
	 * report no violation so the loop exits.
	 */
	@Test(timeout = 30000)
	public void methodSetComparisonExitsLoopWhenUnchanged() {
		// Simulate a rule whose "new methods" list does not change across calls.
		// This mimics a deduplication audit where the agent found no duplicates.
		AtomicInteger callCount = new AtomicInteger();
		Set<String> methodSet = new LinkedHashSet<>();
		methodSet.add("myMethod");

		// Track the last-seen method set to replicate the comparison logic.
		Set<String>[] lastSeen = new Set[]{null};

		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "method-set-test"; }

			@Override
			public boolean isViolated(ClaudeCodeJob job) {
				Set<String> current = new LinkedHashSet<>(methodSet);
				if (lastSeen[0] != null && current.equals(lastSeen[0])) {
					return false;
				}
				lastSeen[0] = current;
				callCount.incrementAndGet();
				return !current.isEmpty();
			}

			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix it"; }
		};

		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");

		// First call: method set is recorded and violation is reported.
		assertTrue(rule.isViolated(job));
		// Second call with same method set: agent changed nothing — exit condition met.
		assertFalse(rule.isViolated(job));
		// Call count should be 1 (only the initial recording increments it).
		assertEquals(1, callCount.get());
	}

	@Test(timeout = 30000)
	public void methodSetComparisonContinuesLoopWhenMethodsRemoved() {
		// Simulate a deduplication pass that removes one method, then finds no more.
		List<String> methodList = new ArrayList<>();
		methodList.add("methodA");
		methodList.add("methodB");

		Set<String>[] lastSeen = new Set[]{null};
		AtomicInteger violationCount = new AtomicInteger();

		EnforcementRule rule = new EnforcementRule() {
			@Override
			public String getName() { return "method-set-removal-test"; }

			@Override
			public boolean isViolated(ClaudeCodeJob job) {
				Set<String> current = new LinkedHashSet<>(methodList);
				if (lastSeen[0] != null && current.equals(lastSeen[0])) {
					return false;
				}
				lastSeen[0] = current;
				boolean violated = !current.isEmpty();
				if (violated) violationCount.incrementAndGet();
				return violated;
			}

			@Override
			public String buildCorrectionPrompt(ClaudeCodeJob job) { return "fix it"; }
		};

		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");

		// First check: both methods present.
		assertTrue(rule.isViolated(job));
		// Simulate agent removing one method.
		methodList.remove("methodB");
		// Second check: method set changed — loop should continue.
		assertTrue(rule.isViolated(job));
		// Third check: method set unchanged — loop should exit.
		assertFalse(rule.isViolated(job));
		assertEquals(2, violationCount.get());
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
