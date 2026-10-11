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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests of {@link ObjectOrientedDesignRule}: one review session per changed production class,
 * in order, with deleted files, tests and non-Java files skipped, and a prompt that names the
 * class under review and asks for fixes rather than deferral. Also covers the per-job
 * {@link CodingAgentJob#isObjectOrientedReviewEnabled()} switch that activates the rule, which
 * is off by default and travels over the wire.
 */
public class ObjectOrientedDesignRuleTest extends TestSuiteBase {

	/** A {@link CodingAgentJob} whose changed-file scan returns a fixed, mutable list. */
	private static final class StubJob extends CodingAgentJob {
		/** The changed paths returned by {@link #extractChangedFilePaths()}. */
		private final List<String> changed;

		/**
		 * Creates a job over a working directory with the given changed paths.
		 *
		 * @param workingDirectory the working directory
		 * @param changed          the changed paths
		 */
		StubJob(Path workingDirectory, List<String> changed) {
			super("t1", "p");
			this.changed = changed;
			setWorkingDirectory(workingDirectory.toString());
		}

		@Override
		List<String> extractChangedFilePaths() { return changed; }
	}

	/**
	 * Each changed production class gets exactly one session, in the reported order; test
	 * sources, non-Java files and changed paths whose file no longer exists are skipped, and a
	 * class added by an earlier review session is reviewed in turn.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void reviewsEachChangedProductionClassOnce() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String first = "engine/ml/src/main/java/org/example/Window.java";
			String second = "src/main/java/org/example/Model.java";
			String added = "engine/ml/src/main/java/org/example/Config.java";
			for (String path : List.of(first, second, added, "engine/ml/src/test/java/org/example/WindowTest.java",
					"engine/ml/src/main/java/org/example/package-info.java", "docs/notes.md")) {
				Files.createDirectories(root.resolve(path).getParent());
				Files.writeString(root.resolve(path), "class X {}");
			}

			List<String> changed = new ArrayList<>(List.of(first,
					"engine/ml/src/test/java/org/example/WindowTest.java",
					"engine/ml/src/main/java/org/example/Deleted.java",
					"engine/ml/src/main/java/org/example/package-info.java",
					"docs/notes.md", second));
			StubJob job = new StubJob(root, changed);
			ObjectOrientedDesignRule rule = new ObjectOrientedDesignRule();

			assertTrue(rule.isViolated(job));
			assertTrue(rule.buildCorrectionPrompt(job).startsWith("OBJECT-ORIENTED DESIGN REVIEW: " + first));
			assertTrue(rule.getReviewed().isEmpty());
			appendVerdict(root, first + ": CLEAN");
			rule.onCorrectionAttempted(job);
			assertTrue(rule.isViolated(job));
			assertTrue(rule.buildCorrectionPrompt(job).startsWith("OBJECT-ORIENTED DESIGN REVIEW: " + second));
			appendVerdict(root, second + ": FIXED - made it extend Model");
			rule.onCorrectionAttempted(job);
			assertFalse(rule.isViolated(job));

			changed.add(added);
			assertTrue(rule.isViolated(job));
			assertTrue(rule.buildCorrectionPrompt(job).startsWith("OBJECT-ORIENTED DESIGN REVIEW: " + added));
			appendVerdict(root, added + ": CLEAN");
			rule.onCorrectionAttempted(job);
			assertFalse(rule.isViolated(job));
			assertEquals(List.of(first, second, added), new ArrayList<>(rule.getReviewed()));
		} finally {
			delete(root);
		}
	}

	/**
	 * A session that records no verdict for its class, or a verdict for a different class, or a
	 * line that is not a verdict, leaves the class unreviewed, so the next session reviews the
	 * same class again; once its verdict is recorded the class counts as reviewed.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void classWithoutVerdictIsReviewedAgain() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String target = "engine/ml/src/main/java/org/example/Window.java";
			String other = "engine/ml/src/main/java/org/example/Other.java";
			Files.createDirectories(root.resolve(target).getParent());
			Files.writeString(root.resolve(target), "class Window {}");
			StubJob job = new StubJob(root, new ArrayList<>(List.of(target)));
			ObjectOrientedDesignRule rule = new ObjectOrientedDesignRule();

			assertTrue(rule.buildCorrectionPrompt(job).startsWith("OBJECT-ORIENTED DESIGN REVIEW: " + target));
			rule.onCorrectionAttempted(job);
			assertTrue("a session without a verdict file counted as a review", rule.getReviewed().isEmpty());
			assertTrue(rule.isViolated(job));

			appendVerdict(root, other + ": CLEAN");
			appendVerdict(root, target + ": reviewing");
			assertTrue(rule.buildCorrectionPrompt(job).startsWith("OBJECT-ORIENTED DESIGN REVIEW: " + target));
			rule.onCorrectionAttempted(job);
			assertTrue("a verdict for another class, or a non-verdict line, counted as a review",
					rule.getReviewed().isEmpty());
			assertTrue(rule.isViolated(job));

			appendVerdict(root, "  " + target + ": CLEAN");
			rule.buildCorrectionPrompt(job);
			rule.onCorrectionAttempted(job);
			assertEquals(Set.of(target), rule.getReviewed());
			assertFalse(rule.isViolated(job));
		} finally {
			delete(root);
		}
	}

	/**
	 * Appends a line to the verdict file of a working tree, as a review session does.
	 *
	 * @param root the working tree
	 * @param line the line to append
	 * @throws IOException if the file cannot be written
	 */
	private void appendVerdict(Path root, String line) throws IOException {
		Path verdicts = root.resolve(ObjectOrientedDesignRule.VERDICT_PATH);
		Files.createDirectories(verdicts.getParent());
		Files.writeString(verdicts, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/**
	 * Deletes a temporary tree.
	 *
	 * @param root the tree to delete
	 * @throws IOException if the tree cannot be walked
	 */
	private void delete(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
	}

	/** A job without a working directory has nothing to review. */
	@Test(timeout = 30000)
	public void noWorkingDirectoryIsNotViolated() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		assertFalse(new ObjectOrientedDesignRule().isViolated(job));
	}

	/**
	 * The verdict file is the rule's progress path, so a review that finds a class sound is
	 * not counted as a session that made no progress, and the per-pass cap is the runner's
	 * ceiling, so remaining classes continue in a later pass.
	 */
	@Test(timeout = 30000)
	public void verdictFileIsProgressAndCapIsRunnerCeiling() {
		ObjectOrientedDesignRule rule = new ObjectOrientedDesignRule();
		assertEquals("object-oriented-design", rule.getName());
		assertEquals(Set.of(ObjectOrientedDesignRule.VERDICT_PATH), rule.getProgressPaths());
		assertEquals(CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES, rule.getMaxRetries());
	}

	/**
	 * The prompt names the class under review, lists the other changed classes for context,
	 * covers the violations the owner has rejected, demands fixes rather than deferral, and
	 * asks for the verdict line.
	 */
	@Test(timeout = 30000)
	public void promptDemandsFixesForOneClass() {
		String target = "engine/ml/src/main/java/org/example/Window.java";
		String other = "engine/ml/src/main/java/org/example/Model.java";
		String prompt = new ObjectOrientedDesignRule().buildReviewPrompt(target, List.of(target, other), "develop");

		assertTrue(prompt.contains("This session reviews ONE class: " + target));
		assertTrue(prompt.contains("  " + other + "\n"));
		assertTrue(prompt.contains("git diff origin/develop -- " + target));
		assertFalse(prompt.contains("  " + target + "\n"));
		assertTrue(prompt.contains("IDENTITY"));
		assertTrue(prompt.contains("OWN YOUR COLLABORATORS"));
		assertTrue(prompt.contains("NO REACHING INTO OBJECTS"));
		assertTrue(prompt.contains("SUBCLASS, DON'T WIRE LAMBDAS"));
		assertTrue(prompt.contains("Do not defer"));
		assertTrue(prompt.contains(ObjectOrientedDesignRule.VERDICT_PATH));
		assertTrue(prompt.contains(target + ": CLEAN"));
	}

	/**
	 * Reports whether the runner for the given job assembles an {@link ObjectOrientedDesignRule}.
	 *
	 * @param job the job whose active rules are inspected
	 * @return {@code true} if the rule is among the job's active enforcement rules
	 */
	private static boolean hasObjectOrientedRule(CodingAgentJob job) {
		return new EnforcementRunner(job).buildActiveRules().stream()
				.anyMatch(rule -> rule instanceof ObjectOrientedDesignRule);
	}

	/** With review enabled and the flag left at its default, the rule is not assembled. */
	@Test(timeout = 30000)
	public void ruleAbsentByDefaultWithReviewEnabled() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		assertTrue(job.isReviewEnabled());
		assertFalse(job.isObjectOrientedReviewEnabled());
		assertFalse(hasObjectOrientedRule(job));
	}

	/** Opting in while review is enabled assembles the rule after the content rules, before staging checks. */
	@Test(timeout = 30000)
	public void rulePresentWhenFlagSetAndReviewEnabled() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setTargetBranch("feature/test");
		job.setObjectOrientedReviewEnabled(true);

		List<EnforcementRule> rules = new EnforcementRunner(job).buildActiveRules();
		int review = -1;
		int oop = -1;
		int staging = -1;
		for (int i = 0; i < rules.size(); i++) {
			if (rules.get(i) instanceof ReviewRule) review = i;
			if (rules.get(i) instanceof ObjectOrientedDesignRule) oop = i;
			if (rules.get(i) instanceof StagingSkipRule) staging = i;
		}
		assertTrue("Expected ObjectOrientedDesignRule in " + rules, oop >= 0);
		assertTrue("ObjectOrientedDesignRule must follow ReviewRule", review >= 0 && review < oop);
		assertTrue("ObjectOrientedDesignRule must precede StagingSkipRule", oop < staging);
	}

	/** Opting in has no effect when review is disabled. */
	@Test(timeout = 30000)
	public void ruleAbsentWhenFlagSetButReviewDisabled() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setObjectOrientedReviewEnabled(true);
		job.setReviewEnabled(false);
		assertFalse(hasObjectOrientedRule(job));
	}

	/** The job codec round-trips the opt-in and omits the key when the flag is at its default. */
	@Test(timeout = 30000)
	public void jobCodecRoundTripsFlag() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		assertFalse(job.encode().contains("objectOrientedReviewEnabled"));
		assertFalse(GitManagedJobSerializationTest.roundTrip(job).isObjectOrientedReviewEnabled());

		job.setObjectOrientedReviewEnabled(true);
		assertTrue(job.encode().contains("::objectOrientedReviewEnabled:=true"));
		assertTrue(GitManagedJobSerializationTest.roundTrip(job).isObjectOrientedReviewEnabled());
	}

	/** A factory decoded without the field, as from an older submission, leaves the rule off. */
	@Test(timeout = 30000)
	public void missingFieldDecodesAsOff() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		assertFalse(factory.encode().contains("objectOrientedReviewEnabled"));
		CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
		assertFalse(decoded.isObjectOrientedReviewEnabled());
		assertFalse(((CodingAgentJob) decoded.nextJob()).isObjectOrientedReviewEnabled());
	}

	/** A factory that opts in survives the wire and hands the opt-in to the jobs it creates. */
	@Test(timeout = 30000)
	public void factoryOptInRoundTripsAndPropagates() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setObjectOrientedReviewEnabled(true);
		CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
		assertTrue(decoded.isObjectOrientedReviewEnabled());
		assertTrue(((CodingAgentJob) decoded.nextJob()).isObjectOrientedReviewEnabled());
	}
}
