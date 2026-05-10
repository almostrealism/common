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

import java.util.List;

/**
 * Enforcement rule that verifies new files introduced since the base branch are
 * placed at the appropriate level of the organizational hierarchy.
 *
 * <p>Uses set-snapshot comparison (inherited from {@link SetComparisonRule}) to
 * determine when to stop looping: after a correction session that produces no
 * change in the new-file set, the rule considers the agent satisfied with the
 * current placement and marks itself resolved.</p>
 *
 * <p>Active when {@link ClaudeCodeJob#isEnforceOrganizationalPlacement()} is {@code true}
 * (the default).</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob#extractNewFilePaths()
 * @see SetComparisonRule
 * @see EnforcementRule
 */
class OrganizationalPlacementRule extends SetComparisonRule {

    @Override
    public String getName() { return "organizational-placement"; }

    @Override
    protected List<String> extractItems(ClaudeCodeJob job) {
        return job.extractNewFilePaths();
    }

    @Override
    public String buildCorrectionPrompt(ClaudeCodeJob job) {
        List<String> newFiles = job.extractNewFilePaths();
        StringBuilder sb = new StringBuilder();
        sb.append("ORGANIZATIONAL PLACEMENT REVIEW\n\n");
        sb.append("Your branch has introduced the following new files:\n\n");
        for (String file : newFiles) {
            sb.append("  ").append(file).append("\n");
        }
        sb.append("\nPlease review the placement of each file against these principles:\n\n");
        sb.append("1. Assets belong at the LOWEST level of the organizational hierarchy ");
        sb.append("where they can be useful. Do not place a class in a higher-level module ");
        sb.append("when a lower-level module would serve all its callers.\n\n");
        sb.append("2. A utility class that could serve multiple modules belongs in a shared ");
        sb.append("lower-level module, not in the specific module where it was first needed.\n\n");
        sb.append("3. Consider the dependency hierarchy — if placing a file in a lower module ");
        sb.append("would create a circular dependency, it is correctly placed at a higher level. ");
        sb.append("Do not move files that would introduce circular dependencies.\n\n");
        sb.append("4. Documents should be where they will be found: planning documents in ");
        sb.append("docs/plans/, API documentation near the code it describes, tutorials in ");
        sb.append("docs/internals/.\n\n");
        sb.append("If the placement of every listed file is already correct, do not move anything. ");
        sb.append("If any file should be moved, move it now using the Bash tool (git mv) and update ");
        sb.append("all import statements that reference the old location.");
        return sb.toString();
    }
}
