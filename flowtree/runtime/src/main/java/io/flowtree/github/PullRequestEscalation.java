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

package io.flowtree.github;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pull-request facts a cap-reached remediation ping lists so a person knows
 * what is still outstanding: the PR's page, its unresolved review threads, and
 * the TODO lines the branch added. Assembled by
 * {@link GitHubProxyHandler#escalationFor(String, String)} from GitHub and
 * rendered by {@code RemediationEscalation}.
 *
 * @param prUrl             the PR's HTML URL, or {@code null} when none was found
 * @param unresolvedThreads the PR's unresolved review threads, each a location
 *                          with a link
 * @param addedTodos        the TODO lines the branch added, each a location with
 *                          a link
 */
public record PullRequestEscalation(String prUrl, List<Finding> unresolvedThreads,
                                    List<Finding> addedTodos) {

    /** An empty escalation: no PR was found, so nothing is listed. */
    public static final PullRequestEscalation NONE =
            new PullRequestEscalation(null, List.of(), List.of());

    /**
     * Appends these facts to {@code message} as Markdown: the PR's page (when
     * known), then a titled, bulleted section for its unresolved review threads
     * and one for the TODO lines the branch added. An empty section is rendered
     * as {@code - none} rather than omitted, so the reader can tell "nothing
     * outstanding" from "nothing was looked up".
     *
     * @param message the message being built
     */
    public void appendTo(StringBuilder message) {
        if (prUrl != null) {
            message.append("\n\nPull request: ").append(prUrl);
        }
        appendSection(message, "Unresolved review threads", unresolvedThreads);
        appendSection(message, "TODOs added on this branch", addedTodos);
    }

    /**
     * Appends a titled, bulleted section for {@code findings}, or a line saying
     * there are none, so an empty list is explicit rather than silently omitted.
     *
     * @param message  the message being built
     * @param title    the section title
     * @param findings the findings to list
     */
    private static void appendSection(StringBuilder message, String title,
                                      List<Finding> findings) {
        message.append("\n\n").append(title).append(':');
        if (findings == null || findings.isEmpty()) {
            message.append("\n- none");
            return;
        }
        for (Finding finding : findings) {
            message.append('\n').append(finding.toBullet());
        }
    }

    /**
     * A single location worth a person's attention — a review thread's anchor or
     * an added TODO line — with the link that opens it.
     *
     * @param path   the repository-relative file path
     * @param line   the 1-based line, or {@code 0} when the location has none
     * @param url    the link that opens the thread or line on GitHub
     * @param detail a short description (the comment's or the TODO's text)
     */
    public record Finding(String path, int line, String url, String detail) {

        /** Matches a unified-diff hunk header, capturing the new-file start line. */
        private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)");

        /**
         * Matches a {@code TODO} marker as a word token, so {@code TODO},
         * {@code TODO:}, and {@code TODO(review)} match but an identifier such as
         * {@code TODOList} does not. Mirrors the {@code \bTODO\b} match in
         * {@code tools/ci/extract_branch_todos.py}.
         */
        private static final Pattern TODO_MARKER = Pattern.compile("\\bTODO\\b");

        /** Longest detail a review-thread finding carries, so a ping bullet stays short. */
        private static final int MAX_DETAIL_LENGTH = 120;

        /**
         * Returns the finding for an unresolved review thread, anchored at its
         * first comment, whose detail is the first non-blank line of that
         * comment's body, shortened to {@value #MAX_DETAIL_LENGTH} characters.
         *
         * @param path the file the comment is anchored to
         * @param line the comment's line, or {@code 0} when it has none
         * @param url  the comment's URL
         * @param body the comment's Markdown body; may be {@code null}
         * @return the finding
         */
        public static Finding reviewComment(String path, int line, String url, String body) {
            String detail = null;
            if (body != null) {
                for (String bodyLine : body.split("\n", -1)) {
                    String trimmed = bodyLine.strip();
                    if (!trimmed.isEmpty()) {
                        detail = trimmed.length() <= MAX_DETAIL_LENGTH ? trimmed
                                : trimmed.substring(0, MAX_DETAIL_LENGTH - 3) + "...";
                        break;
                    }
                }
            }
            return new Finding(path, line, url, detail);
        }

        /**
         * Returns one finding per added line of {@code patch} that carries a
         * {@code TODO} marker, at its new-file line number. New-file line numbers
         * are tracked from each hunk header; removed lines do not advance them.
         *
         * <p>The patch is the {@code patch} field of a pull request's file listing:
         * hunk bodies only, with no {@code diff --git}/{@code ---}/{@code +++} file
         * headers. Every {@code +} line is therefore an added line — including one
         * whose content itself begins with {@code ++} — and must be examined.</p>
         *
         * @param path    the file the patch belongs to (its new name when renamed)
         * @param patch   the GitHub file-patch body (hunks only, no file headers)
         * @param blobUrl the URL of the file at the PR head, to which each
         *                finding's {@code #L<line>} anchor is appended; may be
         *                {@code null}, in which case findings carry no link
         * @return the added TODO lines, in patch order
         */
        public static List<Finding> addedTodos(String path, String patch, String blobUrl) {
            List<Finding> todos = new ArrayList<>();
            int newLine = 0;
            for (String line : patch.split("\n", -1)) {
                if (line.startsWith("@@")) {
                    Matcher matcher = HUNK_HEADER.matcher(line);
                    if (matcher.find()) newLine = Integer.parseInt(matcher.group(1));
                    continue;
                }
                if (line.startsWith("+")) {
                    if (TODO_MARKER.matcher(line).find()) {
                        String url = blobUrl == null ? null : blobUrl + "#L" + newLine;
                        todos.add(new Finding(path, newLine, url, null));
                    }
                    newLine++;
                } else if (line.startsWith("-")) {
                    // A removed line: not present in the new file; does not advance.
                    continue;
                } else if (!line.startsWith("\\")) {
                    // A context line advances the new-file counter; a
                    // "\ No newline at end of file" marker does not.
                    newLine++;
                }
            }
            return todos;
        }

        /**
         * Renders the finding as one Markdown bullet, e.g.
         * {@code - `path:line` — detail (<url>)}.
         *
         * @return the bullet line
         */
        public String toBullet() {
            StringBuilder line = new StringBuilder("- `").append(path);
            if (this.line > 0) line.append(':').append(this.line);
            line.append('`');
            if (detail != null && !detail.isBlank()) line.append(" — ").append(detail.strip());
            if (url != null && !url.isBlank()) line.append(" (").append(url).append(')');
            return line.toString();
        }
    }
}
