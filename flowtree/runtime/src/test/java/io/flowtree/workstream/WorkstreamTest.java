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

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the repository-identity predicates {@link Workstream} delegates to
 * {@link RepositoryBinding}: {@code dependsOnRepo} (new) alongside the existing
 * {@code matchesRepo}. Normalisation of the many git URL spellings is covered
 * by {@code GitOperationsTest}; these tests pin the workstream-level contract.
 */
public class WorkstreamTest extends TestSuiteBase {

    /** Builds a workstream naming {@code repoUrl} as its primary repo. */
    private static Workstream workstreamOn(String repoUrl) {
        Workstream ws = new Workstream(null, "#w-test");
        ws.setDefaultBranch("feature/shared");
        ws.setRepoUrl(repoUrl);
        return ws;
    }

    /**
     * A workstream depends on a repository it lists among its dependent repos,
     * and the comparison is by repository identity — an HTTPS/{@code .git}
     * spelling in the list matches an SSH query.
     */
    @Test(timeout = 10000)
    public void testDependsOnRepoMatchesByIdentity() {
        Workstream ws = workstreamOn("git@github.com:almostrealism/ringsdesktop.git");
        ws.setDependentRepos(List.of("https://github.com/almostrealism/common.git"));

        assertTrue("a dependent repo matches across URL spellings",
                ws.dependsOnRepo("git@github.com:almostrealism/common.git"));
        assertTrue("identical spelling matches too",
                ws.dependsOnRepo("https://github.com/almostrealism/common.git"));
        assertFalse("the primary repo is not a dependent repo",
                ws.dependsOnRepo("git@github.com:almostrealism/ringsdesktop.git"));
        assertFalse("an unlisted repo does not match",
                ws.dependsOnRepo("git@github.com:almostrealism/other.git"));
    }

    /**
     * A workstream with no dependent repos — null, empty, or queried with a
     * null/empty repo — never reports a dependency.
     */
    @Test(timeout = 10000)
    public void testDependsOnRepoHandlesAbsentInputs() {
        Workstream none = workstreamOn("git@github.com:almostrealism/ringsdesktop.git");
        assertFalse("a null dependentRepos list is no dependency",
                none.dependsOnRepo("git@github.com:almostrealism/common.git"));

        none.setDependentRepos(List.of());
        assertFalse("an empty dependentRepos list is no dependency",
                none.dependsOnRepo("git@github.com:almostrealism/common.git"));

        Workstream ws = workstreamOn("git@github.com:almostrealism/ringsdesktop.git");
        ws.setDependentRepos(List.of("git@github.com:almostrealism/common.git"));
        assertFalse("a null query repo matches nothing", ws.dependsOnRepo(null));
        assertFalse("an empty query repo matches nothing", ws.dependsOnRepo(""));
    }

    /**
     * {@code matchesRepo} and {@code dependsOnRepo} are independent: the primary
     * repo matches the former and the dependents match the latter, never
     * crossing over.
     */
    @Test(timeout = 10000)
    public void testMatchesRepoAndDependsOnRepoAreDistinct() {
        Workstream ws = workstreamOn("git@github.com:almostrealism/ringsdesktop.git");
        ws.setDependentRepos(List.of("git@github.com:almostrealism/common.git"));

        assertTrue(ws.matchesRepo("git@github.com:almostrealism/ringsdesktop.git"));
        assertFalse(ws.matchesRepo("git@github.com:almostrealism/common.git"));

        assertTrue(ws.dependsOnRepo("git@github.com:almostrealism/common.git"));
        assertFalse(ws.dependsOnRepo("git@github.com:almostrealism/ringsdesktop.git"));
    }
}
