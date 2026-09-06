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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for {@link AuthorAttribution} covering commit messages whose
 * prose merely <em>mentions</em> an assistant identity string
 * ({@code noreply@anthropic.com}, {@code claude.com/claude-code},
 * {@code claude.ai/code}) &mdash; for example a commit describing a change to
 * identity-handling code itself.
 *
 * <p>Such a mention is the agent's own description of its work, not author
 * attribution. The sanitization contract promises to remove attribution
 * "without touching a single character the agent wrote about its work" and to
 * fail loudly (return {@code null}) rather than silently alter an ambiguous
 * message. A previous {@code IDENTITY_LINE} pattern matched the identity string
 * anywhere in a line, so {@link AuthorAttribution#isAttributionLine(String)}
 * classified such a prose line as <em>entirely</em> attribution. That caused
 * {@link AuthorAttribution#sanitize(String)} to either silently drop the body
 * line (when it sat in the trailing block) or refuse the whole message (when it
 * sat above the trailing block) &mdash; corrupting or rejecting a legitimate
 * commit. These tests pin the corrected behaviour: a mention is left untouched,
 * while a bare identity line and genuine attribution remain fully detected.</p>
 */
public class AuthorAttributionIdentityMentionTest extends TestSuiteBase {

    /**
     * A trailing body line that mentions an identity string as data must be
     * preserved verbatim, not silently dropped as if it were an attribution
     * trailer.
     */
    @Test(timeout = 30000)
    public void trailingBodyMentionIsPreserved() {
        String message = "Fix identity matching\n\n"
                + "The backstop pattern now also handles noreply@anthropic.com in angle brackets.";
        assertFalse(AuthorAttribution.containsAttribution(message));
        assertEquals(message, AuthorAttribution.sanitize(message));
    }

    /**
     * A subject line that mentions an identity string as data must not cause the
     * whole message to be refused.
     */
    @Test(timeout = 30000)
    public void subjectMentionDoesNotRefuseMessage() {
        String message = "Broaden claude.com/claude-code detection to cover subpaths\n\n"
                + "Update the identity pattern accordingly.";
        assertFalse(AuthorAttribution.containsAttribution(message));
        assertEquals(message, AuthorAttribution.sanitize(message));
    }

    /** A line whose prose mentions an identity string is not, by itself, an attribution line. */
    @Test(timeout = 30000)
    public void proseMentionIsNotAnAttributionLine() {
        assertFalse(AuthorAttribution.isAttributionLine(
                "The backstop pattern now also handles noreply@anthropic.com in angle brackets."));
        assertFalse(AuthorAttribution.isAttributionLine(
                "Broaden claude.com/claude-code detection to cover subpaths"));
    }

    /**
     * A line that is nothing but a bare identity &mdash; on its own or wrapped in
     * non-letter decoration &mdash; is still recognized and removed, so narrowing
     * the pattern does not let a stray identity line slip through.
     */
    @Test(timeout = 30000)
    public void bareIdentityLineStillDetected() {
        assertTrue(AuthorAttribution.isAttributionLine("noreply@anthropic.com"));
        assertTrue(AuthorAttribution.isAttributionLine("<noreply@anthropic.com>"));
        assertTrue(AuthorAttribution.isAttributionLine("https://claude.com/claude-code"));
        assertTrue(AuthorAttribution.isAttributionLine("🤖 https://claude.com/claude-code"));
        assertEquals("Do the work",
                AuthorAttribution.sanitize("Do the work\n\nnoreply@anthropic.com"));
    }

    /**
     * The genuine attribution shapes the class exists to catch &mdash; a
     * {@code Co-Authored-By:} trailer and a "Generated with ... Claude" tool
     * credit &mdash; remain detected and removable after the narrowing.
     */
    @Test(timeout = 30000)
    public void genuineAttributionStillRemoved() {
        String message = "Add push reconciliation\n\nReconciles against an advanced target branch.\n\n"
                + "🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\n"
                + "Co-Authored-By: Claude <noreply@anthropic.com>\n";
        assertTrue(AuthorAttribution.containsAttribution(message));
        assertEquals("Add push reconciliation\n\nReconciles against an advanced target branch.",
                AuthorAttribution.sanitize(message));
    }
}
