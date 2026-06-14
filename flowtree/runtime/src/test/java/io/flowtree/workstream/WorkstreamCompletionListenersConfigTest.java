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

package io.flowtree.workstream;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import io.flowtree.workstream.WorkstreamEntry;

/**
 * Tests for the {@code completionListeners} field on
 * {@link WorkstreamEntry} and its
 * round-trip through YAML load / save.
 *
 * <p>The listener list is a new workstream-level field with the
 * same persistence shape as {@code dependentRepos} (a list of
 * strings). The listener graph is validated for cycles at
 * registration time by the controller; this test class only
 * exercises the YAML shape and the round-trip through
 * {@link Workstream#setCompletionListeners}.</p>
 */
public class WorkstreamCompletionListenersConfigTest extends TestSuiteBase {

    /**
     * A non-empty listener list survives a YAML save-and-reload
     * round-trip on the entry, and propagates to the runtime
     * {@link Workstream} when {@link WorkstreamEntry#toWorkstream()}
     * is called.
     */
    @Test(timeout = 10000)
    public void roundTripYmlPreservesListeners() throws IOException {
        String yaml = "workstreams:\n"
                + "  - workstreamId: \"ws-A\"\n"
                + "    channelId: \"C-A\"\n"
                + "    channelName: \"#A\"\n"
                + "    defaultBranch: \"feature/A\"\n"
                + "    completionListeners:\n"
                + "      - \"ws-orchestrator\"\n"
                + "      - \"ws-monitor\"\n";
        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamEntry entry = config.getWorkstreams().get(0);
        List<String> listeners = entry.getCompletionListeners();
        assertNotNull(listeners);
        assertEquals(2, listeners.size());
        assertTrue(listeners.contains("ws-orchestrator"));
        assertTrue(listeners.contains("ws-monitor"));
        // Convert to runtime Workstream.
        Workstream ws = entry.toWorkstream();
        assertEquals(2, ws.getCompletionListeners().size());
        assertTrue(ws.getCompletionListeners().contains("ws-orchestrator"));
        assertTrue(ws.getCompletionListeners().contains("ws-monitor"));
        // Save and reload; the round-trip preserves the field.
        File tempFile = File.createTempFile("ws-listeners", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamEntry reloadedEntry =
                reloaded.getWorkstreams().get(0);
        assertEquals(2, reloadedEntry.getCompletionListeners().size());
        assertTrue(reloadedEntry.getCompletionListeners()
                .contains("ws-orchestrator"));
        assertTrue(reloadedEntry.getCompletionListeners()
                .contains("ws-monitor"));
    }

    /**
     * A workstream entry without a {@code completionListeners} key
     * loads with an empty list (the inert default, the v0
     * behavior). The field defaults to an empty list rather
     * than {@code null} so callers can iterate without
     * null-guarding.
     */
    @Test(timeout = 10000)
    public void emptyListenersRoundTrip() throws IOException {
        String yaml = "workstreams:\n"
                + "  - workstreamId: \"ws-no-listeners\"\n"
                + "    channelId: \"C-X\"\n"
                + "    channelName: \"#X\"\n"
                + "    defaultBranch: \"feature/X\"\n";
        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamEntry entry = config.getWorkstreams().get(0);
        assertNotNull("entry must have a non-null listener list",
                entry.getCompletionListeners());
        assertTrue("entry must default to empty listener list",
                entry.getCompletionListeners().isEmpty());
        Workstream ws = entry.toWorkstream();
        assertNotNull(ws.getCompletionListeners());
        assertTrue(ws.getCompletionListeners().isEmpty());
        // The YAML should NOT include the field when empty (NON_EMPTY
        // serialisation policy is what WorkstreamConfig uses for
        // the rest of its fields, so the listener list rounds
        // through cleanly).
        File tempFile = File.createTempFile("ws-no-listeners-save", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        String written = new String(Files.readAllBytes(tempFile.toPath()));
        assertFalse("saved YAML must not contain 'completionListeners:'"
                + " when empty: " + written, written.contains("completionListeners:"));
    }

    /**
     * Setting {@code null} or an empty list on the runtime
     * {@link Workstream} is treated as "no listeners": the
     * getter returns the empty list, not {@code null}. The
     * setters do not silently retain stale state.
     */
    @Test(timeout = 10000)
    public void workstreamSetterHandlesNullAndEmpty() {
        Workstream ws = new Workstream("ws-setter", "C", "#c");
        ws.setCompletionListeners(null);
        assertNotNull(ws.getCompletionListeners());
        assertTrue(ws.getCompletionListeners().isEmpty());
        ws.setCompletionListeners(Arrays.asList("ws-x"));
        assertEquals(1, ws.getCompletionListeners().size());
        ws.setCompletionListeners(Collections.emptyList());
        assertTrue(ws.getCompletionListeners().isEmpty());
    }

    /**
     * The getter returns an unmodifiable view, so callers cannot
     * mutate the listener list by reaching into the Workstream.
     * This is a defensive property: a mutating getter would let
     * a caller bypass the cycle check by mutating the list
     * after registration.
     */
    @Test(timeout = 10000)
    public void getterReturnsUnmodifiableView() {
        Workstream ws = new Workstream("ws-mod", "C", "#c");
        ws.setCompletionListeners(Arrays.asList("ws-x"));
        List<String> view = ws.getCompletionListeners();
        try {
            view.add("ws-y");
            assertTrue("getter must return an unmodifiable view", false);
        } catch (UnsupportedOperationException expected) {
            // OK
        }
    }
}
