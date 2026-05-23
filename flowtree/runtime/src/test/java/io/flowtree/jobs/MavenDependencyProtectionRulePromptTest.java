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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks in the wording of the Maven dependency protection correction prompt so
 * the principles established in the {@code feature/agent-prompts} workstream
 * cannot silently regress. The key addition is the "EXPLAIN THE UNDERLYING NEED"
 * section: the prompt is purely prohibitive without it — it tells the agent to
 * revert the change but gives no guidance about documenting the design need that
 * drove the change, leaving the underlying issue invisible in the commit history.
 *
 * @author Michael Murray
 */
public class MavenDependencyProtectionRulePromptTest extends TestSuiteBase {

    private static String prompt() {
        return MavenDependencyProtectionRule.buildCorrectionPromptText("master");
    }

    // ── Blocking behaviour preserved ─────────────────────────────────────────

    @Test(timeout = 30000)
    public void promptStatesViolationHeader() {
        String text = prompt();
        assertTrue("Prompt should open with the violation header",
                text.contains("MAVEN DEPENDENCY PROTECTION RULE VIOLATION"));
    }

    @Test(timeout = 30000)
    public void promptRequiresMandatoryRevert() {
        String text = prompt();
        assertTrue("Prompt should require reverting all <dependency> changes",
                text.contains("MANDATORY ACTION: Revert all <dependency> changes"));
    }

    @Test(timeout = 30000)
    public void promptIncludesBaseBranchInDiffCommand() {
        String text = prompt();
        assertTrue("Prompt should embed the base branch name in the git diff command",
                text.contains("git diff origin/master"));
    }

    @Test(timeout = 30000)
    public void promptForbidsGitRestore() {
        String text = prompt();
        assertTrue("Prompt should forbid git restore / checkout -- / reset on pom.xml",
                text.contains("do NOT use git restore"));
    }

    @Test(timeout = 30000)
    public void promptPermitsNonDependencyChanges() {
        String text = prompt();
        assertTrue("Prompt should explicitly allow non-dependency pom.xml changes "
                        + "to be kept",
                text.contains("You MAY keep all non-dependency changes"));
    }

    // ── New: explain the underlying need ─────────────────────────────────────

    @Test(timeout = 30000)
    public void promptIncludesExplainUnderlyingNeedSection() {
        String text = prompt();
        assertTrue("Prompt should include an 'EXPLAIN THE UNDERLYING NEED' section",
                text.contains("EXPLAIN THE UNDERLYING NEED"));
    }

    @Test(timeout = 30000)
    public void promptAsksForGoalThatDroveChange() {
        String text = prompt();
        assertTrue("Prompt should ask the agent what goal drove the dependency change",
                text.contains("What goal drove the dependency change"));
    }

    @Test(timeout = 30000)
    public void promptAsksWhetherAlternativeExists() {
        String text = prompt();
        assertTrue("Prompt should ask whether the goal can be achieved without the dependency",
                text.contains("same goal can be expressed without adding the"));
    }

    @Test(timeout = 30000)
    public void promptInstructsAgentToFlagIfNoAlternative() {
        String text = prompt();
        assertTrue("Prompt should instruct the agent to flag areas where no "
                        + "dependency-free path is apparent",
                text.contains("flags the area for human review"));
    }

    // ── Base branch is embedded correctly ────────────────────────────────────

    @Test(timeout = 30000)
    public void promptUsesSuppliedBaseBranch() {
        String text = MavenDependencyProtectionRule.buildCorrectionPromptText("develop");
        assertTrue("Prompt should embed the supplied base branch name",
                text.contains("git diff origin/develop"));
        assertFalse("Prompt must not still reference 'master' when a different "
                        + "base branch was supplied",
                text.contains("origin/master"));
    }

    @Test(timeout = 30000)
    public void promptDefaultsToMasterForBlankBaseBranch() {
        String text = MavenDependencyProtectionRule.buildCorrectionPromptText(
                MavenDependencyProtectionRule.normalizeBaseBranch("   "));
        assertTrue("Prompt should fall back to master when base branch is blank",
                text.contains("git diff origin/master"));
    }
}
