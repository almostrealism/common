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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the {@link CommitMessageBuilder} static helpers extracted from
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
        String msg = CommitMessageBuilder.resolve(job);
        assertEquals("Fix bug X", msg);
        assertEquals(CommitMessageBuilder.SOURCE_AGENT, job.getCommitMessageSource());
    }

    /** {@link CommitMessageBuilder#resolve} preserves an already-set source tag (e.g. recovered). */
    @Test(timeout = 30000)
    public void resolveDoesNotOverwriteExistingSource() throws IOException {
        writeCommitTxt("Fix bug Y");
        CodingAgentJob job = job("do the work");
        job.setCommitMessageSource(CommitMessageRule.SOURCE_RECOVERED);
        String msg = CommitMessageBuilder.resolve(job);
        assertEquals("Fix bug Y", msg);
        assertEquals(CommitMessageRule.SOURCE_RECOVERED, job.getCommitMessageSource());
    }

    /** {@link CommitMessageBuilder#resolve} falls back to the prompt-derived message when commit.txt is missing. */
    @Test(timeout = 30000)
    public void resolveFallsBackToPromptWhenCommitTxtMissing() {
        CodingAgentJob job = job("do the work");
        String msg = CommitMessageBuilder.resolve(job);
        assertNotNull(msg);
        assertTrue("Fallback must start with 'Claude Code:'", msg.startsWith("Claude Code:"));
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, job.getCommitMessageSource());
    }

    /** A blank commit.txt is treated as missing — fallback runs and source is {@code prompt_fallback}. */
    @Test(timeout = 30000)
    public void resolveFallsBackWhenCommitTxtIsBlank() throws IOException {
        writeCommitTxt("   \n\t");
        CodingAgentJob job = job("do the work");
        String msg = CommitMessageBuilder.resolve(job);
        assertTrue(msg.startsWith("Claude Code:"));
        assertEquals(CommitMessageBuilder.SOURCE_PROMPT_FALLBACK, job.getCommitMessageSource());
    }

    /** Fallback summary is the prompt unchanged when it fits under 72 chars. */
    @Test(timeout = 30000)
    public void fallbackSummaryUnderLimitIsVerbatim() {
        CodingAgentJob job = job("short prompt");
        String msg = CommitMessageBuilder.buildFallbackMessage(job);
        assertTrue("Subject line should include the full short prompt",
                msg.startsWith("Claude Code: short prompt"));
    }

    /** Fallback summary is truncated to 72 chars (69 chars + "...") for long prompts. */
    @Test(timeout = 30000)
    public void fallbackSummaryOver72CharsIsTruncatedWithEllipsis() {
        String longPrompt = "a".repeat(120);
        CodingAgentJob job = job(longPrompt);
        String msg = CommitMessageBuilder.buildFallbackMessage(job);
        String firstLine = msg.split("\n", 2)[0];
        // "Claude Code: " (13 chars) + first 69 chars + "..." = 13 + 69 + 3 = 85
        assertEquals("Claude Code: " + "a".repeat(69) + "...", firstLine);
    }

    /** Fallback body includes the full prompt and the exit code. */
    @Test(timeout = 30000)
    public void fallbackBodyIncludesFullPromptAndExitCode() {
        CodingAgentJob job = job("do the work please");
        String msg = CommitMessageBuilder.buildFallbackMessage(job);
        assertTrue("Body must echo the full prompt",
                msg.contains("Prompt: do the work please"));
        assertTrue("Body must include the exit code line",
                msg.contains("Exit code: 0"));
    }

    /** Fallback body includes the session id only when set; absence leaves no Session: line. */
    @Test(timeout = 30000)
    public void fallbackBodyOmitsSessionLineWhenSessionIdIsNull() {
        CodingAgentJob job = job("do the work");
        assertNull("Pre-condition: sessionId starts null", job.getSessionId());
        String msg = CommitMessageBuilder.buildFallbackMessage(job);
        assertTrue("No Session: line when sessionId is null",
                !msg.contains("\nSession: "));
    }
}
