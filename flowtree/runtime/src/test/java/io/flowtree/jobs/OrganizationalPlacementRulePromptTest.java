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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

/**
 * Locks in the wording of the organizational placement correction prompt so the
 * principles established in the {@code feature/agent-prompts} workstream cannot
 * silently regress. The key addition over the original prompt is P1 (improve the
 * area, do not police the diff): existing files that the branch substantially
 * modifies are also in scope for placement fixes, not just newly-created files.
 *
 * @author Michael Murray
 */
public class OrganizationalPlacementRulePromptTest extends TestSuiteBase {

    private static String prompt() {
        return OrganizationalPlacementRule.buildOrganizationalPlacementPrompt(
                Collections.singletonList(
                        "flowtree/runtime/src/main/java/io/flowtree/jobs/FooRule.java"));
    }

    // ── Core placement principles ────────────────────────────────────────────

    @Test(timeout = 30000)
    public void promptStatesLowestLevelPrinciple() {
        String text = prompt();
        assertTrue("Prompt should state the lowest-level principle",
                text.contains("LOWEST level of the organizational hierarchy"));
    }

    @Test(timeout = 30000)
    public void promptStatesUtilityClassPrinciple() {
        String text = prompt();
        assertTrue("Prompt should explain utility classes belong in a shared lower-level module",
                text.contains("utility class that could serve multiple modules"));
    }

    @Test(timeout = 30000)
    public void promptStatesDependencyHierarchyPrinciple() {
        String text = prompt();
        assertTrue("Prompt should warn about circular dependencies",
                text.contains("circular dependency"));
    }

    @Test(timeout = 30000)
    public void promptStatesDocumentPlacementPrinciple() {
        String text = prompt();
        assertTrue("Prompt should describe where documents should be placed",
                text.contains("docs/plans/"));
    }

    // ── P1: existing files in scope (the key new principle) ─────────────────

    @Test(timeout = 30000)
    public void promptIncludesExistingFilesInScope() {
        String text = prompt();
        assertTrue("Prompt should state that existing files substantially modified "
                        + "by the branch are also in scope",
                text.contains("EXISTING files your branch substantially modifies are also in scope"));
    }

    @Test(timeout = 30000)
    public void promptRejectsPreDatedFileRationalization() {
        String text = prompt();
        assertTrue("Prompt should reject the 'file pre-dated my branch' rationalization",
                text.contains("\"The file pre-dated my branch\" is not a reason"));
    }

    @Test(timeout = 30000)
    public void promptReferencesCircularDependencyExceptionForExistingFiles() {
        String text = prompt();
        assertTrue("Prompt should acknowledge the circular-dependency constraint "
                        + "applies to existing-file moves too",
                text.contains("circular-dependency constraint in principle 3"));
    }

    // ── Closing instruction covers both new and existing files ───────────────

    @Test(timeout = 30000)
    public void closingInstructionCoversExistingFilesToo() {
        String text = prompt();
        assertTrue("Closing instruction should mention existing files the branch modifies, "
                        + "not just new files",
                text.contains("every existing file your branch substantially modifies"));
    }

    // ── File list is rendered ────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void promptListsProvidedFiles() {
        String text = OrganizationalPlacementRule.buildOrganizationalPlacementPrompt(
                Arrays.asList("com/example/Foo.java", "com/example/Bar.java"));
        assertTrue("Prompt should include the first provided file path",
                text.contains("com/example/Foo.java"));
        assertTrue("Prompt should include the second provided file path",
                text.contains("com/example/Bar.java"));
    }
}
