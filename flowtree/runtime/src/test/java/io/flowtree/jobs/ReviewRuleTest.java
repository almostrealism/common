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

import io.flowtree.jobs.agent.Phase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ReviewRule}, the encode/decode round-trip of the
 * review configuration fields on {@link CodingAgentJob}/
 * {@link CodingAgentJobFactory}, and the {@link Phase#REVIEW} wire mappings.
 */
public class ReviewRuleTest extends TestSuiteBase {

	/** A {@link CodingAgentJob} that stubs the new-file scan to a fixed list. */
	private static final class StubJob extends CodingAgentJob {
		private final List<String> newFiles;
		private final boolean dirty;

		StubJob(List<String> newFiles, boolean dirty) {
			super("t1", "p");
			this.newFiles = newFiles;
			this.dirty = dirty;
		}

		@Override
		List<String> extractNewFilePaths() { return newFiles; }

		@Override
		boolean hasUncommittedChanges() { return dirty; }
	}

	@Test(timeout = 30000)
	public void getNameReturnsReview() {
		assertEquals("review", new ReviewRule().getName());
	}

	@Test(timeout = 30000)
	public void defaultMaxPassesIsOne() {
		assertEquals(1, ReviewRule.DEFAULT_MAX_PASSES);
		assertEquals(1, new ReviewRule().getMaxRetries());
		assertEquals(1, CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES);
	}

	@Test(timeout = 30000)
	public void maxRetriesRespectsConstructorOverride() {
		assertEquals(3, new ReviewRule(3).getMaxRetries());
	}

	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void constructorRejectsZero() {
		new ReviewRule(0);
	}

	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void constructorRejectsNegative() {
		new ReviewRule(-1);
	}

	@Test(timeout = 30000)
	public void isViolatedTrueWithNewFiles() {
		ReviewRule rule = new ReviewRule();
		assertTrue(rule.isViolated(new StubJob(List.of("foo/Bar.java"), false)));
	}

	@Test(timeout = 30000)
	public void isViolatedTrueWithUncommittedChanges() {
		ReviewRule rule = new ReviewRule();
		assertTrue(rule.isViolated(new StubJob(new ArrayList<>(), true)));
	}

	@Test(timeout = 30000)
	public void isViolatedFalseOnCleanTree() {
		ReviewRule rule = new ReviewRule();
		assertFalse(rule.isViolated(new StubJob(new ArrayList<>(), false)));
	}

	@Test(timeout = 30000)
	public void isViolatedFalseAfterFirstRun() {
		ReviewRule rule = new ReviewRule();
		StubJob job = new StubJob(List.of("foo/Bar.java"), true);
		assertTrue(rule.isViolated(job));
		rule.onCorrectionAttempted(job);
		// One-shot contract: after one correction attempt the rule reports satisfied
		// regardless of whether the working tree still has changes.
		assertFalse(rule.isViolated(job));
	}

	@Test(timeout = 30000)
	public void buildCorrectionPromptReturnsNonNull() {
		ReviewRule rule = new ReviewRule();
		StubJob job = new StubJob(List.of("foo/Bar.java"), false);
		String prompt = rule.buildCorrectionPrompt(job);
		assertNotNull(prompt);
		assertTrue("Prompt must include the role header",
				prompt.contains("SECOND-PASS REVIEW"));
		assertTrue("Prompt must include the bias statement",
				prompt.contains("When in doubt, DEFER"));
		assertTrue("Prompt must include the DO directly section",
				prompt.contains("FIXES YOU MAY MAKE DIRECTLY"));
		assertTrue("Prompt must include the DEFER section",
				prompt.contains("ISSUES YOU MUST DEFER"));
		assertTrue("Prompt must include the memory_store template",
				prompt.contains("memory_store"));
		assertTrue("Prompt must include the review-followup tag",
				prompt.contains("review-followup"));
		assertTrue("Prompt must include the TODO(review) template",
				prompt.contains("TODO(review)"));
		assertTrue("Prompt must include the forbidden actions block",
				prompt.contains("FORBIDDEN ACTIONS"));
		assertTrue("Prompt must include the diff section header",
				prompt.contains("DIFF"));
	}

	// ── Phase wire mapping ──────────────────────────────────────────────────

	@Test(timeout = 30000)
	public void phaseReviewExists() {
		assertEquals(Phase.REVIEW, Phase.fromWireName("review"));
	}

	@Test(timeout = 30000)
	public void fromRuleNameReviewResolvesToPhaseReview() {
		assertEquals(Phase.REVIEW, Phase.fromRuleName("review"));
	}

	@Test(timeout = 30000)
	public void reviewPhaseWireName() {
		assertEquals("review", Phase.REVIEW.wireName());
	}

	// ── reviewEnabled / maxReviewPasses on CodingAgentJob ───────────────────

	@Test(timeout = 30000)
	public void reviewEnabledDefaultIsTrue() {
		assertTrue(new CodingAgentJob("t1", "p").isReviewEnabled());
	}

	@Test(timeout = 30000)
	public void maxReviewPassesDefaultIsOne() {
		assertEquals(1, new CodingAgentJob("t1", "p").getMaxReviewPasses());
	}

	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void setMaxReviewPassesRejectsZero() {
		new CodingAgentJob("t1", "p").setMaxReviewPasses(0);
	}

	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void setMaxReviewPassesRejectsNegative() {
		new CodingAgentJob("t1", "p").setMaxReviewPasses(-2);
	}

	// ── Wire format — defaults absent ───────────────────────────────────────

	@Test(timeout = 30000)
	public void reviewEnabledAbsentFromWireFormatWhenTrue() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		String encoded = job.encode();
		assertFalse("reviewEnabled must not appear when default (true): " + encoded,
				encoded.contains("reviewEnabled"));
	}

	@Test(timeout = 30000)
	public void reviewEnabledAppearsInWireFormatWhenFalse() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setReviewEnabled(false);
		String encoded = job.encode();
		assertTrue("Expected reviewEnabled:=false in: " + encoded,
				encoded.contains("reviewEnabled:=false"));
	}

	@Test(timeout = 30000)
	public void maxReviewPassesAbsentFromWireFormatWhenDefault() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		String encoded = job.encode();
		assertFalse("maxReviewPasses must not appear when default: " + encoded,
				encoded.contains("maxReviewPasses"));
	}

	@Test(timeout = 30000)
	public void maxReviewPassesAppearsInWireFormatWhenOverridden() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setMaxReviewPasses(3);
		String encoded = job.encode();
		assertTrue("Expected maxReviewPasses:=3 in: " + encoded,
				encoded.contains("maxReviewPasses:=3"));
	}

	@Test(timeout = 30000)
	public void reviewEnabledRoundTripViaSet() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.set("reviewEnabled", "false");
		assertFalse(job.isReviewEnabled());
		job.set("reviewEnabled", "true");
		assertTrue(job.isReviewEnabled());
	}

	@Test(timeout = 30000)
	public void maxReviewPassesRoundTripViaSet() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.set("maxReviewPasses", "4");
		assertEquals(4, job.getMaxReviewPasses());
	}

	@Test(timeout = 30000)
	public void maxReviewPassesDeserializationFallsBackOnInvalidString() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.set("maxReviewPasses", "notanumber");
		assertEquals(CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES, job.getMaxReviewPasses());
	}

	@Test(timeout = 30000)
	public void maxReviewPassesDeserializationFallsBackOnNonPositive() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.set("maxReviewPasses", "0");
		assertEquals(CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES, job.getMaxReviewPasses());
		job.set("maxReviewPasses", "-3");
		assertEquals(CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES, job.getMaxReviewPasses());
	}

	@Test(timeout = 30000)
	public void encodeDecodeRoundTrip() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setReviewEnabled(false);
		job.setMaxReviewPasses(3);
		CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
		assertFalse(restored.isReviewEnabled());
		assertEquals(3, restored.getMaxReviewPasses());
	}

	// ── Factory propagation ────────────────────────────────────────────────

	@Test(timeout = 30000)
	public void factoryReviewEnabledDefaultTrue() {
		assertTrue(new CodingAgentJobFactory("prompt").isReviewEnabled());
	}

	@Test(timeout = 30000)
	public void factoryReviewEnabledPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
		factory.setReviewEnabled(false);
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertFalse(job.isReviewEnabled());
	}

	@Test(timeout = 30000)
	public void factoryMaxReviewPassesPropagatesToJob() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
		factory.setMaxReviewPasses(2);
		CodingAgentJob job = (CodingAgentJob) factory.nextJob();
		assertNotNull(job);
		assertEquals(2, job.getMaxReviewPasses());
	}

	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void factorySetMaxReviewPassesRejectsZero() {
		new CodingAgentJobFactory("p").setMaxReviewPasses(0);
	}

	@Test(timeout = 30000)
	public void factoryReviewEnabledRoundTripViaSet() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
		factory.set("reviewEnabled", "false");
		assertFalse(factory.isReviewEnabled());
	}

	@Test(timeout = 30000)
	public void factoryMaxReviewPassesRoundTripViaSet() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
		factory.set("maxReviewPasses", "4");
		assertEquals(4, factory.getMaxReviewPasses());
		factory.set("maxReviewPasses", "");
		assertEquals(CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES, factory.getMaxReviewPasses());
	}
}
