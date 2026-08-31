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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Author-attribution content in a commit message &mdash; the
 * {@code Co-Authored-By:} trailers and tool-credit lines
 * ({@code Generated with ...}) that coding agents habitually append.
 *
 * <h2>Why it is prohibited</h2>
 * <p>Commits produced by the harness are authored by the configured git
 * identity (see {@link GitManagedJob#getGitUserName()}); the agent does not
 * get to claim or share authorship in the commit message. Attribution
 * trailers name a co-author who did not agree to be named, advertise a tool
 * in the permanent history of a public repository, and are noise in
 * {@code git log}. The agent's own instructions forbid them (see
 * {@link InstructionPromptBuilder}), but agents routinely add them anyway
 * because their base training and their default harness instructions tell
 * them to &mdash; so the harness sanitizes the message rather than trusting
 * the instruction to hold.</p>
 *
 * <h2>Sanitization contract</h2>
 * <p>{@link #sanitize(String)} removes attribution only when removal is
 * unambiguous: every attribution occurrence must be a whole line sitting in
 * the message's trailing block (at or after the last line of real content,
 * blank lines allowed between). That is the shape agents actually produce,
 * and it can be deleted without touching a single character the agent wrote
 * about its work.</p>
 *
 * <p>Anything else &mdash; attribution mixed into a line of prose, an
 * attribution line in the middle of the body with content after it, or a
 * message that is nothing but attribution &mdash; is <em>not</em> safely
 * removable: rewriting it would mean editing the agent's prose or guessing
 * what the message was supposed to say. In that case {@code sanitize}
 * returns {@code null} and the caller fails loudly. Silently committing the
 * attribution is never an option.</p>
 *
 * <p>Operating on the message text (rather than on a job) keeps the rule
 * usable from every point that handles a commit message &mdash; the
 * enforcement rule that inspects {@code commit.txt}, the builder that
 * resolves the final message, and any future consumer &mdash; and mirrors
 * {@link SensitiveFileBypassTrailer}, the other commit-message trailer the
 * harness strips before committing.</p>
 *
 * @author Michael Murray
 * @see CommitMessageBuilder
 * @see CommitMessageRule
 */
public final class AuthorAttribution {

    /**
     * A git trailer that names an author or co-author, e.g.
     * {@code Co-Authored-By: Claude <noreply@anthropic.com>}. Leading
     * non-letter characters (list markers, quoting, emoji) are tolerated so a
     * decorated line is still recognized.
     */
    private static final Pattern TRAILER_LINE = Pattern.compile(
            "(?i)^[^\\p{L}]*(?:co[- ]?)?(?:authored|written|assisted)[- ]?by[ \t]*:.*$");

    /**
     * A tool-credit line, e.g.
     * {@code Generated with [Claude Code](https://claude.com/claude-code)}.
     * The credited party must be a named assistant or vendor so that ordinary
     * prose beginning "Generated with ..." is not mistaken for attribution.
     */
    private static final Pattern TOOL_CREDIT_LINE = Pattern.compile(
            "(?i)^[^\\p{L}]*(?:generated|created|authored|produced|written)[- ]?(?:with|by)\\b.*"
                    + "\\b(?:claude|anthropic|copilot|chatgpt|openai|gpt-?[0-9]|codex|cursor|gemini)\\b.*$");

    /**
     * A line carrying an assistant identity &mdash; the agent's e-mail address
     * or product URL &mdash; anywhere within it.
     */
    private static final Pattern IDENTITY_LINE = Pattern.compile(
            "(?i)^.*(?:noreply@anthropic\\.com|claude\\.com/claude-code|claude\\.ai/code).*$");

    /**
     * Attribution recognizable inside a line that also carries other content.
     * Used to detect the unsafe case: attribution welded into the agent's own
     * prose, which cannot be deleted line-wise.
     */
    private static final Pattern INLINE_MARKER = Pattern.compile(
            "(?i)(?:co[- ]?)?(?:authored|written|assisted)[- ]?by[ \t]*:"
                    + "|noreply@anthropic\\.com|claude\\.com/claude-code|claude\\.ai/code"
                    + "|(?:generated|created|produced)[- ]?(?:with|by)[ \t]+"
                    + "(?:claude|anthropic|copilot|chatgpt|openai|codex|cursor|gemini)");

    /** Prevents instantiation; this class only exposes static helpers. */
    private AuthorAttribution() {
    }

    /**
     * Returns whether {@code line} is entirely author attribution &mdash; a
     * co-author trailer, a tool-credit line, or a line carrying an assistant
     * identity.
     *
     * @param line a single line of a commit message, without its line terminator
     * @return {@code true} when the whole line is attribution
     */
    public static boolean isAttributionLine(String line) {
        if (line == null) return false;
        return TRAILER_LINE.matcher(line).matches()
                || TOOL_CREDIT_LINE.matcher(line).matches()
                || IDENTITY_LINE.matcher(line).matches();
    }

    /**
     * Returns whether {@code message} carries author attribution anywhere
     * &mdash; as a whole line or embedded in a line of prose.
     *
     * @param message the commit message to inspect; may be {@code null}
     * @return {@code true} when any attribution is present
     */
    public static boolean containsAttribution(String message) {
        return !attributionLines(message).isEmpty();
    }

    /**
     * Returns the attribution content found in {@code message}, one offending
     * line per element, for use in log output, correction prompts, and failure
     * messages.
     *
     * @param message the commit message to inspect; may be {@code null}
     * @return the offending lines, trimmed, in document order; empty when there are none
     */
    public static List<String> attributionLines(String message) {
        List<String> found = new ArrayList<>();
        if (message == null || message.isEmpty()) return found;
        for (String line : message.split("\n", -1)) {
            if (isAttributionLine(line) || INLINE_MARKER.matcher(line).find()) {
                found.add(line.trim());
            }
        }
        return found;
    }

    /**
     * Returns {@code message} with its trailing author-attribution lines
     * removed, or {@code null} when the attribution cannot be removed without
     * rewriting content the agent wrote.
     *
     * <p>Removal is performed only when every attribution occurrence is a whole
     * line in the trailing block &mdash; at or after the last line of real
     * content, separated from it by nothing but blank lines. Trailing blank
     * lines left behind by the removal are dropped with it, so the result ends
     * on the last line of the agent's own message.</p>
     *
     * <p>Attribution above the trailing block is never removed: a whole
     * attribution line there has message content after it, so deleting it
     * restructures the body, and an inline occurrence is welded into prose that
     * only its author can rewrite. A message that is nothing but attribution is
     * likewise unsafe &mdash; there is no message left once it is removed. Both
     * cases return {@code null}.</p>
     *
     * <p>Returns the message unchanged (aside from that trailing trim) when it
     * carries no attribution at all, so callers can always use the return
     * value.</p>
     *
     * @param message the commit message to sanitize; may be {@code null}
     * @return the sanitized message, or {@code null} when {@code message} is
     *         {@code null} or its attribution is not safely removable
     */
    public static String sanitize(String message) {
        if (message == null) return null;

        String[] lines = message.split("\n", -1);

        int lastContent = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty() && !isAttributionLine(lines[i])) {
                lastContent = i;
                break;
            }
        }

        if (lastContent < 0) {
            return message.trim().isEmpty() ? message : null;
        }

        for (int i = 0; i <= lastContent; i++) {
            if (isAttributionLine(lines[i]) || INLINE_MARKER.matcher(lines[i]).find()) {
                return null;
            }
        }

        StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i <= lastContent; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    /**
     * Returns {@code message} with every whole attribution line removed,
     * wherever it appears, leaving all other lines untouched.
     *
     * <p>This is the unconditional counterpart to {@link #sanitize(String)} and
     * is reserved for harness-authored text (such as the prompt-derived
     * fallback message), where there is no agent prose to preserve and no
     * reason to fail the job over a stray line. Agent-authored messages go
     * through {@link #sanitize(String)} instead, so that unsafe attribution is
     * reported rather than silently patched.</p>
     *
     * @param message the message to strip; may be {@code null}
     * @return the stripped message, or {@code null} when {@code message} is {@code null}
     */
    public static String stripLines(String message) {
        if (message == null || message.isEmpty()) return message;
        StringBuilder out = new StringBuilder(message.length());
        boolean first = true;
        for (String line : message.split("\n", -1)) {
            if (isAttributionLine(line)) continue;
            if (!first) out.append('\n');
            out.append(line);
            first = false;
        }
        return out.toString();
    }
}
