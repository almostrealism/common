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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforcement rule that verifies new files introduced since the base branch are
 * placed at the appropriate level of the organizational hierarchy.
 *
 * <p>Uses file-set comparison to determine when to stop looping. Before each correction
 * session, {@link #isViolated} records the current set of new file paths. After the
 * session completes, {@link #onCorrectionAttempted} compares the post-session set with
 * the pre-session set. If the sets are identical, the agent is satisfied with placement
 * and the rule marks itself resolved so the loop exits on the next {@link #isViolated}
 * check. If the set changed (files were moved), another pass is made to confirm the
 * final placement is correct.</p>
 *
 * <p>Active when {@link ClaudeCodeJob#isEnforceOrganizationalPlacement()} is {@code true}
 * (the default).</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob#extractNewFilePaths()
 * @see EnforcementRule
 */
class OrganizationalPlacementRule implements EnforcementRule {

    /**
     * The set of new file paths recorded by the most recent {@link #isViolated} call.
     * Used by {@link #onCorrectionAttempted} to compare against the post-session set
     * and determine whether the agent moved any files.
     */
    private Set<String> fileSetBeforeSession = null;

    /**
     * Set to {@code true} by {@link #onCorrectionAttempted} when a correction session
     * completes without changing the file set, indicating the agent is satisfied with
     * the current placement of all new files. Once resolved, {@link #isViolated} returns
     * {@code false} immediately so the loop exits.
     */
    private boolean resolved = false;

    @Override
    public String getName() { return "organizational-placement"; }

    @Override
    public boolean isViolated(ClaudeCodeJob job) {
        if (resolved) return false;
        List<String> newFiles = job.extractNewFilePaths();
        fileSetBeforeSession = new LinkedHashSet<>(newFiles);
        return !newFiles.isEmpty();
    }

    /**
     * Compares the post-session file set against the pre-session snapshot recorded by
     * {@link #isViolated}. If the sets are equal, the agent moved nothing during the
     * correction session and the rule marks itself resolved so the loop exits on the
     * next {@link #isViolated} check.
     */
    @Override
    public void onCorrectionAttempted(ClaudeCodeJob job) {
        if (fileSetBeforeSession == null) return;
        Set<String> currentFileSet = new LinkedHashSet<>(job.extractNewFilePaths());
        if (currentFileSet.equals(fileSetBeforeSession)) {
            resolved = true;
        }
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
