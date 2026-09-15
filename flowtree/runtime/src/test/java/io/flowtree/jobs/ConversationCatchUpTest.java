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

import io.flowtree.jobs.agent.AgentRunResult;
import io.flowtree.workstream.WorkstreamMailbox;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link ConversationCatchUp} hands a relaunched collaborative
 * session exactly the messages that followed its own last one, and that
 * {@link InstructionPromptBuilder} places them where the agent reads first.
 */
public class ConversationCatchUpTest extends TestSuiteBase {

    /** Mailbox read URL used by every test; the fetcher ignores it. */
    private static final String URL = "http://controller:7780/api/workstreams/ws-1/mailbox?since=0";

    /** Renders a mailbox body with the given messages in order. */
    private static String mailbox(String... messages) {
        return "{\"ok\":true,\"messages\":[" + String.join(",", messages) + "],\"nextSince\":9}";
    }

    /** One message line as the controller serialises it. */
    private static String message(long seq, String sender, String text) {
        return "{\"seq\":" + seq + ",\"createdAt\":\"2026-09-12T10:0" + seq + ":00Z\",\"sender\":\""
                + sender + "\",\"text\":\"" + text + "\"}";
    }

    /** Only what came after the job's own last message is missed. */
    @Test(timeout = 30000)
    public void missedMessagesFollowTheJobsOwnLastMessage() {
        String body = mailbox(
                message(1, "caller:michael", "get set up"),
                message(2, "job:j1", "ready"),
                message(3, "caller:michael", "skip the download, run the parity test"),
                message(4, "caller:michael", "are you there?"));

        List<WorkstreamMailbox.Message> missed =
                new ConversationCatchUp(URL, "job:j1", url -> body).missedMessages();

        assertEquals(2, missed.size());
        assertEquals(3L, missed.get(0).seq());
        assertEquals(4L, missed.get(1).seq());
    }

    /** A job that never spoke receives the recent history rather than nothing. */
    @Test(timeout = 30000)
    public void jobThatNeverSpokeReceivesTheHistory() {
        String body = mailbox(
                message(1, "caller:michael", "get set up"),
                message(2, "caller:michael", "still waiting"));

        List<WorkstreamMailbox.Message> missed =
                new ConversationCatchUp(URL, "job:j1", url -> body).missedMessages();

        assertEquals(2, missed.size());
    }

    /** Nothing after the job's own last message means nothing to catch up on. */
    @Test(timeout = 30000)
    public void nothingMissedWhenTheJobSpokeLast() {
        String body = mailbox(
                message(1, "caller:michael", "get set up"),
                message(2, "job:j1", "done, waiting"));

        ConversationCatchUp catchUp = new ConversationCatchUp(URL, "job:j1", url -> body);
        assertTrue(catchUp.missedMessages().isEmpty());
        assertEquals("", catchUp.render());
    }

    /** An unreachable controller or an unparsable body renders nothing rather than failing the launch. */
    @Test(timeout = 30000)
    public void unreadableConversationRendersNothing() {
        assertEquals("", new ConversationCatchUp(URL, "job:j1", url -> null).render());
        assertEquals("", new ConversationCatchUp(URL, "job:j1", url -> "not json").render());
        assertEquals("", new ConversationCatchUp((String) null, "job:j1", url -> {
            throw new AssertionError("must not fetch without a URL");
        }).render());
    }

    /** The rendering quotes sender, time and text, oldest first. */
    @Test(timeout = 30000)
    public void renderingQuotesEachMissedMessage() {
        String body = mailbox(
                message(1, "job:j1", "ready"),
                message(2, "caller:michael", "skip the download"),
                message(3, "caller:michael", "run the parity test"));

        String rendered = new ConversationCatchUp(URL, "job:j1", url -> body).render();

        assertTrue(rendered, rendered.indexOf("caller:michael: skip the download") >= 0);
        assertTrue(rendered, rendered.indexOf("skip the download") < rendered.indexOf("run the parity test"));
        assertTrue(rendered, rendered.contains("2026-09-12T10:02:00Z"));
        assertFalse(rendered, rendered.contains("ready"));
    }

    /** The mailbox URL is derived from either form of workstream URL, and from nothing otherwise. */
    @Test(timeout = 30000)
    public void mailboxUrlIsDerivedFromTheWorkstreamUrl() {
        assertEquals(URL, new ConversationCatchUp(
                "http://controller:7780/api/workstreams/ws-1/jobs/j1", "j1").getMailboxUrl());
        assertEquals(URL, new ConversationCatchUp(
                "http://controller:7780/api/workstreams/ws-1", "j1").getMailboxUrl());
        assertNull(new ConversationCatchUp((String) null, "j1").getMailboxUrl());
        assertNull(new ConversationCatchUp("http://controller:7780/other", "j1").getMailboxUrl());
    }

    /** The prompt preamble quotes the catch-up above the user request and tells the agent what to do with it. */
    @Test(timeout = 30000)
    public void promptPreambleQuotesTheCatchUp() {
        String prompt = new InstructionPromptBuilder()
                .setPrompt("Bring up the model server.")
                .setWorkstreamUrl("http://controller:7780/api/workstreams/ws-1")
                .setCollaborative(true)
                .setConversationCatchUp("- [t] caller:michael: skip the download\n")
                .build();

        int preamble = prompt.indexOf("MESSAGES ARRIVED WHILE YOU WERE DOWN");
        assertTrue(preamble >= 0);
        assertTrue(prompt.indexOf("skip the download") > preamble);
        assertTrue(prompt.indexOf("Act on the newest instruction") > preamble);
        assertTrue(prompt.indexOf("Bring up the model server.") > prompt.indexOf("Act on the newest instruction"));
    }

    /** No catch-up means no preamble. */
    @Test(timeout = 30000)
    public void promptOmitsThePreambleWithoutACatchUp() {
        String prompt = new InstructionPromptBuilder()
                .setPrompt("Bring up the model server.")
                .setCollaborative(true)
                .setConversationCatchUp("")
                .build();
        assertFalse(prompt.contains("MESSAGES ARRIVED WHILE YOU WERE DOWN"));
    }

    /** A job renders a catch-up only when it is collaborative and has run a session before. */
    @Test(timeout = 30000)
    public void jobRendersCatchUpOnlyOnACollaborativeRelaunch() {
        CodingAgentJob job = new CodingAgentJob("j1", "do the thing");
        assertNull("not collaborative", job.conversationCatchUp());

        job.setCollaborative(true);
        assertNull("first session has nothing to catch up on", job.conversationCatchUp());
    }

    /**
     * An inactivity relaunch runs inside the same logical session (it does not
     * increment {@link RestartGovernor#getSessionsLaunched()}), so the catch-up
     * decision must also look at {@link RestartGovernor#getInactivityRestartAttempt()}
     * — otherwise a relaunched attempt silently misses the conversation.
     */
    @Test(timeout = 30000)
    public void jobRendersCatchUpOnAnInactivityRelaunchWithinTheSameSession() {
        CodingAgentJob job = new CodingAgentJob("j1", "do the thing");
        job.setCollaborative(true);
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxInactivityRestarts(1);

        List<String> catchUpByAttempt = new ArrayList<>();
        gov.runWithInactivityRetries("claude", attempt -> {
            catchUpByAttempt.add(job.conversationCatchUp());
            return new AgentRunResult(0, attempt == 0, "out", "sid",
                    1000L, 0L, 1, 0.0, null, false, null, null);
        });

        assertNull("first attempt has nothing to catch up on", catchUpByAttempt.get(0));
        assertEquals(2, catchUpByAttempt.size());
        assertNotNull("relaunched attempt must render catch-up, not skip it",
                catchUpByAttempt.get(1));
    }
}
