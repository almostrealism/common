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

import io.flowtree.workstream.WorkstreamConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests case-insensitive resolution of GitHub organisation names.
 *
 * <p>These exist because org-keyed configuration was looked up two different
 * ways: {@link GitHubProxyHandler#resolveGithubToken(String)} compared with
 * {@code equalsIgnoreCase}, while the org → workspace lookup behind
 * {@code POST /api/workstreams} used an exact map {@code get}. An org whose
 * configured casing matched the repository URL — an all-lowercase org such as
 * {@code almostrealism} — worked either way, which is what kept the
 * inconsistency hidden. An org written {@code Plytrix} in one place and
 * {@code plytrix} in the other resolved a token but no workspace, and job
 * submission failed with "Could not determine target workspace".</p>
 */
public class GitHubOrgsTest extends TestSuiteBase {

    /** Workspaces config declaring an org with GitHub's own casing. */
    private static final String MIXED_CASE_ORG_YAML =
            "workspaces:\n"
            + "  - id: \"flowtree\"\n"
            + "    name: \"FlowTree\"\n"
            + "    githubOrgs:\n"
            + "      Plytrix:\n"
            + "        token: \"<REDACTED>\"\n"
            + "workstreams: []\n";

    /** Builds the org-keyed map the controller loads from YAML. */
    private Map<String, String> orgTokens(String org) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put(org, "<REDACTED>");
        return tokens;
    }

    /** A lookup key that differs from the configured key only by case still resolves. */
    @Test(timeout = 10000)
    public void lookupIgnoresCaseOfTheRequestedOrg() {
        assertEquals("<REDACTED>", GitHubOrgs.lookup(orgTokens("Plytrix"), "plytrix"));
    }

    /** The reverse pairing — lowercase config, mixed-case request — resolves too. */
    @Test(timeout = 10000)
    public void lookupIgnoresCaseOfTheConfiguredOrg() {
        assertEquals("<REDACTED>", GitHubOrgs.lookup(orgTokens("plytrix"), "Plytrix"));
    }

    /** A genuinely different org still misses; the match is on case only. */
    @Test(timeout = 10000)
    public void lookupDoesNotMatchAnUnrelatedOrg() {
        assertNull(GitHubOrgs.lookup(orgTokens("Plytrix"), "almostrealism"));
    }

    /** Absent inputs yield no match rather than throwing. */
    @Test(timeout = 10000)
    public void lookupToleratesAbsentInputs() {
        assertNull(GitHubOrgs.lookup(null, "Plytrix"));
        assertNull(GitHubOrgs.lookup(orgTokens("Plytrix"), null));
        assertNull(GitHubOrgs.lookup(orgTokens("Plytrix"), ""));
    }

    /**
     * When a merged map (built with "last write wins" semantics, as
     * {@code WorkstreamConfig.mergedGithubOrgTokens()} does) contains both an
     * exact-case key and an older case-insensitive duplicate, the exact match
     * must win rather than whichever entry was inserted first.
     */
    @Test(timeout = 10000)
    public void lookupPrefersExactMatchOverEarlierCaseInsensitiveEntry() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("Plytrix", "<STALE>");
        tokens.put("plytrix", "<CURRENT>");

        assertEquals("<CURRENT>", GitHubOrgs.lookup(tokens, "plytrix"));
    }

    /**
     * When no exact match exists but several keys differ only by case, the
     * last one in iteration order wins, matching "last write wins" merge
     * semantics rather than returning whichever was inserted first.
     */
    @Test(timeout = 10000)
    public void lookupPrefersLastCaseInsensitiveMatchWhenNoExactMatch() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("Plytrix", "<STALE>");
        tokens.put("PLYTRIX", "<CURRENT>");

        assertEquals("<CURRENT>", GitHubOrgs.lookup(tokens, "plytrix"));
    }

    /**
     * The regression itself: the org parsed out of a submitted repoUrl
     * resolves the owning workspace whichever casing each side used. The
     * repoUrl shape is the one CI submits, built from
     * {@code git@github.com:${{ github.repository }}.git}.
     */
    @Test(timeout = 10000)
    public void repoUrlResolvesTheOwningWorkspaceRegardlessOfCase() throws IOException {
        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(MIXED_CASE_ORG_YAML);
        Map<String, String> orgToWorkspaceId = config.orgToWorkspaceId();

        String mixed = GitHubProxyHandler.extractOrgFromRepoUrl(
                "git@github.com:Plytrix/plytrix-platform.git");
        String lower = GitHubProxyHandler.extractOrgFromRepoUrl(
                "https://github.com/plytrix/plytrix-platform.git");

        assertEquals("flowtree", GitHubOrgs.lookup(orgToWorkspaceId, mixed));
        assertEquals("flowtree", GitHubOrgs.lookup(orgToWorkspaceId, lower));
    }
}
