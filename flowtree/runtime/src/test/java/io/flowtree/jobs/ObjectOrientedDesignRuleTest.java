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
 * class under review and asks for fixes rather than deferral.
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
			rule.onCorrectionAttempted(job);
			assertTrue(rule.isViolated(job));
			assertTrue(rule.buildCorrectionPrompt(job).startsWith("OBJECT-ORIENTED DESIGN REVIEW: " + second));
			rule.onCorrectionAttempted(job);
			assertFalse(rule.isViolated(job));

			changed.add(added);
			assertTrue(rule.isViolated(job));
			assertTrue(rule.buildCorrectionPrompt(job).startsWith("OBJECT-ORIENTED DESIGN REVIEW: " + added));
			rule.onCorrectionAttempted(job);
			assertFalse(rule.isViolated(job));
			assertEquals(List.of(first, second, added), new ArrayList<>(rule.getReviewed()));
		} finally {
			try (Stream<Path> paths = Files.walk(root)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			}
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
}
