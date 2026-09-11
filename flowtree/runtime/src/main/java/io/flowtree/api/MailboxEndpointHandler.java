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
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.workstream.MailboxRegistry;
import io.flowtree.workstream.WorkstreamMailbox;

import java.util.function.Function;

/**
 * Handles {@code GET /api/workstreams/{id}/mailbox} for
 * {@link FlowTreeApiEndpoint} — the read half of agent-to-agent conversation.
 *
 * <p>The write half is {@link MessageEndpointHandler}: everything sent with
 * {@code POST .../messages} lands in the same log this endpoint reads, so a
 * message is a notification to humans and a turn addressed to peers at once.</p>
 *
 * <p>Three query parameters shape a read:</p>
 * <ul>
 *   <li>{@code since} — the highest {@code seq} the reader has already seen. A
 *       negative value (the default) starts from the current head, so a reader
 *       joining a long conversation is not handed its whole history; zero
 *       replays everything, which is how a job submitted later catches up.</li>
 *   <li>{@code wait} — seconds to block when nothing new is available, capped
 *       by {@link WorkstreamMailbox#MAX_WAIT_SECONDS}. The read returns the
 *       moment a message is appended, so an instruction reaches a waiting peer
 *       in a round trip rather than at the end of a polling interval.</li>
 *   <li>{@code exclude} — a sender whose messages the reader does not want to
 *       hear back, which is how a participant avoids its own echo.</li>
 * </ul>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see WorkstreamMailbox
 */
public final class MailboxEndpointHandler {

    /** Resolves the workstream named in the path, to reject unknown ones. */
    private final NotifierRegistry notifiers;

    /** Holds the conversation being read. */
    private final MailboxRegistry mailboxes;

    /** Builds a 400 error response with the given message. */
    private final Function<String, Response> errorResponse;

    /**
     * Constructs a handler bound to the given registries.
     *
     * @param notifiers     the workspace notifier registry, used to resolve the
     *                      workstream
     * @param mailboxes     the workstream conversation registry
     * @param errorResponse 400-error response factory
     */
    MailboxEndpointHandler(NotifierRegistry notifiers, MailboxRegistry mailboxes,
                           Function<String, Response> errorResponse) {
        this.notifiers = notifiers;
        this.mailboxes = mailboxes;
        this.errorResponse = errorResponse;
    }

    /**
     * Handles a read of the workstream's conversation, blocking for up to the
     * requested {@code wait} when the reader has already caught up.
     *
     * @param session      the HTTP session
     * @param workstreamId the workstream identifier from the URL path
     * @return a JSON response carrying the messages and the reader's next cursor
     */
    public Response handle(IHTTPSession session, String workstreamId) {
        if (notifiers.workstreamFor(workstreamId) == null) {
            return errorResponse.apply("Unknown workstream: " + workstreamId);
        }

        WorkstreamMailbox.Delivery delivery = mailboxes.mailboxFor(workstreamId).read(
                RequestParameters.number(session, "since", -1),
                RequestParameters.first(session, "exclude", null),
                (int) RequestParameters.number(session, "wait", 0));

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", delivery.toJson());
    }
}
