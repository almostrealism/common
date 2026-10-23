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
 * {@link CodingAgentJob#getCommitMessage()}. These tests exercise the helper
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

    /** {@link CommitMessageBuilder#resolve} falls back to the prompt-derived message when commit.txt is missing. */
    @Test(timeout = 30000)
    public void resolveFallsBackToPromptWhenCommitTxtMissing() {
        CodingAgentJob job = job("do the work");
        String msg = job.commitMessageBuilder().resolve(job);
        assertNotNull(msg);
        assertTrue("Fallback must start with 'Claude Code:'", msg.startsWith("Claude Code:"));
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, job.getCommitMessageSource());
    }

    /** A blank commit.txt is treated as missing — fallback runs and source is {@code prompt_fallback}. */
    @Test(timeout = 30000)
    public void resolveFallsBackWhenCommitTxtIsBlank() throws IOException {
        writeCommitTxt("   \n\t");
        CodingAgentJob job = job("do the work");
        String msg = job.commitMessageBuilder().resolve(job);
        assertTrue(msg.startsWith("Claude Code:"));
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, job.getCommitMessageSource());
    }

    /** Fallback summary is the prompt unchanged when it fits under 72 chars. */
    @Test(timeout = 30000)
    public void fallbackSummaryUnderLimitIsVerbatim() {
        CodingAgentJob job = job("short prompt");
        String msg = job.commitMessageBuilder().buildFallbackMessage(job);
        assertTrue("Subject line should include the full short prompt",
                msg.startsWith("Claude Code: short prompt"));
    }

    /** Fallback summary is truncated to 72 chars (69 chars + "...") for long prompts. */
    @Test(timeout = 30000)
    public void fallbackSummaryOver72CharsIsTruncatedWithEllipsis() {
        String longPrompt = "a".repeat(120);
        CodingAgentJob job = job(longPrompt);
        String msg = job.commitMessageBuilder().buildFallbackMessage(job);
        String firstLine = msg.split("\n", 2)[0];
        // "Claude Code: " (13 chars) + first 69 chars + "..." = 13 + 69 + 3 = 85
        assertEquals("Claude Code: " + "a".repeat(69) + "...", firstLine);
    }

    /** Fallback body includes the full prompt and the exit code. */
    @Test(timeout = 30000)
    public void fallbackBodyIncludesFullPromptAndExitCode() {
        CodingAgentJob job = job("do the work please");
        String msg = job.commitMessageBuilder().buildFallbackMessage(job);
        assertTrue("Body must echo the full prompt",
                msg.contains("Prompt: do the work please"));
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

    /** {@link CommitMessageBuilder#buildExhaustionFallback} uses the trimmed first line of the prompt. */
    @Test(timeout = 30000)
    public void exhaustionFallbackUsesFirstPromptLine() {
        CodingAgentJob job = job("  Add the sliding window layer  \nMore detail on a second line.");
        assertEquals("Add the sliding window layer",
                job.commitMessageBuilder().buildExhaustionFallback(job));
    }

    /** A first line of exactly 72 characters is kept whole; one character more is truncated to 69 + "...". */
    @Test(timeout = 30000)
    public void exhaustionFallbackTruncatesOnlyPast72Characters() {
        CodingAgentJob exact = job("b".repeat(72));
        assertEquals("b".repeat(72), exact.commitMessageBuilder().buildExhaustionFallback(exact));

        CodingAgentJob longer = job("c".repeat(73));
        assertEquals("c".repeat(69) + "...", longer.commitMessageBuilder().buildExhaustionFallback(longer));
    }

    /** A first prompt line carrying author attribution is never used; the task-id message is used instead. */
    @Test(timeout = 30000)
    public void exhaustionFallbackRejectsAttributedFirstLine() {
        CodingAgentJob job = job("Co-Authored-By: Claude <noreply@anthropic.com>\nFix the bug");
        assertEquals("Job t1 commit", job.commitMessageBuilder().buildExhaustionFallback(job));
    }

    /** A blank or missing prompt falls back to the task-id message. */
    @Test(timeout = 30000)
    public void exhaustionFallbackUsesTaskIdForBlankOrNullPrompt() {
        CodingAgentJob blank = job("  \n\t ");
        assertEquals("Job t1 commit", blank.commitMessageBuilder().buildExhaustionFallback(blank));

        CodingAgentJob none = job(null);
        assertEquals("Job t1 commit", none.commitMessageBuilder().buildExhaustionFallback(none));
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
}
