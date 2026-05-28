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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.flowtree.workstream.Workstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link NotifierRegistry} resolves and aggregates lookups
 * across multiple per-workspace {@link SlackNotifier} instances.
 */
public class NotifierRegistryTest extends TestSuiteBase {

    private static Workstream newWorkstream(String id, String channelId) {
        Workstream ws = new Workstream(id, channelId, channelId);
        ws.setDefaultBranch("feature/" + id);
        return ws;
    }

    private static Workstream newWorkstream(String id, String channelId,
                                            String branch, String repoUrl) {
        Workstream ws = new Workstream(id, channelId, channelId);
        ws.setDefaultBranch(branch);
        ws.setRepoUrl(repoUrl);
        return ws;
    }

    private static SlackNotifier notifierWith(Workstream... workstreams) {
        SlackNotifier notifier = new SlackNotifier(null);
        for (Workstream ws : workstreams) {
            notifier.registerWorkstream(ws);
        }
        return notifier;
    }

    @Test(timeout = 10000)
    public void testSingleWorkspaceModeFallsBackToPrimary() {
        SlackNotifier primary = notifierWith(newWorkstream("ws-a", "C-a"));
        NotifierRegistry registry = new NotifierRegistry(primary, null);

        assertSame(primary, registry.notifierFor("ws-a"));
        assertSame(primary, registry.notifierFor("ws-missing"));
        assertSame(primary, registry.notifierForWorkspace(null));
        assertSame(primary, registry.notifierForWorkspace("T-anything"));
        assertTrue("Single-workspace mode reports isMultiWorkspace=false",
                !registry.isMultiWorkspace());
    }

    @Test(timeout = 10000)
    public void testMultiWorkspaceLookupRoutesByWorkstreamId() {
        SlackNotifier a = notifierWith(newWorkstream("ws-a", "C-a"));
        SlackNotifier b = notifierWith(newWorkstream("ws-b", "C-b"));
        Map<String, SlackNotifier> byWs = new LinkedHashMap<>();
        byWs.put("TAAA", a);
        byWs.put("TBBB", b);
        NotifierRegistry registry = new NotifierRegistry(a, byWs);

        assertSame("ws-a lives on workspace TAAA",
                a, registry.notifierFor("ws-a"));
        assertSame("ws-b lives on workspace TBBB",
                b, registry.notifierFor("ws-b"));
        assertEquals("TAAA", registry.workspaceIdFor("ws-a"));
        assertEquals("TBBB", registry.workspaceIdFor("ws-b"));
        assertNull("Unknown workstream resolves to null workspace",
                registry.workspaceIdFor("ws-missing"));
    }

    @Test(timeout = 10000)
    public void testAllWorkstreamsMergesAcrossWorkspaces() {
        SlackNotifier a = notifierWith(
                newWorkstream("ws-a", "C-a"),
                newWorkstream("ws-a2", "C-a2"));
        SlackNotifier b = notifierWith(newWorkstream("ws-b", "C-b"));
        Map<String, SlackNotifier> byWs = new LinkedHashMap<>();
        byWs.put("TAAA", a);
        byWs.put("TBBB", b);
        NotifierRegistry registry = new NotifierRegistry(a, byWs);

        Map<String, Workstream> all = registry.allWorkstreams();
        assertEquals(3, all.size());
        assertNotNull(all.get("ws-a"));
        assertNotNull(all.get("ws-a2"));
        assertNotNull(all.get("ws-b"));
    }

    @Test(timeout = 10000)
    public void testFindByBranchSearchesAllWorkspaces() {
        SlackNotifier a = notifierWith(newWorkstream("ws-a", "C-a"));
        SlackNotifier b = notifierWith(newWorkstream("ws-b", "C-b"));
        Map<String, SlackNotifier> byWs = new LinkedHashMap<>();
        byWs.put("TAAA", a);
        byWs.put("TBBB", b);
        NotifierRegistry registry = new NotifierRegistry(a, byWs);

        Workstream found = registry.findByBranch("feature/ws-b");
        assertNotNull("Workstream on second workspace must be discoverable",
                found);
        assertEquals("ws-b", found.getWorkstreamId());
    }

    @Test(timeout = 10000)
    public void testFindAllByBranchReturnsEveryWorkstreamSharingBranch() {
        // Two workstreams on different repositories register the same
        // defaultBranch.  Branch-only resolution must surface both so the
        // caller can disambiguate or reject; silently picking the first
        // would route jobs to the wrong repo.
        Workstream wsCommon = newWorkstream("ws-common", "C-1",
                "feature/audio-prototypes",
                "git@github.com:almostrealism/common.git");
        Workstream wsRings = newWorkstream("ws-rings", "C-2",
                "feature/audio-prototypes",
                "git@github.com:almostrealism/ringsdesktop.git");
        SlackNotifier a = notifierWith(wsCommon);
        SlackNotifier b = notifierWith(wsRings);
        Map<String, SlackNotifier> byWs = new LinkedHashMap<>();
        byWs.put("TAAA", a);
        byWs.put("TBBB", b);
        NotifierRegistry registry = new NotifierRegistry(a, byWs);

        List<Workstream> matches = registry.findAllByBranch("feature/audio-prototypes");
        assertEquals("Both workstreams share the branch", 2, matches.size());
        assertTrue(matches.contains(wsCommon));
        assertTrue(matches.contains(wsRings));

        // findByBranchAndRepo must only return the matching repo, never
        // the other workstream that happens to share the branch.
        assertSame(wsCommon, registry.findByBranchAndRepo(
                "feature/audio-prototypes",
                "git@github.com:almostrealism/common.git"));
        assertSame(wsRings, registry.findByBranchAndRepo(
                "feature/audio-prototypes",
                "git@github.com:almostrealism/ringsdesktop.git"));

        // A repo URL not registered on either workstream must miss.
        assertNull(registry.findByBranchAndRepo(
                "feature/audio-prototypes",
                "git@github.com:almostrealism/nope.git"));
    }

    @Test(timeout = 10000)
    public void testFindAllByBranchSingleWorkspaceMode() {
        Workstream wsA = newWorkstream("ws-a", "C-a",
                "feature/shared", "git@github.com:org/a.git");
        Workstream wsB = newWorkstream("ws-b", "C-b",
                "feature/shared", "git@github.com:org/b.git");
        SlackNotifier primary = notifierWith(wsA, wsB);
        NotifierRegistry registry = new NotifierRegistry(primary, null);

        List<Workstream> matches = registry.findAllByBranch("feature/shared");
        assertEquals(2, matches.size());
        assertTrue(matches.contains(wsA));
        assertTrue(matches.contains(wsB));
    }

    @Test(timeout = 10000)
    public void testFindAllByBranchEmptyResults() {
        SlackNotifier primary = notifierWith(newWorkstream("ws-a", "C-a"));
        NotifierRegistry registry = new NotifierRegistry(primary, null);

        assertTrue(registry.findAllByBranch("feature/missing").isEmpty());
        assertTrue(registry.findAllByBranch(null).isEmpty());
        assertTrue(registry.findAllByBranch("").isEmpty());
    }

    @Test(timeout = 10000)
    public void testNotifierForWorkspaceReturnsMatchingNotifier() {
        SlackNotifier a = new SlackNotifier(null);
        SlackNotifier b = new SlackNotifier(null);
        Map<String, SlackNotifier> byWs = new LinkedHashMap<>();
        byWs.put("TAAA", a);
        byWs.put("TBBB", b);
        NotifierRegistry registry = new NotifierRegistry(a, byWs);

        assertSame(a, registry.notifierForWorkspace("TAAA"));
        assertSame(b, registry.notifierForWorkspace("TBBB"));
        assertSame("Unknown workspace falls back to primary",
                a, registry.notifierForWorkspace("TUNKNOWN"));
    }
}
