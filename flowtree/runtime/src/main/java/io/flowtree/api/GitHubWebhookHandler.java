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

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.github.GitHubProxyHandler.BodyReader.InvalidRequestBodyException;
import io.flowtree.github.GitHubWebhookEvent;
import io.flowtree.github.GitHubWebhookSignature;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.NotifierRegistry.BranchResolution;
import io.flowtree.slack.SlackEventDeduplicator;
import io.flowtree.workstream.Workstream;
import org.almostrealism.io.ConsoleFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Receives GitHub webhook deliveries on {@code POST /api/github/webhook} and
 * announces the ones a workstream cares about in that workstream's Slack
 * channel.
 *
 * <p>Each delivery goes through these steps, in order:</p>
 * <ol>
 *   <li>Without a configured webhook secret every delivery is refused
 *       ({@link Outcome#NOT_CONFIGURED}): an endpoint that cannot verify
 *       deliveries must not act on them.</li>
 *   <li>The {@code X-Hub-Signature-256} header is verified against the raw
 *       body ({@link GitHubWebhookSignature}); an unsigned or badly signed
 *       delivery is refused.</li>
 *   <li>{@code ping}, which GitHub sends when the webhook is created, is
 *       answered and otherwise ignored.</li>
 *   <li>The {@code X-GitHub-Delivery} identifier is claimed. GitHub redelivers
 *       a delivery with the same identifier, both automatically and when an
 *       operator presses "Redeliver", so a second claim is answered as a
 *       {@link Outcome#DUPLICATE} without posting again.</li>
 *   <li>{@link GitHubWebhookEvent#notification()} decides whether the
 *       delivery is announced and renders the message; it is the one place
 *       that maps events to messages.</li>
 *   <li>The pull request's head branch and head repository are resolved to a
 *       workstream. Only a workstream that names that repository matches, so
 *       a fork's pull request from a branch of the same name never reaches an
 *       unrelated workstream. A delivery with no matching workstream is
 *       ignored and logged at debug level only, since a webhook configured on
 *       a repository sees every pull request, most of which no workstream
 *       owns.</li>
 * </ol>
 *
 * <p>A delivery that is claimed but then fails (a malformed body, a failed
 * post) releases its claim so a redelivery can succeed.</p>
 *
 * @author Michael Murray
 * @see GitHubWebhookEvent
 */
public final class GitHubWebhookHandler implements ConsoleFeatures {

    /** The path GitHub is configured to deliver to. */
    public static final String PATH = "/api/github/webhook";

    /**
     * How long a delivery identifier is remembered. An operator can redeliver
     * from GitHub's settings page long after the original delivery, so the
     * window is a day rather than the few minutes of an automatic retry.
     */
    public static final long DELIVERY_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(24);

    /** Debug-level logger for deliveries that match no workstream. */
    private static final Logger DEBUG = LoggerFactory.getLogger(GitHubWebhookHandler.class);

    /** What became of one delivery, and the HTTP status GitHub is answered with. */
    public enum Outcome {
        /** A message was posted to the matched workstream's channel. */
        POSTED(Response.Status.OK),
        /** Verified, but not announced or owned by no workstream. */
        IGNORED(Response.Status.OK),
        /** The {@code ping} sent when the webhook is created. */
        PONG(Response.Status.OK),
        /** A redelivery of a delivery that was already handled. */
        DUPLICATE(Response.Status.OK),
        /** No {@code X-Hub-Signature-256}, or a signature that does not match. */
        BAD_SIGNATURE(Response.Status.UNAUTHORIZED),
        /** No webhook secret is configured, so no delivery can be verified. */
        NOT_CONFIGURED(Response.Status.SERVICE_UNAVAILABLE),
        /** The body could not be read or is not JSON. */
        MALFORMED(Response.Status.BAD_REQUEST),
        /** Posting the message failed. */
        FAILED(Response.Status.INTERNAL_ERROR);

        /** The HTTP status for this outcome. */
        private final Response.Status status;

        /**
         * Creates an outcome answered with {@code status}.
         *
         * @param status the HTTP status
         */
        Outcome(Response.Status status) {
            this.status = status;
        }

        /**
         * Returns the response GitHub receives for this outcome.
         *
         * @return a JSON response carrying the outcome name
         */
        Response toResponse() {
            boolean ok = status == Response.Status.OK;
            return NanoHTTPD.newFixedLengthResponse(status, "application/json",
                    "{\"ok\":" + ok + ",\"outcome\":\"" + name().toLowerCase(Locale.ROOT) + "\"}");
        }
    }

    /** Resolves a delivery to its workstream and posts to that workstream's channel. */
    private final NotifierRegistry notifiers;

    /** Reads the raw body bytes the signature covers. */
    private final RequestBodyReader bodyReader;

    /** Remembers the delivery identifiers already handled. */
    private final SlackEventDeduplicator deliveries;

    /** Verifies deliveries; {@code null} until a secret is configured. */
    private volatile GitHubWebhookSignature signature;

    /**
     * Creates a handler with no secret; it refuses every delivery until
     * {@link #setSecret(String)} is called.
     *
     * @param notifiers  resolves workstreams and posts to their channels
     * @param bodyReader reads the raw request body
     */
    public GitHubWebhookHandler(NotifierRegistry notifiers, RequestBodyReader bodyReader) {
        this(notifiers, bodyReader, new SlackEventDeduplicator(DELIVERY_WINDOW_MILLIS));
    }

    /**
     * Creates a handler with an explicit delivery record, so a test can
     * choose the retention window.
     *
     * @param notifiers  resolves workstreams and posts to their channels
     * @param bodyReader reads the raw request body
     * @param deliveries remembers handled delivery identifiers
     */
    GitHubWebhookHandler(NotifierRegistry notifiers, RequestBodyReader bodyReader,
            SlackEventDeduplicator deliveries) {
        this.notifiers = notifiers;
        this.bodyReader = bodyReader;
        this.deliveries = deliveries;
    }

    /**
     * Sets the secret deliveries are signed with.
     *
     * @param secret the webhook secret configured on GitHub; {@code null} or
     *               blank leaves the endpoint refusing every delivery
     */
    public void setSecret(String secret) {
        this.signature = secret == null || secret.isBlank() ? null : new GitHubWebhookSignature(secret);
    }

    /**
     * Handles one HTTP delivery.
     *
     * @param session the NanoHTTPD session
     * @return the response for the delivery's {@link Outcome}
     */
    public Response handle(IHTTPSession session) {
        byte[] body;
        try {
            body = bodyReader.readBytes(session);
        } catch (IOException | InvalidRequestBodyException e) {
            warn("Unreadable GitHub webhook delivery: " + e.getMessage());
            return Outcome.MALFORMED.toResponse();
        }
        return accept(header(session, GitHubWebhookEvent.EVENT_HEADER),
                header(session, GitHubWebhookEvent.DELIVERY_HEADER),
                header(session, GitHubWebhookSignature.HEADER), body).toResponse();
    }

    /**
     * Verifies, deduplicates, matches and announces one delivery.
     *
     * @param eventType       the {@code X-GitHub-Event} value
     * @param deliveryId      the {@code X-GitHub-Delivery} value
     * @param signatureHeader the {@code X-Hub-Signature-256} value, or {@code null}
     * @param body            the raw body
     * @return what became of the delivery
     */
    public Outcome accept(String eventType, String deliveryId, String signatureHeader, byte[] body) {
        GitHubWebhookSignature verifier = signature;
        if (verifier == null) {
            warn("GitHub webhook delivery refused: no webhook secret is configured");
            return Outcome.NOT_CONFIGURED;
        }
        if (!verifier.verifies(body, signatureHeader)) {
            warn("GitHub webhook delivery refused: " + (signatureHeader == null
                    ? "unsigned" : "signature mismatch") + " (deliveryId=" + deliveryId + ")");
            return Outcome.BAD_SIGNATURE;
        }
        if (GitHubWebhookEvent.PING.equals(eventType)) return Outcome.PONG;
        if (deliveryId == null || deliveryId.isBlank()) {
            warn("GitHub webhook delivery refused: no " + GitHubWebhookEvent.DELIVERY_HEADER);
            return Outcome.MALFORMED;
        }
        if (!deliveries.claim(deliveryId)) return Outcome.DUPLICATE;

        GitHubWebhookEvent event;
        try {
            event = GitHubWebhookEvent.parse(eventType, body);
        } catch (IOException e) {
            deliveries.release(deliveryId);
            warn("GitHub webhook delivery is not JSON (deliveryId=" + deliveryId + "): " + e.getMessage());
            return Outcome.MALFORMED;
        }

        String message = event.notification();
        if (message == null) return Outcome.IGNORED;

        String workstreamId = workstreamIdFor(event);
        if (workstreamId == null) {
            DEBUG.debug("No workstream for {}.{} on branch={} repoUrl={}; ignored",
                    eventType, event.action(), event.headBranch(), event.headRepoUrl());
            return Outcome.IGNORED;
        }
        boolean posted;
        try {
            posted = notifiers.postWorkstreamNotice(workstreamId, message);
        } catch (RuntimeException e) {
            deliveries.release(deliveryId);
            warn("Failed to post GitHub " + eventType + "." + event.action()
                    + " to workstreamId=" + workstreamId, e);
            return Outcome.FAILED;
        }
        if (!posted) {
            deliveries.release(deliveryId);
            warn("Slack did not accept GitHub " + eventType + "." + event.action()
                    + " for workstreamId=" + workstreamId);
            return Outcome.FAILED;
        }
        log("Posted GitHub " + eventType + "." + event.action() + " to workstreamId=" + workstreamId);
        return Outcome.POSTED;
    }

    /**
     * Finds the workstream whose branch is the event's head branch and which
     * names the event's head repository.
     *
     * @param event the delivery
     * @return the workstream id, or {@code null} when none matches
     */
    private String workstreamIdFor(GitHubWebhookEvent event) {
        String branch = event.headBranch();
        String repoUrl = event.headRepoUrl();
        if (branch == null || repoUrl == null) return null;
        BranchResolution resolution = notifiers.resolveBranch(branch, repoUrl);
        if (resolution == null) return null;
        if (resolution.error() != null) {
            warn("GitHub webhook delivery not routed: " + resolution.error());
            return null;
        }
        Workstream match = resolution.match();
        return match != null && match.matchesRepo(repoUrl) ? match.getWorkstreamId() : null;
    }

    /**
     * Reads a request header; NanoHTTPD stores header names in lower case.
     *
     * @param session the session
     * @param name    the header name in any case
     * @return the value, or {@code null} when absent
     */
    private static String header(IHTTPSession session, String name) {
        return session.getHeaders().get(name.toLowerCase(Locale.ROOT));
    }
}
