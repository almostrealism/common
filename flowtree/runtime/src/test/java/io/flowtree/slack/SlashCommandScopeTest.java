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

package io.flowtree.slack;

import io.flowtree.controller.JobStatsStore;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests which {@code /flowtree} subcommands need the invoking channel's
 * workstream: the general ones work from a channel that has none, and the rest
 * still say clearly that they need one.
 */
public class SlashCommandScopeTest extends TestSuiteBase {

    /** A channel with no workstream. */
    private static final String BARE_CHANNEL = "C_BARE";

    /** A channel bound to the workstream {@link #setUp()} registers. */
    private static final String WORKSTREAM_CHANNEL = "C_WS";

    /** The listener under test. */
    private SlackListener listener;

    /** The stats store behind the listener's notifier. */
    private JobStatsStore store;

    /** Replies sent by the most recent command. */
    private final List<String> replies = new ArrayList<>();

    /** Builds a listener whose notifier has a stats store and one registered workstream. */
    @Before
    public void setUp() throws Exception {
        File dir = Files.createTempDirectory("slash-scope").toFile();
        dir.deleteOnExit();
        store = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        store.initialize();

        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setStatsStore(store);
        Workstream workstream = new Workstream(WORKSTREAM_CHANNEL, "#scoped");
        workstream.setDefaultBranch("feature/scoped");
        notifier.registerWorkstream(workstream);

        listener = new SlackListener(notifier);
        listener.registerWorkstream(workstream);
    }

    /** Closes the stats store. */
    @After
    public void tearDown() {
        if (store != null) store.close();
    }

    /**
     * Runs a {@code /flowtree} command from a channel and returns its single reply.
     *
     * @param command   the text after {@code /flowtree}
     * @param channelId the invoking channel
     * @return the reply
     */
    private String run(String command, String channelId) {
        replies.clear();
        listener.handleSlashCommand(command, channelId, "#channel", replies::add);
        assertEquals("one reply to " + command, 1, replies.size());
        return replies.get(0);
    }

    /** {@code /flowtree stats} in a channel with no workstream shows the global stats. */
    @Test(timeout = 10000)
    public void statsWithoutAWorkstreamIsGlobal() {
        String reply = run("stats", BARE_CHANNEL);
        assertTrue(reply, reply.contains("All Workstreams"));
        assertFalse(reply, reply.contains("No workstream configured"));
    }

    /** In a workstream's channel {@code stats} shows that workstream, and {@code global} still forces the global view. */
    @Test(timeout = 10000)
    public void statsInAWorkstreamChannelIsScoped() {
        String scoped = run("stats", WORKSTREAM_CHANNEL);
        assertTrue(scoped, scoped.contains("#scoped"));
        assertFalse(scoped, scoped.contains("All Workstreams"));

        assertTrue(run("stats global", WORKSTREAM_CHANNEL).contains("All Workstreams"));
    }

    /** {@code /flowtree active} and its alias work from a channel with no workstream. */
    @Test(timeout = 10000)
    public void activeWithoutAWorkstreamWorks() {
        for (String command : new String[] {"active", "workstreams"}) {
            String reply = run(command, BARE_CHANNEL);
            assertFalse(reply, reply.contains("No workstream configured"));
            assertTrue(reply, reply.contains("last 7 days"));
        }
    }

    /** Commands that act on the channel's workstream say so, naming the command, when there is none. */
    @Test(timeout = 10000)
    public void workstreamCommandsNameWhatTheyNeed() {
        for (String command : new String[] {"info", "status", "task do it", "cancel", "config", "jobs", "archive"}) {
            String reply = run(command, BARE_CHANNEL);
            String name = command.split(" ")[0];
            assertTrue(command + ": " + reply, reply.contains("No workstream configured"));
            assertTrue(command + ": " + reply, reply.contains("`/flowtree " + name + "`"));
            assertTrue(command + ": " + reply, reply.contains("/flowtree setup"));
        }
    }

    /** The help text separates the commands that need a workstream from those that work anywhere. */
    @Test(timeout = 10000)
    public void helpGroupsCommandsByScope() {
        String help = run("help", BARE_CHANNEL);
        int scoped = help.indexOf("In a workstream's channel");
        int anywhere = help.indexOf("Anywhere");
        assertTrue(help, scoped >= 0 && anywhere > scoped);
        assertTrue(help, help.indexOf("/flowtree info") < anywhere);
        assertTrue(help, help.indexOf("/flowtree stats") > anywhere);
        assertTrue(help, help.indexOf("/flowtree active") > anywhere);
    }
}
