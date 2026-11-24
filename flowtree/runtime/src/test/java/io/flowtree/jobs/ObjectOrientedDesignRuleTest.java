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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests of {@link ObjectOrientedDesignRule}: one review session per job over the changed production
 * classes the {@link ObjectOrientedRiskGate} flags and the {@link ObjectOrientedReviewLedger} has
 * not already settled, with per-class verdicts read from the repository root or a module directory,
 * the primary commit message preserved across the session, and verdicts persisted across jobs. Also
 * covers the per-job {@link CodingAgentJob#isObjectOrientedReviewEnabled()} switch, which is on by
 * default and travels over the wire.
 */
public class ObjectOrientedDesignRuleTest extends TestSuiteBase {

	/** A {@link CodingAgentJob} whose changed-file scan returns a fixed, mutable list. */
	private static final class StubJob extends CodingAgentJob {
		/** The changed paths returned by {@link #extractChangedFilePaths()}. */
		private final List<String> changed;
		/** The JSON bodies of every harness status message the job posted. */
		private final List<String> posts = new ArrayList<>();
		/** A status reporter that records into {@link #posts} instead of reaching a controller. */
		private final HarnessStatusReporter reporter =
				new HarnessStatusReporter("http://controller/api/workstreams/ws-1", (url, body) -> posts.add(body));

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

		@Override
		HarnessStatusReporter harnessStatus() { return reporter; }

		/**
		 * Returns whether any posted status message contains the given text.
		 *
		 * @param text the text to look for
		 * @return whether a posted message contains it
		 */
		boolean posted(String text) { return posts.stream().anyMatch(body -> body.contains(text)); }
	}

	/**
	 * A rule whose git-backed inspection and ledger are replaced by in-memory fakes, so selection,
	 * prompt-building and verdict-recording can be tested without a git working tree.
	 */
	private static final class FakeRule extends ObjectOrientedDesignRule {
		/** The design signals each path's inspection reports. */
		private final Map<String, EnumSet<ObjectOrientedRiskGate.Signal>> signals;
		/** The git blob id each path's inspection reports. */
		private final Map<String, String> blobs;
		/** The lines the branch added to each path, as its inspection reports. */
		private final Map<String, Integer> added;
		/** The ledger every inspection and recording uses. */
		private final ObjectOrientedReviewLedger ledger;
		/** The working tree's changed-line counts to report, consumed one per call; the last repeats. */
		private final List<Integer> lineCounts;

		/**
		 * @param ledgerFile the backing file for the shared ledger
		 */
		FakeRule(Path ledgerFile) {
			this.signals = new HashMap<>();
			this.blobs = new HashMap<>();
			this.added = new HashMap<>();
			this.ledger = new ObjectOrientedReviewLedger(ledgerFile);
			this.lineCounts = new ArrayList<>(List.of(0));
		}

		/**
		 * Declares how many lines the branch added to a path.
		 *
		 * @param path  the class path
		 * @param lines the added-line count
		 * @return this rule, for chaining
		 */
		FakeRule addedLines(String path, int lines) {
			added.put(path, lines);
			return this;
		}

		/**
		 * Declares the working tree's changed-line counts, one per measurement.
		 *
		 * @param counts the counts in measurement order
		 * @return this rule, for chaining
		 */
		FakeRule lineCounts(Integer... counts) {
			lineCounts.clear();
			lineCounts.addAll(List.of(counts));
			return this;
		}

		/**
		 * Simulates a review session editing a class: its content now hashes to another blob id.
		 *
		 * @param path the class path
		 * @param blob the blob id of the edited content
		 */
		void edit(String path, String blob) { blobs.put(path, blob); }

		@Override
		String blobId(CodingAgentJob job, String path) { return blobs.getOrDefault(path, "blob-" + path); }

		@Override
		int changedLineCount(CodingAgentJob job) {
			return lineCounts.size() > 1 ? lineCounts.remove(0) : lineCounts.get(0);
		}

		/**
		 * Declares the signals and blob id a path's inspection reports.
		 *
		 * @param path   the class path
		 * @param blob   the blob id to report
		 * @param fired  the signals to report
		 * @return this rule, for chaining
		 */
		FakeRule with(String path, String blob, ObjectOrientedRiskGate.Signal... fired) {
			blobs.put(path, blob);
			EnumSet<ObjectOrientedRiskGate.Signal> set = EnumSet.noneOf(ObjectOrientedRiskGate.Signal.class);
			for (ObjectOrientedRiskGate.Signal signal : fired) set.add(signal);
			signals.put(path, set);
			return this;
		}

		@Override
		Candidate inspect(CodingAgentJob job, String path, String base) {
			EnumSet<ObjectOrientedRiskGate.Signal> fired =
					signals.getOrDefault(path, EnumSet.noneOf(ObjectOrientedRiskGate.Signal.class));
			return new Candidate(path, blobId(job, path), fired, added.getOrDefault(path, 0));
		}

		@Override
		ObjectOrientedReviewLedger ledger(CodingAgentJob job) { return ledger; }
	}

	/**
	 * Every flagged, unsettled class is reviewed together in a single session: one prompt names
	 * them all, and once every one has a verdict the rule is resolved. A changed class the gate does
	 * not flag is never selected.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void reviewsFlaggedClassesInOneSession() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String flaggedA = "engine/ml/src/main/java/org/example/Window.java";
			String flaggedB = "src/main/java/org/example/ModelHolder.java";
			String unflagged = "engine/ml/src/main/java/org/example/Plain.java";
			for (String path : List.of(flaggedA, flaggedB, unflagged)) {
				Files.createDirectories(root.resolve(path).getParent());
				Files.writeString(root.resolve(path), "class X {}");
			}
			StubJob job = new StubJob(root, new ArrayList<>(List.of(flaggedA, unflagged, flaggedB)));
			FakeRule rule = new FakeRule(root.resolve("ledger.tsv"))
					.with(flaggedA, "a1", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(flaggedB, "b1", ObjectOrientedRiskGate.Signal.SUSPICIOUS_NAME)
					.with(unflagged, "p1");

			assertTrue(rule.isViolated(job));
			String prompt = rule.buildCorrectionPrompt(job);
			assertTrue(prompt.contains(flaggedA));
			assertTrue(prompt.contains(flaggedB));
			assertTrue(prompt.contains(ObjectOrientedRiskGate.Signal.NEW_CLASS.description()));
			assertTrue(prompt.contains(ObjectOrientedRiskGate.Signal.SUSPICIOUS_NAME.description()));
			assertFalse("an unflagged class must not be reviewed", prompt.contains(unflagged));

			appendVerdict(root, VERDICT_FILE, flaggedA + ": CLEAN");
			appendVerdict(root, VERDICT_FILE, flaggedB + ": FIXED - made it a factory");
			rule.onCorrectionAttempted(job);

			assertEquals(Set.of(flaggedA, flaggedB), rule.getReviewed());
			assertFalse(rule.isViolated(job));
		} finally {
			delete(root);
		}
	}

	/**
	 * Only existing production classes are selected: a changed test source, a {@code package-info} or
	 * {@code module-info} descriptor, a non-Java file, and a changed path whose file no longer exists
	 * are all excluded before the gate is even consulted, so a session is never spent on them.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void skipsTestSourcesDescriptorsNonJavaAndDeletedFiles() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String production = "engine/ml/src/main/java/org/example/Window.java";
			String testSource = "engine/ml/src/test/java/org/example/WindowTest.java";
			String packageInfo = "engine/ml/src/main/java/org/example/package-info.java";
			String moduleInfo = "engine/ml/src/main/java/module-info.java";
			String nonJava = "docs/notes.md";
			String deleted = "engine/ml/src/main/java/org/example/Deleted.java";
			for (String path : List.of(production, testSource, packageInfo, moduleInfo, nonJava)) {
				Files.createDirectories(root.resolve(path).getParent());
				Files.writeString(root.resolve(path), "class X {}");
			}
			StubJob job = new StubJob(root, new ArrayList<>(
					List.of(testSource, packageInfo, moduleInfo, nonJava, deleted, production)));
			FakeRule rule = new FakeRule(root.resolve("ledger.tsv"))
					.with(production, "w1", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(testSource, "t1", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(packageInfo, "pi", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(moduleInfo, "mi", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(nonJava, "nj", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(deleted, "dl", ObjectOrientedRiskGate.Signal.NEW_CLASS);

			assertTrue(rule.isViolated(job));
			String prompt = rule.buildCorrectionPrompt(job);
			assertTrue("the existing production class must be reviewed", prompt.contains(production));
			assertFalse("a test source must not be reviewed", prompt.contains(testSource));
			assertFalse("a package-info descriptor must not be reviewed", prompt.contains(packageInfo));
			assertFalse("a module-info descriptor must not be reviewed", prompt.contains(moduleInfo));
			assertFalse("a non-Java file must not be reviewed", prompt.contains(nonJava));
			assertFalse("a deleted file must not be reviewed", prompt.contains(deleted));

			appendVerdict(root, VERDICT_FILE, production + ": CLEAN");
			rule.onCorrectionAttempted(job);

			assertEquals(Set.of(production), rule.getReviewed());
			assertFalse(rule.isViolated(job));
		} finally {
			delete(root);
		}
	}

	/**
	 * A verdict a session writes into a module's {@code .flowtree/oop-review.md} — not the
	 * repository root — is still found, so the class is not re-reviewed.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void verdictFoundInModuleDirectory() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String target = "engine/ml/src/main/java/org/example/Window.java";
			Files.createDirectories(root.resolve(target).getParent());
			Files.writeString(root.resolve(target), "class Window {}");
			StubJob job = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule rule = new FakeRule(root.resolve("ledger.tsv"))
					.with(target, "w1", ObjectOrientedRiskGate.Signal.NEW_CLASS);

			rule.buildCorrectionPrompt(job);
			appendVerdict(root, "engine/ml/" + VERDICT_FILE, target + ": CLEAN");
			rule.onCorrectionAttempted(job);

			assertEquals(Set.of(target), rule.getReviewed());
			assertFalse(rule.isViolated(job));
		} finally {
			delete(root);
		}
	}

	/**
	 * A class whose session records no verdict stays unreviewed, so a later session reviews it
	 * again; a verdict only for another class does not count.
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
			FakeRule rule = new FakeRule(root.resolve("ledger.tsv"))
					.with(target, "w1", ObjectOrientedRiskGate.Signal.NEW_CLASS);

			rule.buildCorrectionPrompt(job);
			rule.onCorrectionAttempted(job);
			assertTrue("a session without a verdict counted as a review", rule.getReviewed().isEmpty());
			assertTrue(rule.isViolated(job));

			rule.buildCorrectionPrompt(job);
			appendVerdict(root, VERDICT_FILE, other + ": CLEAN");
			rule.onCorrectionAttempted(job);
			assertTrue("a verdict for another class counted as a review", rule.getReviewed().isEmpty());

			rule.buildCorrectionPrompt(job);
			appendVerdict(root, VERDICT_FILE, target + ": CLEAN");
			rule.onCorrectionAttempted(job);
			assertEquals(Set.of(target), rule.getReviewed());
			assertFalse(rule.isViolated(job));
		} finally {
			delete(root);
		}
	}

	/**
	 * The review session must not own the commit message: whatever it writes to {@code commit.txt}
	 * is replaced with the primary session's message after the session.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void primaryCommitMessageSurvivesReview() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String target = "engine/ml/src/main/java/org/example/Window.java";
			Files.createDirectories(root.resolve(target).getParent());
			Files.writeString(root.resolve(target), "class Window {}");
			Files.writeString(root.resolve("commit.txt"), "Add the sliding window layer");
			StubJob job = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule rule = new FakeRule(root.resolve("ledger.tsv"))
					.with(target, "w1", ObjectOrientedRiskGate.Signal.NEW_CLASS);

			rule.buildCorrectionPrompt(job);
			// Simulate the review session rewriting commit.txt to describe its own verdict.
			Files.writeString(root.resolve("commit.txt"), "Record OOP verdict: Window CLEAN");
			appendVerdict(root, VERDICT_FILE, target + ": CLEAN");
			rule.onCorrectionAttempted(job);

			assertEquals("Add the sliding window layer",
					Files.readString(root.resolve("commit.txt"), StandardCharsets.UTF_8));
		} finally {
			delete(root);
		}
	}

	/**
	 * A verdict recorded in one job's ledger skips the unchanged class in a later job; editing the
	 * class (a new blob id) brings it back for review.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void verdictPersistsAcrossJobsUntilContentChanges() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String target = "engine/ml/src/main/java/org/example/Window.java";
			Files.createDirectories(root.resolve(target).getParent());
			Files.writeString(root.resolve(target), "class Window {}");
			Path ledger = root.resolve("ledger.tsv");

			StubJob firstJob = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule firstRule = new FakeRule(ledger)
					.with(target, "blobA", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			firstRule.buildCorrectionPrompt(firstJob);
			appendVerdict(root, VERDICT_FILE, target + ": CLEAN");
			firstRule.onCorrectionAttempted(firstJob);
			assertTrue(firstRule.getReviewed().contains(target));

			// A later job, same content (blobA): already settled, nothing to review.
			StubJob sameContent = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule sameRule = new FakeRule(ledger)
					.with(target, "blobA", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			assertFalse("an unchanged, already-cleared class must not be reviewed again",
					sameRule.isViolated(sameContent));

			// A later job after an edit (blobB): reviewed again.
			StubJob edited = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule editedRule = new FakeRule(ledger)
					.with(target, "blobB", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			assertTrue("an edited class must be reviewed again", editedRule.isViolated(edited));
		} finally {
			delete(root);
		}
	}

	/**
	 * A verdict file is deleted once its verdicts are read, so a verdict left on the git-ignored
	 * scratch path by one job is not reused to settle an edited class a later job's session never
	 * wrote a verdict for.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void staleVerdictFileIsNotReusedInALaterJob() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String target = "engine/ml/src/main/java/org/example/Window.java";
			Files.createDirectories(root.resolve(target).getParent());
			Files.writeString(root.resolve(target), "class Window {}");
			Path ledger = root.resolve("ledger.tsv");

			StubJob firstJob = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule firstRule = new FakeRule(ledger).with(target, "v1", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			firstRule.buildCorrectionPrompt(firstJob);
			appendVerdict(root, VERDICT_FILE, target + ": CLEAN");
			firstRule.onCorrectionAttempted(firstJob);
			assertTrue(firstRule.getReviewed().contains(target));
			assertFalse("the verdict file must be cleared once its verdicts are read",
					Files.exists(root.resolve(VERDICT_FILE)));

			StubJob laterJob = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule laterRule = new FakeRule(ledger).with(target, "v2", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			assertTrue("an edited class must be reviewed again", laterRule.isViolated(laterJob));
			laterRule.buildCorrectionPrompt(laterJob);
			laterRule.onCorrectionAttempted(laterJob);
			assertTrue("a stale verdict must not settle a class the session did not review",
					laterRule.getReviewed().isEmpty());
		} finally {
			delete(root);
		}
	}

	/**
	 * A verdict file left on the git-ignored scratch path by a session that was killed before it
	 * reached {@link ObjectOrientedDesignRule#onCorrectionAttempted} — so the file was never deleted
	 * and survives {@code git clean -fd} into the next job — is cleared before the next session is
	 * dispatched. A class that session edits but writes no verdict for is therefore left unsettled
	 * rather than settled by the leftover verdict.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void staleVerdictFileFromAKilledSessionIsClearedBeforeDispatch() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String target = "engine/ml/src/main/java/org/example/Window.java";
			Files.createDirectories(root.resolve(target).getParent());
			Files.writeString(root.resolve(target), "class Window {}");
			Path ledger = root.resolve("ledger.tsv");

			// An earlier session wrote a CLEAN verdict but was killed before onCorrectionAttempted,
			// so the git-ignored scratch file survived into this job's working tree.
			appendVerdict(root, VERDICT_FILE, target + ": CLEAN");

			StubJob job = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule rule = new FakeRule(ledger).with(target, "v2", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			assertTrue(rule.isViolated(job));

			rule.buildCorrectionPrompt(job);
			assertFalse("the stale verdict file must be cleared before the session runs",
					Files.exists(root.resolve(VERDICT_FILE)));

			// The session edits the class but writes no verdict line for it.
			rule.onCorrectionAttempted(job);
			assertTrue("a leftover verdict must not settle a class this session did not review",
					rule.getReviewed().isEmpty());
		} finally {
			delete(root);
		}
	}

	/**
	 * The real {@link ObjectOrientedDesignRule#changedLineCount} counts the lines tracked files add
	 * relative to {@code origin/<base>} plus every line of an untracked file, ignores deletions, and
	 * skips untracked harness scratch such as {@code .flowtree/}.
	 *
	 * @throws Exception if the temporary repository cannot be built
	 */
	@Test(timeout = 60000)
	public void changedLineCountMeasuresAddedLinesAgainstTheBase() throws Exception {
		try (GitTestRepository repo = GitTestRepository.create("oop-rule-git", false)) {
			repo.write("Kept.java", "a\nb\nc\n");
			repo.write("Shrunk.java", "x\ny\nz\n");
			repo.commitAll("initial");
			repo.git("update-ref", "refs/remotes/origin/master", "HEAD");

			ObjectOrientedDesignRule rule = new ObjectOrientedDesignRule();
			StubJob job = new StubJob(repo.dir(), new ArrayList<>());
			assertEquals("a clean tree adds nothing", 0, rule.changedLineCount(job));

			repo.write("Kept.java", "a\nb\nc\nd\ne\n");
			repo.write("Shrunk.java", "x\n");
			repo.write("New.java", "1\n2\n3\n4\n");
			repo.write(".flowtree/oop-review.md", "v1\nv2\nv3\n");

			assertEquals("two lines added to a tracked file plus four in an untracked one",
					6, rule.changedLineCount(job));
		}
	}

	/**
	 * Appends a line to a verdict file of a working tree, as a review session does.
	 *
	 * @param root     the working tree
	 * @param relative the verdict file's path relative to the tree
	 * @param line     the line to append
	 * @throws IOException if the file cannot be written
	 */
	private void appendVerdict(Path root, String relative, String line) throws IOException {
		Path verdicts = root.resolve(relative);
		Files.createDirectories(verdicts.getParent());
		Files.writeString(verdicts, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** The repository-root verdict file, relative to the working tree. */
	private static final String VERDICT_FILE = ObjectOrientedDesignRule.VERDICT_PATH;

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
	 * The verdict file is the rule's progress path, so a review that finds a class sound is not
	 * counted as a session that made no progress, and the per-pass cap is the runner's ceiling.
	 */
	@Test(timeout = 30000)
	public void verdictFileIsProgressAndCapIsRunnerCeiling() {
		ObjectOrientedDesignRule rule = new ObjectOrientedDesignRule();
		assertEquals("object-oriented-design", rule.getName());
		assertEquals(Set.of(ObjectOrientedDesignRule.VERDICT_PATH), rule.getProgressPaths());
		assertEquals(CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES, rule.getMaxRetries());
	}

	/**
	 * The prompt names every flagged class with the signals that flagged it, covers the violations
	 * the owner has rejected, demands fixes rather than deferral, asks for one verdict line per
	 * class, and forbids editing the commit message.
	 */
	@Test(timeout = 30000)
	public void promptListsFlaggedClassesWithSignals() {
		String window = "engine/ml/src/main/java/org/example/Window.java";
		String config = "engine/ml/src/main/java/org/example/LayerConfig.java";
		ObjectOrientedDesignRule.Candidate a = new ObjectOrientedDesignRule.Candidate(
				window, "w1", EnumSet.of(ObjectOrientedRiskGate.Signal.LAMBDA_ARGUMENT), 40);
		ObjectOrientedDesignRule.Candidate b = new ObjectOrientedDesignRule.Candidate(
				config, "c1", EnumSet.of(ObjectOrientedRiskGate.Signal.SUSPICIOUS_NAME), 12);
		String prompt = new ObjectOrientedDesignRule().buildReviewPrompt(List.of(a, b), "develop");

		assertTrue(prompt.contains(window));
		assertTrue(prompt.contains(config));
		assertTrue(prompt.contains(ObjectOrientedRiskGate.Signal.LAMBDA_ARGUMENT.description()));
		assertTrue(prompt.contains(ObjectOrientedRiskGate.Signal.SUSPICIOUS_NAME.description()));
		assertTrue(prompt.contains("git diff origin/develop"));
		assertTrue(prompt.contains("IDENTITY"));
		assertTrue(prompt.contains("OWN YOUR COLLABORATORS"));
		assertTrue(prompt.contains("NO REACHING INTO OBJECTS"));
		assertTrue(prompt.contains("SUBCLASS, DON'T WIRE LAMBDAS"));
		assertTrue(prompt.contains("Do not defer"));
		assertTrue(prompt.contains(ObjectOrientedDesignRule.VERDICT_PATH));
		assertTrue(prompt.contains("Do NOT write or edit commit.txt"));
	}

	/**
	 * STEP 3 scopes the review to the code the branch wrote: it fixes violations there, files a
	 * follow-up task (rather than an edit) for surrounding code, and keeps its edits proportionate.
	 * It no longer demands cross-file refactors.
	 */
	@Test(timeout = 30000)
	public void promptScopesFixesToTheBranchAndFilesFollowUps() {
		ObjectOrientedDesignRule.Candidate a = new ObjectOrientedDesignRule.Candidate(
				"engine/ml/src/main/java/org/example/Window.java", "w1",
				EnumSet.of(ObjectOrientedRiskGate.Signal.NEW_CLASS), 40);
		String prompt = new ObjectOrientedDesignRule().buildReviewPrompt(List.of(a), "master");

		assertTrue(prompt.contains("Fix what this branch wrote; file a follow-up for the rest"));
		assertTrue(prompt.contains("code this branch added or changed"));
		assertTrue(prompt.contains("surrounding code this branch did not write"));
		assertTrue(prompt.contains("do NOT edit that code in this job"));
		assertTrue(prompt.contains("tracker_create_task"));
		assertTrue(prompt.contains("proportionate"));
		assertTrue("a follow-up-only finding must still get a settled verdict",
				prompt.contains("`<class>: CLEAN - follow-up <task id> filed for <surrounding class>`"));
		assertTrue("the verdict word is always CLEAN or FIXED, with the follow-up as a suffix",
				prompt.contains("The verdict word is always CLEAN or FIXED"));
		assertFalse("the prompt must not contradict the follow-up suffix by allowing only two forms",
				prompt.contains("Use only these two forms"));
		assertFalse("the prompt must not demand refactors across other files",
				prompt.contains("even when the fix is a refactor that touches other files"));
	}

	/**
	 * The selection is frozen on first evaluation: after one session settles the flagged class, a
	 * class that only the review's own edits produced, and the reviewed class's edited content, are
	 * never gated again in the job, so no second session starts.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void reviewIntroducedChangesDoNotStartASecondSession() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String primary = "engine/ml/src/main/java/org/example/Window.java";
			String introduced = "engine/ml/src/main/java/org/example/WindowScope.java";
			for (String path : List.of(primary, introduced)) {
				Files.createDirectories(root.resolve(path).getParent());
				Files.writeString(root.resolve(path), "class X {}");
			}
			List<String> changed = new ArrayList<>(List.of(primary));
			StubJob job = new StubJob(root, changed);
			FakeRule rule = new FakeRule(root.resolve("ledger.tsv"))
					.with(primary, "w1", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(introduced, "s1", ObjectOrientedRiskGate.Signal.NEW_CLASS, ObjectOrientedRiskGate.Signal.NEW_STATIC);

			assertTrue(rule.isViolated(job));
			String prompt = rule.buildCorrectionPrompt(job);
			assertFalse(prompt.contains(introduced));

			changed.add(introduced);
			rule.edit(primary, "w2");
			appendVerdict(root, VERDICT_FILE, primary + ": FIXED - moved the scope builder onto the window");
			rule.onCorrectionAttempted(job);

			assertEquals(Set.of(primary), rule.getReviewed());
			assertFalse("a class the review introduced must not start another session", rule.isViolated(job));
		} finally {
			delete(root);
		}
	}

	/**
	 * The content a review session leaves behind is recorded under the class's verdict, so a later
	 * job does not review the review's own edit again; content edited after that is reviewed again.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void reviewEditIsRecordedForLaterJobs() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String target = "engine/ml/src/main/java/org/example/Window.java";
			Files.createDirectories(root.resolve(target).getParent());
			Files.writeString(root.resolve(target), "class Window {}");
			Path ledger = root.resolve("ledger.tsv");

			StubJob firstJob = new StubJob(root, new ArrayList<>(List.of(target)));
			FakeRule firstRule = new FakeRule(ledger).with(target, "before", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			firstRule.buildCorrectionPrompt(firstJob);
			firstRule.edit(target, "after");
			appendVerdict(root, VERDICT_FILE, target + ": FIXED - extended the framework type");
			firstRule.onCorrectionAttempted(firstJob);

			assertTrue(new ObjectOrientedReviewLedger(ledger).isReviewed(target, "before"));
			assertTrue(new ObjectOrientedReviewLedger(ledger).isReviewed(target, "after"));
			FakeRule laterRule = new FakeRule(ledger).with(target, "after", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			assertFalse("the review's own edit must not be reviewed again in a later job",
					laterRule.isViolated(new StubJob(root, new ArrayList<>(List.of(target)))));
			FakeRule editedRule = new FakeRule(ledger).with(target, "later", ObjectOrientedRiskGate.Signal.NEW_CLASS);
			assertTrue(editedRule.isViolated(new StubJob(root, new ArrayList<>(List.of(target)))));
		} finally {
			delete(root);
		}
	}

	/**
	 * Each session announces its classes, and afterwards the rule posts the cumulative review time
	 * and cost; a session whose added lines exceed {@link ObjectOrientedDesignRule#MAX_GROWTH_RATIO}
	 * times the reviewed classes' added lines raises a warning, and a proportionate one does not.
	 *
	 * @throws IOException if the temporary tree cannot be written
	 */
	@Test(timeout = 30000)
	public void reportsCumulativeCostAndWarnsOnDisproportionateReview() throws IOException {
		Path root = Files.createTempDirectory("oop-rule");
		try {
			String window = "engine/ml/src/main/java/org/example/Window.java";
			String holder = "engine/ml/src/main/java/org/example/ModelHolder.java";
			for (String path : List.of(window, holder)) {
				Files.createDirectories(root.resolve(path).getParent());
				Files.writeString(root.resolve(path), "class X {}");
			}

			StubJob job = new StubJob(root, new ArrayList<>(List.of(window, holder)));
			FakeRule rule = new FakeRule(root.resolve("ledger.tsv"))
					.with(window, "w1", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.with(holder, "h1", ObjectOrientedRiskGate.Signal.SUSPICIOUS_NAME)
					.addedLines(window, 10).addedLines(holder, 5)
					.lineCounts(100, 200);
			job.getCostTracker().record("claude", "anthropic/primary", 4.00);
			rule.buildCorrectionPrompt(job);
			assertTrue("the session's classes are announced", job.posted("2 class(es): Window, ModelHolder"));
			job.getCostTracker().record("claude", "anthropic/review", 1.25);
			appendVerdict(root, VERDICT_FILE, window + ": CLEAN");
			appendVerdict(root, VERDICT_FILE, holder + ": CLEAN");
			rule.onCorrectionAttempted(job);

			assertTrue("the cumulative figure counts only the review's cost",
					job.posted("2 class(es) reviewed") && job.posted("$1.25"));
			assertFalse(job.posted("lower bound"));
			assertTrue("100 lines added over 15 reviewed lines must warn",
					job.posted(HarnessStatusReporter.UNUSUAL_EMOJI) && job.posted("added 100 lines"));

			StubJob proportionate = new StubJob(root, new ArrayList<>(List.of(window)));
			FakeRule small = new FakeRule(root.resolve("ledger2.tsv"))
					.with(window, "w1", ObjectOrientedRiskGate.Signal.NEW_CLASS)
					.addedLines(window, 10).lineCounts(100, 130);
			small.buildCorrectionPrompt(proportionate);
			proportionate.getCostTracker().markIncomplete();
			small.onCorrectionAttempted(proportionate);
			assertFalse("30 lines over 10 reviewed lines is within the ratio",
					proportionate.posted(HarnessStatusReporter.UNUSUAL_EMOJI));
			assertTrue("a lost session cost makes the figure a lower bound", proportionate.posted("lower bound"));
		} finally {
			delete(root);
		}
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

	/** With review enabled and the flag left at its default, the rule is assembled. */
	@Test(timeout = 30000)
	public void rulePresentByDefaultWithReviewEnabled() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		assertTrue(job.isReviewEnabled());
		assertTrue(job.isObjectOrientedReviewEnabled());
		assertTrue(hasObjectOrientedRule(job));
	}

	/** Enabled while review is enabled, the rule is assembled after the review rule, before staging checks. */
	@Test(timeout = 30000)
	public void ruleOrderedAfterReviewAndBeforeStaging() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setTargetBranch("feature/test");

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

	/** Switching the flag off removes the rule even while review is enabled. */
	@Test(timeout = 30000)
	public void ruleAbsentWhenSwitchedOff() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setObjectOrientedReviewEnabled(false);
		assertFalse(hasObjectOrientedRule(job));
	}

	/** The rule has no effect when review is disabled, even at its default. */
	@Test(timeout = 30000)
	public void ruleAbsentWhenReviewDisabled() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		job.setReviewEnabled(false);
		assertFalse(hasObjectOrientedRule(job));
	}

	/** The job codec omits the key at the on-by-default value and carries an explicit off over the wire. */
	@Test(timeout = 30000)
	public void jobCodecRoundTripsOffSwitch() {
		CodingAgentJob job = new CodingAgentJob("t1", "p");
		assertFalse(job.encode().contains("objectOrientedReviewEnabled"));
		assertTrue(GitManagedJobSerializationTest.roundTrip(job).isObjectOrientedReviewEnabled());

		job.setObjectOrientedReviewEnabled(false);
		assertTrue(job.encode().contains("::objectOrientedReviewEnabled:=false"));
		assertFalse(GitManagedJobSerializationTest.roundTrip(job).isObjectOrientedReviewEnabled());
	}

	/** A factory decoded without the field, as from an older submission, leaves the rule on. */
	@Test(timeout = 30000)
	public void missingFieldDecodesAsOn() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
		assertTrue(decoded.isObjectOrientedReviewEnabled());
		assertTrue(((CodingAgentJob) decoded.nextJob()).isObjectOrientedReviewEnabled());
	}

	/** A factory that switches the review off survives the wire and hands the off switch to its jobs. */
	@Test(timeout = 30000)
	public void factoryOptOutRoundTripsAndPropagates() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
		factory.setObjectOrientedReviewEnabled(false);
		CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
		assertFalse(decoded.isObjectOrientedReviewEnabled());
		assertFalse(((CodingAgentJob) decoded.nextJob()).isObjectOrientedReviewEnabled());
	}
}
