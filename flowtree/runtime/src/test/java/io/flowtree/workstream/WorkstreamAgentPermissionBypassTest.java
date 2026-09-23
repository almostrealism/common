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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link Workstream#permitsAgentPermissionBypass(String)}, the policy
 * that decides which of a workstream's branches may run agent sessions that
 * bypass the agent runtime's permission prompts.
 *
 * <p>The point of the list is that the platform ships no branch-naming
 * convention of its own. One repository allows this on {@code ci/}, another on
 * {@code infra/}, most on nothing at all, and each says so here rather than
 * inheriting someone else's convention.</p>
 */
public class WorkstreamAgentPermissionBypassTest extends TestSuiteBase {

    /**
     * Builds a workstream whose policy is {@code prefixes}.
     *
     * @param prefixes the branch prefixes to configure; may be {@code null}
     * @return the configured workstream
     */
    private Workstream workstreamPermitting(List<String> prefixes) {
        Workstream ws = new Workstream();
        ws.setAgentPermissionBypassBranches(prefixes);
        return ws;
    }

    /** An unconfigured workstream permits nothing — the safe default. */
    @Test(timeout = 5000)
    public void unconfiguredWorkstreamPermitsNoBranch() {
        Workstream ws = new Workstream();

        assertTrue(ws.getAgentPermissionBypassBranches().isEmpty());
        assertFalse(ws.permitsAgentPermissionBypass("ci/tooling"));
        assertFalse(ws.permitsAgentPermissionBypass("master"));
    }

    /** A configured prefix permits the branches beneath it and nothing else. */
    @Test(timeout = 5000)
    public void configuredPrefixPermitsOnlyItsOwnBranches() {
        Workstream ws = workstreamPermitting(Collections.singletonList("ci/"));

        assertTrue(ws.permitsAgentPermissionBypass("ci/agent-phase-constraints"));
        assertTrue(ws.permitsAgentPermissionBypass("ci/"));
        assertFalse(ws.permitsAgentPermissionBypass("feature/something"));
        assertFalse(ws.permitsAgentPermissionBypass("master"));
        assertFalse("a prefix must not match mid-name",
                ws.permitsAgentPermissionBypass("feature/ci/nested"));
    }

    /** Each workstream picks its own convention; several prefixes may apply. */
    @Test(timeout = 5000)
    public void eachWorkstreamChoosesItsOwnPrefixes() {
        Workstream infra = workstreamPermitting(Arrays.asList("infra/", "tooling/"));

        assertTrue(infra.permitsAgentPermissionBypass("infra/runner-image"));
        assertTrue(infra.permitsAgentPermissionBypass("tooling/hooks"));
        assertFalse("another repository's convention must not leak in",
                infra.permitsAgentPermissionBypass("ci/whatever"));
    }

    /**
     * Blank entries are dropped rather than stored. An empty prefix is a
     * prefix of every branch name, so keeping one would turn a typo or a
     * trailing comma into a silent grant across the whole workstream.
     */
    @Test(timeout = 5000)
    public void blankPrefixesAreDroppedRatherThanMatchingEverything() {
        Workstream ws = workstreamPermitting(Arrays.asList("", "   ", null, "ci/"));

        assertEquals(Collections.singletonList("ci/"), ws.getAgentPermissionBypassBranches());
        assertFalse(ws.permitsAgentPermissionBypass("master"));
        assertTrue(ws.permitsAgentPermissionBypass("ci/x"));
    }

    /** Surrounding whitespace in a configured prefix is not part of the branch name. */
    @Test(timeout = 5000)
    public void prefixesAreTrimmed() {
        Workstream ws = workstreamPermitting(Collections.singletonList("  ci/  "));

        assertEquals(Collections.singletonList("ci/"), ws.getAgentPermissionBypassBranches());
        assertTrue(ws.permitsAgentPermissionBypass("ci/x"));
    }

    /** A job with no target branch has nowhere reviewable to land, so it is never permitted. */
    @Test(timeout = 5000)
    public void branchlessJobIsNeverPermitted() {
        Workstream ws = workstreamPermitting(Collections.singletonList("ci/"));

        assertFalse(ws.permitsAgentPermissionBypass(null));
        assertFalse(ws.permitsAgentPermissionBypass(""));
    }

    /** The configured list is a copy: mutating the caller's list cannot widen the policy. */
    @Test(timeout = 5000)
    public void configuredListIsCopiedNotAliased() {
        List<String> caller = new ArrayList<>(Collections.singletonList("ci/"));
        Workstream ws = workstreamPermitting(caller);

        caller.add("");
        caller.add("feature/");

        assertEquals(Collections.singletonList("ci/"), ws.getAgentPermissionBypassBranches());
        assertFalse(ws.permitsAgentPermissionBypass("feature/x"));
    }
}
