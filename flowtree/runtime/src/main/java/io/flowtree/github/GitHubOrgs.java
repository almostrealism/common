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

import java.util.Map;

/**
 * Lookups against maps keyed by GitHub organisation name.
 *
 * <p>GitHub organisation names are case-insensitive: {@code github.com/Plytrix}
 * and {@code github.com/plytrix} address the same organisation, and GitHub
 * itself preserves whichever casing the org was created with while matching
 * either. Configuration in {@code workstreams.yaml} is hand-written, and the
 * repository URLs that arrive on API requests are produced by CI expressions
 * such as {@code git@github.com:${{ github.repository }}.git}, so the two
 * sides routinely disagree on casing for the same organisation.</p>
 *
 * <p>Every lookup of an org-keyed configuration map therefore has to be
 * case-insensitive. This class exists so that requirement lives in one place:
 * before it, {@link GitHubProxyHandler#resolveGithubToken(String)} compared
 * with {@code equalsIgnoreCase} while
 * {@code WorkstreamRegistrationHandler}'s org → workspace lookup and
 * {@code GitHubTokenValidator}'s per-workstream token lookup used exact
 * {@link Map#get(Object)}. The inconsistency was invisible for
 * all-lowercase orgs and broke workspace resolution for any org configured or
 * submitted with different casing.</p>
 *
 * @author  Michael Murray
 */
public final class GitHubOrgs {

    /** Not instantiable; the lookups are static. */
    private GitHubOrgs() { }

    /**
     * Returns the value mapped to {@code org}, comparing organisation names
     * without regard to case.
     *
     * <p>An exact match is preferred when present. Otherwise, when several
     * keys differ only by case — which a well-formed config should never
     * contain, since they name the same GitHub organisation — the last match
     * in the map's iteration order is returned, so that maps built with
     * "last write wins" merge semantics (such as
     * {@code WorkstreamConfig.mergedGithubOrgTokens()}) preserve the intended
     * override precedence rather than falling back to whichever entry
     * happened to be inserted first.</p>
     *
     * @param byOrg map keyed by GitHub org name; {@code null} is treated as empty
     * @param org   the org name to resolve; {@code null} or empty yields {@code null}
     * @param <V>   the mapped value type
     * @return the mapped value, or {@code null} if no key matches
     */
    public static <V> V lookup(Map<String, V> byOrg, String org) {
        if (byOrg == null || org == null || org.isEmpty()) return null;
        // TODO(review): can return a stale entry if it isn't the last-inserted key; see memory review-followup.
        if (byOrg.containsKey(org)) return byOrg.get(org);

        V match = null;

        for (Map.Entry<String, V> entry : byOrg.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(org)) {
                match = entry.getValue();
            }
        }

        return match;
    }
}
