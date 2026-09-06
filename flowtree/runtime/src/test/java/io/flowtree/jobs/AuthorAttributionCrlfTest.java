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
import static org.junit.Assert.assertNotNull;

/**
 * Regression tests for {@link AuthorAttribution} covering commit messages whose
 * lines are terminated with {@code \r\n} (CRLF) rather than a bare {@code \n}.
 *
 * <p>{@link CommitMessageBuilder#resolve(CodingAgentJob)} reads {@code commit.txt}
 * with {@code Files.readString(...).trim()} and performs no line-ending
 * normalization, so an agent that writes {@code commit.txt} with Windows line
 * endings hands CRLF text straight to {@link AuthorAttribution#sanitize(String)}.
 * The sanitizer must strip a trailing author-attribution block regardless of the
 * line terminator: its contract is defined in terms of lines, and CRLF is a
 * line ending, not content. These tests pin that behaviour so a stray {@code \r}
 * can never let a {@code Generated with ...} credit or a {@code Co-Authored-By:}
 * trailer reach the committed message.</p>
 */
public class AuthorAttributionCrlfTest extends TestSuiteBase {

    /**
     * The full generated block — a tool-credit line above a {@code Co-Authored-By:}
     * trailer — is removed when the message uses CRLF line endings, exactly as it
     * is for the LF form. Before the fix, the trailing {@code \r} on the interior
     * credit line defeated the anchored whole-line patterns, so the credit line was
     * misclassified as content and survived into the returned message.
     */
    @Test(timeout = 30000)
    public void trailingGeneratedBlockWithCrlfIsRemoved() {
        String message = ("Add push reconciliation\n\nReconciles against an advanced target branch.\n\n"
                + "🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\n"
                + "Co-Authored-By: Claude <noreply@anthropic.com>\n").replace("\n", "\r\n").trim();

        String sanitized = AuthorAttribution.sanitize(message);

        assertNotNull(sanitized);
        assertFalse("sanitized message must not retain attribution",
                AuthorAttribution.containsAttribution(sanitized));
        assertEquals("Add push reconciliation\n\nReconciles against an advanced target branch.",
                sanitized);
    }

    /**
     * A trailing {@code Co-Authored-By:} trailer sitting above a blank line is
     * removed when the message uses CRLF line endings.
     */
    @Test(timeout = 30000)
    public void trailingCoAuthorTrailerWithCrlfIsRemoved() {
        String message = ("Tighten the retry loop\n\nco-authored-by: someone <a@b.com>\n")
                .replace("\n", "\r\n").trim();

        assertEquals("Tighten the retry loop", AuthorAttribution.sanitize(message));
    }
}
