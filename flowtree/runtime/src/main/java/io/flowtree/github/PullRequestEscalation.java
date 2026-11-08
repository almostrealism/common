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

import java.util.List;

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
