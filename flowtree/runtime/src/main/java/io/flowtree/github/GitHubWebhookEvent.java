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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

/**
 * One GitHub webhook delivery: the event name from the {@code X-GitHub-Event}
 * header and the parsed JSON payload.
 *
 * <p>This type is the single place that decides which deliveries are worth
 * announcing and what the announcement says. {@link #NOTIFICATIONS} maps an
 * {@code event.action} key to the function that renders the message; a
 * delivery whose key is absent produces no message. Adding a new kind of
 * announcement (for example {@code pull_request_review.submitted}) is one entry
 * in that map, plus — for an event whose payload does not carry a
 * {@code pull_request} object, such as {@code check_suite} — a branch for it in
 * {@link #headBranch()} and {@link #headRepoUrl()}.</p>
 *
 * @param eventType the {@code X-GitHub-Event} header value, such as
 *                  {@code pull_request} or {@code ping}
 * @param payload   the delivery's JSON body
 * @author Michael Murray
 */
public record GitHubWebhookEvent(String eventType, JsonNode payload) {

    /** The name of the header carrying the event type. */
    public static final String EVENT_HEADER = "X-GitHub-Event";

    /** The name of the header carrying the unique delivery identifier. */
    public static final String DELIVERY_HEADER = "X-GitHub-Delivery";

    /** The event GitHub sends once when a webhook is created. */
    public static final String PING = "ping";

    /** Parses delivery payloads. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The announced deliveries, keyed by {@code event.action}, each mapped to the
     * function that renders its Slack message. The only place that mapping lives.
     */
    private static final Map<String, Function<GitHubWebhookEvent, String>> NOTIFICATIONS = Map.of(
            "pull_request.closed", GitHubWebhookEvent::closedMessage,
            "pull_request.converted_to_draft",
                    e -> e.pullRequest().slackLink() + " converted to draft by " + e.senderLogin(),
            "pull_request.ready_for_review",
                    e -> e.pullRequest().slackLink() + " marked ready for review by " + e.senderLogin());

    /**
     * Parses a delivery body.
     *
     * @param eventType the {@code X-GitHub-Event} header value
     * @param body      the raw JSON body
     * @return the parsed event
     * @throws IOException when the body is not JSON, is empty, or is not a
     *         JSON object (Jackson returns a {@code null} tree for an empty
     *         body rather than throwing)
     */
    public static GitHubWebhookEvent parse(String eventType, byte[] body) throws IOException {
        JsonNode payload = MAPPER.readTree(body);
        if (payload == null || !payload.isObject()) {
            throw new IOException("webhook body is not a JSON object");
        }
        return new GitHubWebhookEvent(eventType, payload);
    }

    /**
     * Returns the delivery's {@code action}, such as {@code closed}.
     *
     * @return the action, or an empty string for an event that has none
     */
    public String action() {
        return payload.path("action").asText("");
    }

    /**
     * Returns the pull request the delivery describes.
     *
     * @return the pull request, or {@code null} when the payload carries none
     */
    public PullRequest pullRequest() {
        JsonNode pr = payload.path("pull_request");
        return pr.isObject() ? new PullRequest(pr) : null;
    }

    /**
     * Returns the branch the delivery concerns, used to find its workstream.
     *
     * @return the pull request's head branch, or {@code null} when the
     *         payload names none
     */
    public String headBranch() {
        PullRequest pr = pullRequest();
        return pr == null ? null : pr.headBranch();
    }

    /**
     * Returns the repository holding {@link #headBranch()}, used together with
     * the branch to find the workstream. A fork's pull request names the fork.
     *
     * @return the head repository clone URL, or {@code null} when absent
     */
    public String headRepoUrl() {
        PullRequest pr = pullRequest();
        return pr == null ? null : pr.headRepoUrl();
    }

    /**
     * Returns the Slack message announcing this delivery.
     *
     * @return the message text (Slack mrkdwn), or {@code null} when this kind
     *         of delivery is not announced
     */
    public String notification() {
        Function<GitHubWebhookEvent, String> render = NOTIFICATIONS.get(eventType + "." + action());
        return render == null || pullRequest() == null ? null : render.apply(this);
    }

    /**
     * Renders a {@code pull_request.closed} delivery, which GitHub sends both
     * for a merge and for a close without a merge.
     *
     * @return the message text
     */
    private String closedMessage() {
        PullRequest pr = pullRequest();
        if (pr.isMerged()) {
            String by = pr.mergedBy() != null ? pr.mergedBy() : senderLogin();
            return pr.slackLink() + " merged into " + pr.baseBranchCode() + " by " + by;
        }
        return pr.slackLink() + " closed without merging by " + senderLogin();
    }

    /**
     * Returns the login of the user whose action caused the delivery.
     *
     * @return the {@code sender.login}, or {@code someone} when absent
     */
    private String senderLogin() {
        return payload.path("sender").path("login").asText("someone");
    }
}
