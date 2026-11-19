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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the {@link CommitMessageBuilder} collaborator extracted from
 * {@link CodingAgentJob#getCommitMessage(String)}. These tests exercise the helper
 * directly so that the source-tag side effects ({@code "agent"} vs
 * {@code "prompt_fallback"}) and the deterministic fallback formatting are
 * preserved independently of any future changes to {@code CodingAgentJob}.
 */
public class CommitMessageBuilderTest extends TestSuiteBase {

    /** Temporary working directory recreated for each test. */
    private Path tempDir;

    /** Creates a fresh temporary directory before each test. */
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("commit-msg-builder-test");
    }

    /** Deletes the temporary directory and all its contents after each test. */
    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /** Writes {@code content} to {@code commit.txt} in the working directory. */
    private void writeCommitTxt(String content) throws IOException {
        Files.write(tempDir.resolve("commit.txt"), content.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a {@link CodingAgentJob} bound to the temporary directory with no target branch wiring. */
    private CodingAgentJob job(String prompt) {
        CodingAgentJob job = new CodingAgentJob("t1", prompt);
        job.setWorkingDirectory(tempDir.toString());
        return job;
    }

    /** Verifies the {@code SOURCE_AGENT} and {@code SOURCE_PROMPT_FALLBACK} constants are stable wire tags. */
    @Test(timeout = 30000)
    public void sourceConstantsAreStableWireTags() {
        assertEquals("agent", CommitMessageBuilder.SOURCE_AGENT);
        assertEquals("prompt_fallback", CommitMessageBuilder.SOURCE_PROMPT_FALLBACK);
    }

    /** {@link CommitMessageBuilder#resolve} returns trimmed commit.txt content when present. */
    @Test(timeout = 30000)
    public void resolveReturnsTrimmedAgentMessageWhenCommitTxtPresent() throws IOException {
        writeCommitTxt("  Fix bug X\n  ");
        CodingAgentJob job = job("do the work");
        String msg = job.commitMessageBuilder().resolve(job);
        assertEquals("Fix bug X", msg);
        assertEquals(CommitMessageBuilder.SOURCE_AGENT, job.getCommitMessageSource());
    }

    /** {@link CommitMessageBuilder#resolve} preserves an already-set source tag (e.g. recovered). */
    @Test(timeout = 30000)
    public void resolveDoesNotOverwriteExistingSource() throws IOException {
        writeCommitTxt("Fix bug Y");
        CodingAgentJob job = job("do the work");
        job.commitMessageBuilder().setSource(CommitMessageRule.SOURCE_RECOVERED);
        String msg = job.commitMessageBuilder().resolve(job);
        assertEquals("Fix bug Y", msg);
        assertEquals(CommitMessageRule.SOURCE_RECOVERED, job.getCommitMessageSource());
    }

    /**
     * {@link CommitMessageBuilder#resolve} falls back to a generated message when commit.txt is
     * missing. With no determinable changes (the temp directory is not a git repository) the
     * subject names the job, never the prompt, and the source is still {@code prompt_fallback}.
     */
    @Test(timeout = 30000)
    public void resolveFallsBackToGeneratedMessageWhenCommitTxtMissing() {
        CodingAgentJob job = job("do the work");
        String msg = job.commitMessageBuilder().resolve(job);
        assertNotNull(msg);
        assertTrue("Fallback subject must name the job: " + msg, msg.startsWith("Job t1 commit"));
        assertFalse("Fallback must not echo the prompt", msg.contains("do the work"));
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, job.getCommitMessageSource());
    }

    /** A blank commit.txt is treated as missing — fallback runs and source is {@code prompt_fallback}. */
    @Test(timeout = 30000)
    public void resolveFallsBackWhenCommitTxtIsBlank() throws IOException {
        writeCommitTxt("   \n\t");
        CodingAgentJob job = job("do the work");
        String msg = job.commitMessageBuilder().resolve(job);
        assertTrue("Fallback subject must name the job: " + msg, msg.startsWith("Job t1 commit"));
        assertFalse("Fallback must not echo the prompt", msg.contains("do the work"));
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, job.getCommitMessageSource());
    }

    /**
     * {@link CommitMessageBuilder#summarizeChangedFiles} names one changed file as
     * {@code "Update <name>"}, and two as {@code "Update <a> and <b>"}, using class/file base
     * names rather than full paths.
     */
    @Test(timeout = 30000)
    public void summarizeNamesOneOrTwoChangedFiles() {
        assertEquals("Update IndexSequence", CommitMessageBuilder.summarizeChangedFiles(
                List.of("compute/algebra/src/main/java/org/x/IndexSequence.java")));
        assertEquals("Update IndexSequence and IndexSequenceTest",
                CommitMessageBuilder.summarizeChangedFiles(List.of(
                        "compute/algebra/src/main/java/org/x/IndexSequence.java",
                        "compute/algebra/src/test/java/org/x/IndexSequenceTest.java")));
    }

    /**
     * With more than two changed files the summary names the first and counts the rest, staying at
     * or under 72 characters.
     */
    @Test(timeout = 30000)
    public void summarizeNamesFirstFileAndCountsTheRest() {
        String subject = CommitMessageBuilder.summarizeChangedFiles(List.of(
                "a/IndexSequence.java", "a/B.java", "a/C.java", "a/D.java"));
        assertEquals("Update IndexSequence and 3 other files", subject);
        assertTrue("Subject must stay within 72 characters: " + subject, subject.length() <= 72);
    }

    /** A very long first file name still yields a subject no longer than 72 characters. */
    @Test(timeout = 30000)
    public void summarizeClipsOverlongSubjectTo72Characters() {
        String subject = CommitMessageBuilder.summarizeChangedFiles(List.of(
                "a/" + "L".repeat(120) + ".java", "a/B.java", "a/C.java"));
        assertTrue("Subject must stay within 72 characters: " + subject, subject.length() <= 72);
        assertTrue("Overlong subject is ellipsised", subject.endsWith("..."));
    }

    /**
     * A single changed file whose name alone exceeds the limit is clipped as a one-file summary; it
     * never claims a count of other files that do not exist.
     */
    @Test(timeout = 30000)
    public void summarizeClipsOverlongSingleFileWithoutOtherFileCount() {
        String name = "S".repeat(100);
        String subject = CommitMessageBuilder.summarizeChangedFiles(List.of("a/" + name + ".java"));
        assertEquals(("Update " + name).substring(0, 69) + "...", subject);
        assertFalse("A single file must not be described with an other-file count: " + subject,
                subject.contains("other file"));
    }

    /**
     * Two changed files whose names together exceed the limit fall back to naming the first and
     * counting one other file.
     */
    @Test(timeout = 30000)
    public void summarizeCountsSecondFileWhenTwoNamesDoNotFit() {
        String first = "F".repeat(30);
        String second = "G".repeat(40);
        assertEquals("Update " + first + " and 1 other file",
                CommitMessageBuilder.summarizeChangedFiles(List.of(first + ".java", second + ".java")));
    }

    /**
     * Control characters in a file name (which {@code git status -z} reports verbatim) are replaced
     * so they cannot inject extra lines into the commit message.
     */
    @Test(timeout = 30000)
    public void summarizeReplacesControlCharactersInNames() {
        String subject = CommitMessageBuilder.summarizeChangedFiles(
                List.of("a/Evil\n\nCo-Authored-By: X\r\tName.java"));
        assertEquals("Update Evil  Co-Authored-By: X  Name", subject);
        assertFalse("Subject must be a single line", subject.contains("\n") || subject.contains("\r"));
    }

    /**
     * {@link CommitMessageBuilder#buildChangeSubject} reads the job's real working tree: changed
     * files reported by {@code git status} name the subject, and harness scratch paths
     * ({@code commit.txt}, {@code .flowtree/}) are filtered out of it.
     */
    @Test(timeout = 30000)
    public void buildChangeSubjectNamesChangedFilesFromGitStatus() throws IOException, InterruptedException {
        initGitRepository();
        Files.writeString(tempDir.resolve("Widget.java"), "class Widget { }\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("commit.txt"), "  \n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".flowtree"));
        Files.writeString(tempDir.resolve(".flowtree/notes.md"), "scratch\n", StandardCharsets.UTF_8);

        CodingAgentJob job = job("You are hunting for a real defect");
        assertEquals("Update Widget", job.commitMessageBuilder().buildChangeSubject(job));
        assertEquals("Update Widget", job.commitMessageBuilder().buildExhaustionFallback(job));
        String msg = job.commitMessageBuilder().resolve(job);
        assertTrue("Commit-time fallback subject must name the changed file: " + msg,
                msg.startsWith("Update Widget\n"));
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, job.getCommitMessageSource());
    }

    /**
     * In a git repository whose only changes are harness scratch paths, there is nothing to name,
     * so the subject falls back to the job rather than naming a scratch file.
     */
    @Test(timeout = 30000)
    public void buildChangeSubjectIgnoresTreeWithOnlyExcludedPaths() throws IOException, InterruptedException {
        initGitRepository();
        Files.writeString(tempDir.resolve("commit.txt"), "  \n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".flowtree"));
        Files.writeString(tempDir.resolve(".flowtree/notes.md"), "scratch\n", StandardCharsets.UTF_8);

        CodingAgentJob job = job("do the work");
        assertEquals("Job t1 commit", job.commitMessageBuilder().buildChangeSubject(job));
    }

    /** Initializes an empty git repository in the temporary working directory. */
    private void initGitRepository() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "init", "-q")
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assertEquals("git init must succeed", 0, process.waitFor());
    }

    /** An empty change set yields no summary, so the caller supplies the neutral job-named subject. */
    @Test(timeout = 30000)
    public void summarizeReturnsNullForNoChanges() {
        assertNull(CommitMessageBuilder.summarizeChangedFiles(List.of()));
        assertNull(CommitMessageBuilder.summarizeChangedFiles(null));
    }

    /** Fallback body records the harness provenance and the exit code, and never the prompt. */
    @Test(timeout = 30000)
    public void fallbackBodyRecordsProvenanceAndExitCodeNotPrompt() {
        CodingAgentJob job = job("do the work please");
        String msg = job.commitMessageBuilder().buildFallbackMessage(job);
        assertFalse("Body must not echo the prompt", msg.contains("do the work please"));
        assertTrue("Body must say the harness generated the message",
                msg.contains("The harness generated this message because the agent wrote no commit.txt."));
        assertTrue("Body must include the exit code line",
                msg.contains("Exit code: 0"));
    }

    /** {@link CommitMessageBuilder#resolve} strips a trailing attribution block from commit.txt. */
    @Test(timeout = 30000)
    public void resolveRemovesTrailingAuthorAttribution() throws IOException {
        writeCommitTxt("Fix bug X\n\nThe guard was inverted.\n\n"
                + "Co-Authored-By: Claude <noreply@anthropic.com>\n");
        CodingAgentJob job = job("do the work");
        assertEquals("Fix bug X\n\nThe guard was inverted.", job.commitMessageBuilder().resolve(job));
        assertEquals(CommitMessageBuilder.SOURCE_AGENT, job.getCommitMessageSource());
    }

    /** {@link CommitMessageBuilder#resolve} fails when attribution cannot be removed safely. */
    @Test(timeout = 30000)
    public void resolveFailsWhenAttributionIsNotSafelyRemovable() throws IOException {
        writeCommitTxt("Fix bug X\n\nCo-Authored-By: Claude <noreply@anthropic.com>\n\n"
                + "Body content written after the trailer.");
        CodingAgentJob job = job("do the work");
        try {
            job.commitMessageBuilder().resolve(job);
            fail("Expected an IllegalStateException for unremovable author attribution");
        } catch (IllegalStateException e) {
            assertTrue("Failure must name the offending content",
                    e.getMessage().contains("Co-Authored-By: Claude <noreply@anthropic.com>"));
        }
    }

    /**
     * {@link CommitMessageBuilder#restoreCommitTxt} with a non-null saved message overwrites
     * whatever a session wrote, so the primary message always wins.
     */
    @Test(timeout = 30000)
    public void restoreCommitTxtOverwritesSessionMessageWithSaved() throws IOException {
        writeCommitTxt("Session-written verdict message");
        CodingAgentJob job = job("do the work");
        CommitMessageBuilder.restoreCommitTxt(job, "Add the sliding window layer");
        assertEquals("Add the sliding window layer",
                Files.readString(tempDir.resolve("commit.txt"), StandardCharsets.UTF_8));
    }

    /**
     * {@link CommitMessageBuilder#restoreCommitTxt} with a null saved message (nothing was
     * captured) deletes whatever the session left, so a session message cannot become the commit
     * message by default.
     */
    @Test(timeout = 30000)
    public void restoreCommitTxtWithNullSavedDeletesSessionMessage() throws IOException {
        writeCommitTxt("Session-written verdict message");
        CodingAgentJob job = job("do the work");
        CommitMessageBuilder.restoreCommitTxt(job, null);
        assertTrue("commit.txt must be deleted when nothing was captured",
                !Files.exists(tempDir.resolve("commit.txt")));
    }

    /**
     * {@link CommitMessageBuilder#restoreCommitTxt} with a null saved message and no existing
     * {@code commit.txt} is a no-op: the delete tolerates a missing file.
     */
    @Test(timeout = 30000)
    public void restoreCommitTxtWithNullSavedAndNoFileIsHarmless() {
        CodingAgentJob job = job("do the work");
        CommitMessageBuilder.restoreCommitTxt(job, null);
        assertTrue("no commit.txt is created when there was none and nothing was captured",
                !Files.exists(tempDir.resolve("commit.txt")));
    }

    /** Fallback body includes the session id only when set; absence leaves no Session: line. */
    @Test(timeout = 30000)
    public void fallbackBodyOmitsSessionLineWhenSessionIdIsNull() {
        CodingAgentJob job = job("do the work");
        assertNull("Pre-condition: sessionId starts null", job.getSessionId());
        String msg = job.commitMessageBuilder().buildFallbackMessage(job);
        assertTrue("No Session: line when sessionId is null",
                !msg.contains("\nSession: "));
    }

    /**
     * {@link CommitMessageBuilder#buildExhaustionFallback} never uses the prompt. With no
     * determinable changes (the temp directory is not a git repository) it names the job, even when
     * the prompt reads like an instruction that previously became a commit subject.
     */
    @Test(timeout = 30000)
    public void exhaustionFallbackNamesJobAndNeverUsesPrompt() {
        CodingAgentJob job = job(
                "You are hunting for a real defect in this codebase and proving it exists by writing"
                        + " a test that fails because of it.");
        assertEquals("Job t1 commit", job.commitMessageBuilder().buildExhaustionFallback(job));
    }

    /** A blank or missing prompt and no changes falls back to the task-id message. */
    @Test(timeout = 30000)
    public void exhaustionFallbackUsesTaskIdForBlankOrNullPrompt() {
        CodingAgentJob blank = job("  \n\t ");
        assertEquals("Job t1 commit", blank.commitMessageBuilder().buildExhaustionFallback(blank));

        CodingAgentJob none = job(null);
        assertEquals("Job t1 commit", none.commitMessageBuilder().buildExhaustionFallback(none));
    }

    /**
     * An instruction-worded prompt never appears in either fallback's subject or body, which is the
     * regression this change exists to prevent (a defect-hunt prompt had become a PR title).
     */
    @Test(timeout = 30000)
    public void instructionWordedPromptNeverLeaksIntoEitherFallback() {
        String prompt = "You are hunting for a real defect in this codebase and proving it exists ...";
        CodingAgentJob job = job(prompt);
        String fallback = job.commitMessageBuilder().buildFallbackMessage(job);
        String exhaustion = job.commitMessageBuilder().buildExhaustionFallback(job);
        assertFalse("Prompt must not appear in the commit-time fallback", fallback.contains(prompt));
        assertFalse("Prompt must not appear in its subject",
                fallback.split("\n", 2)[0].contains("hunting"));
        assertFalse("Prompt must not appear in the exhaustion fallback", exhaustion.contains(prompt));
        assertFalse("Prompt must not appear in the exhaustion subject", exhaustion.contains("hunting"));
    }

    /** {@link CommitMessageBuilder#isAuthored} is {@code false} when there is no {@code commit.txt}. */
    @Test(timeout = 30000)
    public void isAuthoredFalseWhenCommitTxtMissing() {
        CodingAgentJob job = job("do the work");
        assertFalse(job.commitMessageBuilder().isAuthored(job));
        assertFalse(job.hasAuthoredCommitMessage());
    }

    /** {@link CommitMessageBuilder#isAuthored} is {@code false} when {@code commit.txt} holds only whitespace. */
    @Test(timeout = 30000)
    public void isAuthoredFalseWhenCommitTxtBlank() throws IOException {
        writeCommitTxt(" \n\t\n");
        CodingAgentJob job = job("do the work");
        assertFalse(job.commitMessageBuilder().isAuthored(job));
    }

    /** {@link CommitMessageBuilder#isAuthored} is {@code true} when {@code commit.txt} holds a message. */
    @Test(timeout = 30000)
    public void isAuthoredTrueWhenCommitTxtHoldsMessage() throws IOException {
        writeCommitTxt("Fix bug Z");
        CodingAgentJob job = job("do the work");
        assertTrue(job.commitMessageBuilder().isAuthored(job));
        assertTrue(job.hasAuthoredCommitMessage());
    }

    /**
     * Each job owns its builder, so the source tag one job records is not seen
     * by another: the tag used to live on the job and must stay per-job now
     * that it lives on the builder.
     */
    @Test(timeout = 30000)
    public void sourceTagIsPerJob() throws IOException {
        CodingAgentJob first = job("do the work");
        CodingAgentJob second = job("do other work");
        assertNotSame(first.commitMessageBuilder(), second.commitMessageBuilder());

        first.commitMessageBuilder().resolve(first);
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, first.getCommitMessageSource());
        assertNull("Another job's builder must not record a source it never resolved",
                second.getCommitMessageSource());
    }

    /**
     * {@link CommitMessageBuilder#resolveForRepo} reads the {@code commit.txt} at
     * a dependent repo's own root, not the primary one, and does not tag the
     * primary source — the source describes how the job's (primary) message was
     * produced.
     */
    @Test(timeout = 30000)
    public void resolveForRepoReadsDependentCommitTxt() throws IOException {
        writeCommitTxt("Primary message");
        Path depDir = Files.createTempDirectory("commit-msg-builder-dep");
        try {
            Files.write(depDir.resolve("commit.txt"),
                    "Move the helper into lib".getBytes(StandardCharsets.UTF_8));
            CodingAgentJob job = job("do the work");
            assertEquals("Move the helper into lib",
                    job.commitMessageBuilder().resolveForRepo(job, depDir.toString()));
            assertNull("A dependent repo's message must not tag the primary source",
                    job.getCommitMessageSource());
        } finally {
            deleteRecursively(depDir);
        }
    }

    /**
     * When a dependent repo has no {@code commit.txt}, the fallback names that
     * repository so it cannot be mistaken for the primary one.
     */
    @Test(timeout = 30000)
    public void resolveForRepoDependentFallbackNamesRepo() throws IOException {
        Path depDir = Files.createTempDirectory("commit-msg-builder-dep-named");
        try {
            CodingAgentJob job = job("do the work");
            String msg = job.commitMessageBuilder().resolveForRepo(job, depDir.toString());
            assertTrue("Fallback must name the dependent repo: " + msg,
                    msg.contains(depDir.getFileName().toString()));
            assertFalse("A dependent fallback must not disclose the absolute checkout path: " + msg,
                    msg.contains(depDir.toString()));
            assertFalse("A dependent fallback must not be the primary prompt fallback",
                    msg.startsWith("Claude Code:"));
            assertNull(job.getCommitMessageSource());
        } finally {
            deleteRecursively(depDir);
        }
    }

    /**
     * {@link CommitMessageBuilder#resolveForRepo} for the primary working
     * directory delegates to {@link CommitMessageBuilder#resolve} and tags the
     * source.
     */
    @Test(timeout = 30000)
    public void resolveForRepoPrimaryDelegatesToResolve() throws IOException {
        writeCommitTxt("Primary message");
        CodingAgentJob job = job("do the work");
        assertEquals("Primary message",
                job.commitMessageBuilder().resolveForRepo(job, tempDir.toString()));
        assertEquals(CommitMessageBuilder.SOURCE_AGENT, job.getCommitMessageSource());
    }

    /** Recursively deletes {@code dir} and all its contents. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
        }
    }
}
