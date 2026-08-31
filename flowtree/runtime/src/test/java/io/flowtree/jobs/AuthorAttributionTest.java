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

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AuthorAttribution} — the detection and removal of
 * author-attribution content in commit messages. The two behaviours that
 * matter are covered directly: attribution standing alone at the end of a
 * message is removed, and attribution anywhere it cannot be deleted line-wise
 * is refused rather than patched.
 */
public class AuthorAttributionTest extends TestSuiteBase {

    /** A message with no attribution survives {@link AuthorAttribution#sanitize} untouched. */
    @Test(timeout = 30000)
    public void cleanMessageIsUnchanged() {
        String message = "Fix off-by-one in the sampler\n\nThe loop ran one step past the end.";
        assertFalse(AuthorAttribution.containsAttribution(message));
        assertEquals(message, AuthorAttribution.sanitize(message));
    }

    /** A trailing {@code Co-Authored-By:} trailer is removed along with the blank line before it. */
    @Test(timeout = 30000)
    public void trailingCoAuthorTrailerIsRemoved() {
        String message = "Fix off-by-one in the sampler\n\nThe loop ran one step past the end.\n\n"
                + "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>\n";
        assertEquals("Fix off-by-one in the sampler\n\nThe loop ran one step past the end.",
                AuthorAttribution.sanitize(message));
    }

    /** The full generated block — tool credit plus co-author trailer — is removed. */
    @Test(timeout = 30000)
    public void trailingGeneratedBlockIsRemoved() {
        String message = "Add push reconciliation\n\nReconciles against an advanced target branch.\n\n"
                + "🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\n"
                + "Co-Authored-By: Claude <noreply@anthropic.com>\n\n";
        assertEquals("Add push reconciliation\n\nReconciles against an advanced target branch.",
                AuthorAttribution.sanitize(message));
    }

    /** A lowercase, differently spaced trailer is still recognized and removed. */
    @Test(timeout = 30000)
    public void trailerVariantsAreRecognized() {
        assertTrue(AuthorAttribution.isAttributionLine("co-authored-by: someone <a@b.com>"));
        assertTrue(AuthorAttribution.isAttributionLine("  Assisted-By: an agent"));
        assertTrue(AuthorAttribution.isAttributionLine(
                "Generated with Claude Code"));
        assertEquals("Tighten the retry loop",
                AuthorAttribution.sanitize("Tighten the retry loop\n\nco-authored-by: someone <a@b.com>"));
    }

    /** Attribution welded into a line of prose cannot be removed safely. */
    @Test(timeout = 30000)
    public void inlineAttributionIsRefused() {
        String message = "Fix the sampler, generated with Claude Code\n\nBody text.";
        assertTrue(AuthorAttribution.containsAttribution(message));
        assertNull(AuthorAttribution.sanitize(message));
    }

    /** An attribution line with message content after it cannot be removed safely. */
    @Test(timeout = 30000)
    public void midBodyAttributionIsRefused() {
        String message = "Fix the sampler\n\nCo-Authored-By: Claude <noreply@anthropic.com>\n\n"
                + "This paragraph is real content that follows the trailer.";
        assertNull(AuthorAttribution.sanitize(message));
    }

    /** A message consisting solely of attribution leaves nothing to commit. */
    @Test(timeout = 30000)
    public void attributionOnlyMessageIsRefused() {
        assertNull(AuthorAttribution.sanitize(
                "Co-Authored-By: Claude <noreply@anthropic.com>\n"));
    }

    /** An empty message is not attribution and is returned unchanged. */
    @Test(timeout = 30000)
    public void emptyMessageIsUnchanged() {
        assertEquals("", AuthorAttribution.sanitize(""));
        assertNull(AuthorAttribution.sanitize(null));
    }

    /** Ordinary prose that happens to start with "Generated with" is not attribution. */
    @Test(timeout = 30000)
    public void ordinaryProseIsNotAttribution() {
        String message = "Regenerate the fixtures\n\n"
                + "Generated with the new sampler script; created by hand previously.";
        assertFalse(AuthorAttribution.containsAttribution(message));
        assertEquals(message, AuthorAttribution.sanitize(message));
    }

    /** {@link AuthorAttribution#attributionLines} names every offending line, trimmed. */
    @Test(timeout = 30000)
    public void attributionLinesNamesOffendingContent() {
        List<String> lines = AuthorAttribution.attributionLines(
                "Subject\n\n  Co-Authored-By: Claude <noreply@anthropic.com>  \n"
                        + "🤖 Generated with [Claude Code](https://claude.com/claude-code)");
        assertEquals(2, lines.size());
        assertEquals("Co-Authored-By: Claude <noreply@anthropic.com>", lines.get(0));
        assertTrue(lines.get(1).endsWith("(https://claude.com/claude-code)"));
    }

    /** {@link AuthorAttribution#stripLines} removes attribution lines wherever they appear. */
    @Test(timeout = 30000)
    public void stripLinesRemovesAttributionAnywhere() {
        String message = "Subject\n\nCo-Authored-By: Claude <noreply@anthropic.com>\n\nBody stays.";
        assertEquals("Subject\n\n\nBody stays.", AuthorAttribution.stripLines(message));
    }
}
