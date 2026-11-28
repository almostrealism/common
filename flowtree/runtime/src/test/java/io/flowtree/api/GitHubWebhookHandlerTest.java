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
import io.flowtree.api.GitHubWebhookHandler.Outcome;
import io.flowtree.github.GitHubWebhookSignature;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackEventDeduplicator;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GitHubWebhookHandler}: signature checking, delivery
 * deduplication, routing a pull request event to the workstream that owns its
 * head branch and repository, and ignoring an event no workstream owns.
 *
 * <p>Routing goes through a real {@link NotifierRegistry} over a
 * {@link SlackNotifier} without a bot token, whose message callback records
 * what would have been posted to Slack.</p>
 */
public class GitHubWebhookHandlerTest extends TestSuiteBase {

    /** The webhook secret the tests sign with. */
    private static final String SECRET = "test-webhook-secret";

    /** The repository the workstream is registered for. */
    private static final String REPO = "https://github.com/example-org/example-repo.git";

    /** The branch the workstream is registered for. */
    private static final String BRANCH = "feature/example";

    /** The workstream's Slack channel. */
    private static final String CHANNEL = "C0EXAMPLE";

    /** The JSON of each message the notifier would have posted. */
    private final List<String> posted = new ArrayList<>();

    /** The registry the handler routes through. */
    private NotifierRegistry registry;

    /** The handler under test, configured with {@link #SECRET}. */
    private GitHubWebhookHandler handler;

    /** Registers one workstream on {@link #BRANCH} of {@link #REPO} and builds the handler. */
    @Before
    public void setUp() {
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(posted::add);
        Workstream workstream = new Workstream("ws-example", CHANNEL, "#example");
        workstream.setDefaultBranch(BRANCH);
        workstream.setRepoUrl(REPO);
        notifier.registerWorkstream(workstream);
        registry = new NotifierRegistry(notifier, null);
        handler = new GitHubWebhookHandler(registry, new RequestBodyReader());
        handler.setSecret(SECRET);
    }

    /**
     * Builds a {@code pull_request} payload.
     *
     * @param action   the action, such as {@code closed}
     * @param branch   the head branch
     * @param repoUrl  the head repository clone URL
     * @param merged   whether the pull request was merged
     * @return the JSON body
     */
    private static byte[] pullRequestPayload(String action, String branch, String repoUrl, boolean merged) {
        String json = "{\"action\":\"" + action + "\",\"number\":42,"
                + "\"pull_request\":{\"number\":42,"
                + "\"html_url\":\"https://github.com/example-org/example-repo/pull/42\","
                + "\"title\":\"Add the example — café\","
                + "\"state\":\"" + ("closed".equals(action) ? "closed" : "open") + "\","
                + "\"merged\":" + merged + ","
                + "\"merged_at\":" + (merged ? "\"2026-01-01T00:00:00Z\"" : "null") + ","
                + "\"merged_by\":" + (merged ? "{\"login\":\"merger\"}" : "null") + ","
                + "\"head\":{\"ref\":\"" + branch + "\",\"repo\":{\"clone_url\":\"" + repoUrl + "\"}},"
                + "\"base\":{\"ref\":\"master\"}},"
                + "\"sender\":{\"login\":\"actor\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Delivers {@code body} signed with {@link #SECRET}.
     *
     * @param deliveryId the delivery identifier
     * @param body       the body
     * @return the outcome
     */
    private Outcome deliverSigned(String deliveryId, byte[] body) {
        return handler.accept("pull_request", deliveryId,
                new GitHubWebhookSignature(SECRET).sign(body), body);
    }

    /** A merged pull request on the workstream's branch posts a merge message to its channel. */
    @Test(timeout = 30000)
    public void mergedPullRequestPostsToWorkstreamChannel() {
        Outcome outcome = deliverSigned("d-merge", pullRequestPayload("closed", BRANCH, REPO, true));
        assertEquals(Outcome.POSTED, outcome);
        assertEquals(1, posted.size());
        String message = posted.get(0);
        assertTrue(message, message.contains("\"channel\":\"" + CHANNEL + "\""));
        assertTrue(message, message.contains("PR #42"));
        assertTrue(message, message.contains("merged into `master` by merger"));
    }

    /** A close without a merge says so rather than reporting a merge. */
    @Test(timeout = 30000)
    public void closedWithoutMergePostsClosedMessage() {
        assertEquals(Outcome.POSTED,
                deliverSigned("d-close", pullRequestPayload("closed", BRANCH, REPO, false)));
        assertEquals(1, posted.size());
        assertTrue(posted.get(0), posted.get(0).contains("closed without merging by actor"));
    }

    /** Converting to draft and marking ready for review each post a message. */
    @Test(timeout = 30000)
    public void draftTransitionsPost() {
        assertEquals(Outcome.POSTED,
                deliverSigned("d-draft", pullRequestPayload("converted_to_draft", BRANCH, REPO, false)));
        assertEquals(Outcome.POSTED,
                deliverSigned("d-ready", pullRequestPayload("ready_for_review", BRANCH, REPO, false)));
        assertEquals(2, posted.size());
        assertTrue(posted.get(0), posted.get(0).contains("converted to draft by actor"));
        assertTrue(posted.get(1), posted.get(1).contains("marked ready for review by actor"));
    }

    /** An unsigned delivery is refused and posts nothing. */
    @Test(timeout = 30000)
    public void unsignedDeliveryIsRejected() {
        byte[] body = pullRequestPayload("closed", BRANCH, REPO, true);
        assertEquals(Outcome.BAD_SIGNATURE, handler.accept("pull_request", "d-unsigned", null, body));
        assertTrue(posted.isEmpty());
    }

    /** A delivery signed with another secret, or whose body changed after signing, is refused. */
    @Test(timeout = 30000)
    public void badlySignedDeliveryIsRejected() {
        byte[] body = pullRequestPayload("closed", BRANCH, REPO, true);
        String wrongKey = new GitHubWebhookSignature("another-secret").sign(body);
        assertEquals(Outcome.BAD_SIGNATURE, handler.accept("pull_request", "d-bad", wrongKey, body));

        String signed = new GitHubWebhookSignature(SECRET).sign(body);
        byte[] tampered = pullRequestPayload("closed", BRANCH, REPO, false);
        assertEquals(Outcome.BAD_SIGNATURE, handler.accept("pull_request", "d-tampered", signed, tampered));

        assertEquals(Outcome.BAD_SIGNATURE, handler.accept("pull_request", "d-garbage", "sha256=zz", body));
        assertTrue(posted.isEmpty());
    }

    /** Without a configured secret no delivery is acted on, even one that carries a signature. */
    @Test(timeout = 30000)
    public void missingSecretRefusesEveryDelivery() {
        handler.setSecret(null);
        assertEquals(Outcome.NOT_CONFIGURED,
                deliverSigned("d-noconfig", pullRequestPayload("closed", BRANCH, REPO, true)));
        assertTrue(posted.isEmpty());
    }

    /** A redelivery with the same delivery identifier posts once; a new identifier posts again. */
    @Test(timeout = 30000)
    public void redeliveryIsDeduplicated() {
        byte[] body = pullRequestPayload("closed", BRANCH, REPO, true);
        assertEquals(Outcome.POSTED, deliverSigned("d-same", body));
        assertEquals(Outcome.DUPLICATE, deliverSigned("d-same", body));
        assertEquals(1, posted.size());
        assertEquals(Outcome.POSTED, deliverSigned("d-other", body));
        assertEquals(2, posted.size());
    }

    /** A delivery whose post fails releases its claim, so GitHub's redelivery is acted on. */
    @Test(timeout = 30000)
    public void failedPostReleasesDeliveryClaim() {
        int[] attempts = {0};
        registry.primary().setMessageCallback(json -> {
            if (attempts[0]++ == 0) throw new IllegalStateException("Slack unavailable");
            posted.add(json);
        });
        GitHubWebhookHandler failing = new GitHubWebhookHandler(registry,
                new RequestBodyReader(), new SlackEventDeduplicator(60000));
        failing.setSecret(SECRET);
        byte[] body = pullRequestPayload("closed", BRANCH, REPO, true);
        String header = new GitHubWebhookSignature(SECRET).sign(body);
        assertEquals(Outcome.FAILED, failing.accept("pull_request", "d-retry", header, body));
        assertEquals(Outcome.POSTED, failing.accept("pull_request", "d-retry", header, body));
        assertEquals(1, posted.size());
    }

    /**
     * A failed post that reports failure by returning {@code null} (the way
     * Slack reports a rejected message, without throwing) returns
     * {@link Outcome#FAILED} and releases the claim, so GitHub's redelivery is
     * acted on once the post succeeds.
     */
    @Test(timeout = 30000)
    public void postFailureWithoutThrowingReleasesClaim() {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream unresolvable = new Workstream("ws-nochannel", null, "#none");
        unresolvable.setDefaultBranch(BRANCH);
        unresolvable.setRepoUrl(REPO);
        notifier.registerWorkstream(unresolvable);
        NotifierRegistry nullChannel = new NotifierRegistry(notifier, null);
        GitHubWebhookHandler failing = new GitHubWebhookHandler(nullChannel,
                new RequestBodyReader(), new SlackEventDeduplicator(60000));
        failing.setSecret(SECRET);
        byte[] body = pullRequestPayload("closed", BRANCH, REPO, true);
        String header = new GitHubWebhookSignature(SECRET).sign(body);
        assertEquals(Outcome.FAILED, failing.accept("pull_request", "d-null", header, body));

        notifier.setMessageCallback(posted::add);
        notifier.getWorkstream("ws-nochannel").setChannelId(CHANNEL);
        assertEquals(Outcome.POSTED, failing.accept("pull_request", "d-null", header, body));
        assertEquals(1, posted.size());
    }

    /** A non-ping delivery with no delivery identifier is refused as malformed and posts nothing. */
    @Test(timeout = 30000)
    public void deliveryWithoutIdentifierIsRejected() {
        byte[] body = pullRequestPayload("closed", BRANCH, REPO, true);
        String header = new GitHubWebhookSignature(SECRET).sign(body);
        assertEquals(Outcome.MALFORMED, handler.accept("pull_request", null, header, body));
        assertEquals(Outcome.MALFORMED, handler.accept("pull_request", "  ", header, body));
        assertTrue(posted.isEmpty());
    }

    /** A signed but empty body is answered malformed rather than parsing to a null payload. */
    @Test(timeout = 30000)
    public void emptyBodyIsMalformed() {
        byte[] empty = new byte[0];
        String header = new GitHubWebhookSignature(SECRET).sign(empty);
        assertEquals(Outcome.MALFORMED, handler.accept("pull_request", "d-empty", header, empty));
        assertTrue(posted.isEmpty());
    }

    /** A contributor-controlled title is escaped, so it cannot inject a Slack mention or markup. */
    @Test(timeout = 30000)
    public void titleWithSlackMarkupIsEscaped() {
        String json = "{\"action\":\"closed\",\"number\":42,"
                + "\"pull_request\":{\"number\":42,"
                + "\"html_url\":\"https://github.com/example-org/example-repo/pull/42\","
                + "\"title\":\"<!channel> & <b>\","
                + "\"state\":\"closed\",\"merged\":true,"
                + "\"merged_at\":\"2026-01-01T00:00:00Z\",\"merged_by\":{\"login\":\"merger\"},"
                + "\"head\":{\"ref\":\"" + BRANCH + "\",\"repo\":{\"clone_url\":\"" + REPO + "\"}},"
                + "\"base\":{\"ref\":\"master\"}},\"sender\":{\"login\":\"actor\"}}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertEquals(Outcome.POSTED, deliverSigned("d-escape", body));
        assertEquals(1, posted.size());
        String message = posted.get(0);
        assertTrue(message, message.contains("&lt;!channel&gt; &amp; &lt;b&gt;"));
        assertTrue(message, !message.contains("<!channel>"));
    }

    /**
     * A contributor-controlled base branch cannot break out of the Slack code
     * span it is rendered in: a backtick in the ref name is removed, so a ref
     * such as {@code master`<!channel>} cannot close the span and activate the
     * injected mention.
     */
    @Test(timeout = 30000)
    public void baseBranchWithSlackMarkupCannotBreakOut() {
        String json = "{\"action\":\"closed\",\"number\":42,"
                + "\"pull_request\":{\"number\":42,"
                + "\"html_url\":\"https://github.com/example-org/example-repo/pull/42\","
                + "\"title\":\"Add the example\","
                + "\"state\":\"closed\",\"merged\":true,"
                + "\"merged_at\":\"2026-01-01T00:00:00Z\",\"merged_by\":{\"login\":\"merger\"},"
                + "\"head\":{\"ref\":\"" + BRANCH + "\",\"repo\":{\"clone_url\":\"" + REPO + "\"}},"
                + "\"base\":{\"ref\":\"master`<!channel>\"}},\"sender\":{\"login\":\"actor\"}}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertEquals(Outcome.POSTED, deliverSigned("d-baseref", body));
        assertEquals(1, posted.size());
        String message = posted.get(0);
        assertTrue(message, message.contains("merged into `master<!channel>` by merger"));
        assertTrue(message, !message.contains("`master`<!channel>`"));
    }

    /** A branch no workstream tracks is ignored without posting. */
    @Test(timeout = 30000)
    public void unmatchedBranchIsIgnored() {
        assertEquals(Outcome.IGNORED,
                deliverSigned("d-nobranch", pullRequestPayload("closed", "feature/unknown", REPO, true)));
        assertTrue(posted.isEmpty());
    }

    /** A fork's pull request from a branch of the same name does not reach the workstream. */
    @Test(timeout = 30000)
    public void sameBranchInAnotherRepositoryIsIgnored() {
        String fork = "https://github.com/someone-else/example-repo.git";
        assertEquals(Outcome.IGNORED,
                deliverSigned("d-fork", pullRequestPayload("closed", BRANCH, fork, true)));
        assertTrue(posted.isEmpty());
    }

    /** A workstream registered with an SSH URL matches the HTTPS clone URL GitHub sends. */
    @Test(timeout = 30000)
    public void sshRegisteredWorkstreamMatchesHttpsCloneUrl() {
        registry.workstreamFor("ws-example").setRepoUrl("git@github.com:example-org/example-repo.git");
        assertEquals(Outcome.POSTED,
                deliverSigned("d-ssh", pullRequestPayload("closed", BRANCH, REPO, true)));
        assertEquals(1, posted.size());
    }

    /** Actions that are not announced, and the creation ping, post nothing. */
    @Test(timeout = 30000)
    public void unannouncedEventsPostNothing() {
        assertEquals(Outcome.IGNORED,
                deliverSigned("d-sync", pullRequestPayload("synchronize", BRANCH, REPO, false)));
        byte[] ping = "{\"zen\":\"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Outcome.PONG, handler.accept("ping", "d-ping", new GitHubWebhookSignature(SECRET).sign(ping), ping));
        assertTrue(posted.isEmpty());
    }

    /**
     * Over HTTP, a body with non-ASCII characters and no charset in its
     * {@code Content-Type} still verifies, because the signature is checked
     * against the bytes as they arrived; an unsigned request is answered 401.
     *
     * @throws Exception if the endpoint cannot be started or reached
     */
    @Test(timeout = 30000)
    public void endpointVerifiesRawBodyOverHttp() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(posted::add);
        Workstream workstream = new Workstream("ws-http", CHANNEL, "#example");
        workstream.setDefaultBranch(BRANCH);
        workstream.setRepoUrl(REPO);
        notifier.registerWorkstream(workstream);
        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setGitHubWebhookSecret(SECRET);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        try {
            String url = "http://localhost:" + endpoint.getListeningPort() + GitHubWebhookHandler.PATH;
            byte[] body = pullRequestPayload("closed", BRANCH, REPO, true);
            String signature = new GitHubWebhookSignature(SECRET).sign(body);
            assertEquals(200, post(url, body, "d-http", signature));
            assertEquals(1, posted.size());
            assertTrue(posted.get(0), posted.get(0).contains("merged into `master` by merger"));
            assertEquals(401, post(url, body, "d-http-unsigned", null));
            assertEquals(1, posted.size());
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Posts a webhook delivery the way GitHub does.
     *
     * @param url        the endpoint URL
     * @param body       the body
     * @param deliveryId the delivery identifier
     * @param signature  the signature header, or {@code null} to omit it
     * @return the HTTP status code
     * @throws Exception if the request fails
     */
    private static int post(String url, byte[] body, String deliveryId, String signature) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-GitHub-Event", "pull_request");
        conn.setRequestProperty("X-GitHub-Delivery", deliveryId);
        if (signature != null) conn.setRequestProperty("X-Hub-Signature-256", signature);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        return conn.getResponseCode();
    }
}
