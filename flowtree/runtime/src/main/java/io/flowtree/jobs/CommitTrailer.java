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

import java.util.regex.Pattern;

/**
 * A commit-message trailer that only the harness may write, such as
 * {@code Sensitive-File-Bypass} or {@code Merge-Resolution-Accepted}.
 *
 * <p>The agent controls {@code commit.txt}, so it can write any line it likes.
 * Every harness-owned trailer is therefore applied by stripping each line the
 * agent wrote that introduces the key, however disguised (any case, leading
 * or trailing whitespace before the colon), and only then appending the
 * harness's own value.</p>
 *
 * @author Michael Murray
 * @see SensitiveFileBypassTrailer
 * @see OperatorGrants
 */
final class CommitTrailer {

    /** The trailer key as it appears in the commit message. */
    private final String key;

    /** Matches any line that introduces {@link #key}, case-insensitively and with optional whitespace. */
    private final Pattern line;

    /**
     * Creates the trailer named {@code key}.
     *
     * @param key the trailer key, e.g. {@code "Sensitive-File-Bypass"}
     */
    CommitTrailer(String key) {
        this.key = key;
        this.line = Pattern.compile("(?im)^[ \t]*" + Pattern.quote(key) + "[ \t]*:.*$");
    }

    /**
     * Returns the trailer key.
     *
     * @return the key as it appears in the commit message
     */
    String key() {
        return key;
    }

    /**
     * Returns {@code message} with every line that introduces this trailer
     * removed, and the rest of the message preserved exactly.
     *
     * <p>A trailer line is dropped whole and the newline ending the line
     * before it is left in place; each surviving line keeps its own trailing
     * newline, and the split keeps trailing empty fields so the message's
     * final newline survives. An earlier version instead removed the newline
     * preceding each trailer so that the appended trailer would not leave a
     * blank gap, which was wrong for consecutive trailers: a second trailer
     * had no preceding newline left to remove, so the next ordinary line was
     * joined onto the previous one. Removing whole lines only preserves the
     * message structure however many trailers the agent wrote back to back.</p>
     *
     * @param message the original commit message; may be {@code null}
     * @return the message with this trailer's lines removed, never {@code null}
     */
    String strip(String message) {
        if (message == null || message.isEmpty()) return "";
        StringBuilder out = new StringBuilder(message.length());
        for (String text : message.split("\n", -1)) {
            if (line.matcher(text).matches()) {
                continue;
            }
            out.append(text).append('\n');
        }
        return out.toString();
    }

    /**
     * Returns {@code message} with any agent-supplied copy of this trailer
     * removed and, when {@code value} is present, the harness's own trailer
     * appended on its own line.
     *
     * @param message the commit message; may be {@code null}
     * @param value   the trailer value, or {@code null} to only strip
     * @return the message with the trailer applied, never {@code null}
     */
    String apply(String message, String value) {
        String cleaned = strip(message);
        if (value == null || value.isEmpty()) {
            return cleaned;
        }
        return cleaned + (cleaned.endsWith("\n") ? "" : "\n") + key + ": " + value + "\n";
    }
}
