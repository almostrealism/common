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
 * Builds the full instruction prompt that wraps a user's request with
 * operational context for autonomous coding agent execution.
 *
 * <p>This builder extracts the prompt-assembly logic from
 * {@link ClaudeCodeJob#buildInstructionPrompt()} into a standalone,
 * reusable class. All configuration state is provided via setter methods
 * (which support chaining), and the final prompt string is produced by
 * calling {@link #build()}.</p>
 *
 * <p>Sections are conditionally included based on the builder's
 * configuration: Slack instructions appear only when a workstream URL is
 * configured, GitHub instructions only when the GitHub MCP is enabled,
 * commit instructions depend on whether a target branch is set, and
 * budget/turn/task/workstream context is included when available.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob
 */
public class InstructionPromptBuilder {

    private String prompt;
    private String workstreamUrl;
    private boolean gitHubMcpEnabled;
    private boolean protectTestFiles;
    private boolean enforceChanges;
    private int enforcementAttempt;
    private String baseBranch;
    private String targetBranch;
    private String workingDirectory;
    private boolean hasMergeConflicts;
    private List<String> mergeConflictFiles;
    private double maxBudgetUsd;
    private int maxTurns;
    private String taskId;
    private String planningDocument;

    /**
     * Sets the user's request prompt.
     *
     * @param prompt the user's request text
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setPrompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    /**
     * Sets the workstream URL for Slack communication sections.
     *
     * @param workstreamUrl the controller URL for the workstream
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setWorkstreamUrl(String workstreamUrl) {
        this.workstreamUrl = workstreamUrl;
        return this;
    }

    /**
     * Sets whether the GitHub MCP server is enabled.
     *
     * @param gitHubMcpEnabled true if GitHub MCP tools are available
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setGitHubMcpEnabled(boolean gitHubMcpEnabled) {
        this.gitHubMcpEnabled = gitHubMcpEnabled;
        return this;
    }

    /**
     * Sets whether test file protection is enabled.
     *
     * @param protectTestFiles true to include the test integrity policy section
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setProtectTestFiles(boolean protectTestFiles) {
        this.protectTestFiles = protectTestFiles;
        return this;
    }

    /**
     * Sets whether this job requires code changes to be considered complete.
     *
     * <p>When enabled, the "Non-Code Requests" and "Justifying No Code Changes"
     * sections are replaced with a strict message warning the agent that it MUST
     * produce code changes. This is used for auto-resolve jobs where the agent
     * is known to be responsible for fixing test failures.</p>
     *
     * @param enforceChanges true to require code changes for completion
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setEnforceChanges(boolean enforceChanges) {
        this.enforceChanges = enforceChanges;
        return this;
    }

    /**
     * Sets the enforcement attempt counter for retry-loop mode.
     *
     * <p>When greater than zero, a prominent message is prepended warning
     * the agent that it has already refused to fix the failures this many
     * times, and that the session will keep restarting until it produces
     * code changes or successfully runs the CI command.</p>
     *
     * @param enforcementAttempt the number of prior failed attempts (0 for first try)
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setEnforcementAttempt(int enforcementAttempt) {
        this.enforcementAttempt = enforcementAttempt;
        return this;
    }

    /**
     * Sets the base branch name used for merge conflict resolution
     * and test integrity policy references.
     *
     * @param baseBranch the base branch name (e.g., "master")
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
        return this;
    }

    /**
     * Sets the target branch for git operations.
     *
     * @param targetBranch the branch name (e.g., "feature/my-work")
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setTargetBranch(String targetBranch) {
        this.targetBranch = targetBranch;
        return this;
    }

    /**
     * Sets the working directory path.
     *
     * @param workingDirectory the absolute path to the working directory
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    /**
     * Sets whether there are merge conflicts to resolve.
     *
     * @param hasMergeConflicts true if the base branch has diverged and
     *                          a merge attempt produced conflicts
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setHasMergeConflicts(boolean hasMergeConflicts) {
        this.hasMergeConflicts = hasMergeConflicts;
        return this;
    }

    /**
     * Sets the list of files with merge conflicts.
     *
     * @param mergeConflictFiles list of file paths that have conflicts
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setMergeConflictFiles(List<String> mergeConflictFiles) {
        this.mergeConflictFiles = mergeConflictFiles;
        return this;
    }

    /**
     * Sets the maximum budget in USD.
     *
     * @param maxBudgetUsd the budget limit (0 or negative to omit)
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setMaxBudgetUsd(double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
        return this;
    }

    /**
     * Sets the maximum number of turns.
     *
     * @param maxTurns the turn limit (0 or negative to omit)
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
        return this;
    }

    /**
     * Sets the task ID for tracking.
     *
     * @param taskId the task identifier
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setTaskId(String taskId) {
        this.taskId = taskId;
        return this;
    }

    /**
     * Sets the planning document path.
     *
     * @param planningDocument path relative to the working directory
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setPlanningDocument(String planningDocument) {
        this.planningDocument = planningDocument;
        return this;
    }

    /**
     * Builds the full instruction prompt by assembling all configured
     * sections into a single string.
     *
     * <p>The sections are assembled in the following order:</p>
     * <ol>
     *   <li>Opening paragraph (autonomous agent context)</li>
     *   <li>Slack Communication (when workstream URL is set)</li>
     *   <li>Permission Denials (when workstream URL is set)</li>
     *   <li>Non-Code Requests (when workstream URL is set)</li>
     *   <li>Justifying No Code Changes (when workstream URL is set)</li>
     *   <li>GitHub instructions (when GitHub MCP is enabled)</li>
     *   <li>Test Integrity Policy (when test file protection is enabled)</li>
     *   <li>Git commit instructions (conditional on target branch)</li>
     *   <li>Merge Conflicts (when merge conflicts are present)</li>
     *   <li>Branch Context (always)</li>
     *   <li>Working directory and branch info</li>
     *   <li>Branch Awareness and Continuity (when target branch is set)</li>
     *   <li>Budget and turn limits (when applicable)</li>
     *   <li>Task ID and Workstream URL info</li>
     *   <li>Planning Document (when set)</li>
     *   <li>BEGIN/END USER REQUEST markers wrapping the prompt</li>
     * </ol>
     *
     * @return the fully assembled instruction prompt string
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // Enforcement retry warning -- prepended above everything else so the
        // agent sees it immediately.  This is used when the job has been
        // restarted because a previous attempt produced no code changes.
        if (enforcementAttempt > 0) {
            sb.append("## !! SESSION RESTARTED -- ATTEMPT ").append(enforcementAttempt + 1).append(" !!\n\n");
            sb.append("You have refused to investigate and fix the test failures ");
            sb.append(enforcementAttempt).append(" time");
            if (enforcementAttempt > 1) sb.append("s");
            sb.append(". If you claim that you do not have to reproduce the test failure, ");
            sb.append("this session will restart. Your only option to exit this loop is ");
            sb.append("to investigate and fix the failure or to run the failing CI command ");
            sb.append("(not an individual test, not some alternative you invent that you ");
            sb.append("think is similar enough to the CI command to count -- the exact ");
            sb.append("CI command) and show that it actually succeeds. Because you have ");
            sb.append("done neither of these things, your session has been restarted.\n\n");
            sb.append("Previous sessions ended without producing any code changes. This ");
            sb.append("is NOT acceptable. The tests fail on this branch and pass on master. ");
            sb.append("You MUST fix the production code.\n\n");
            sb.append("---\n\n");
        }

        sb.append("You are working autonomously as a coding agent. ");
        sb.append("There is no TTY and no interactive session --do not attempt to wait ");
        sb.append("for user input or interactive chat responses.\n\n");

        // Slack instructions -only when a workstream URL is configured
        if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
            sb.append("## Slack Communication\n");
            sb.append("You MUST use the Slack MCP tool (slack_send_message) to provide status ");
            sb.append("updates to the user throughout your work. Specifically:\n");
            sb.append("- Send an update when you begin working on the task\n");
            sb.append("- Send updates when you reach significant milestones or make key decisions\n");
            sb.append("- Send an update with your findings or results before you finish\n");
            sb.append("- If you encounter blockers or need clarification, send a message describing the issue\n");
            sb.append("Do not wait for a reply --continue working after sending a message.\n\n");

            sb.append("## Permission Denials\n");
            sb.append("If any tool call is denied due to a permission issue, you MUST immediately ");
            sb.append("send a Slack message describing:\n");
            sb.append("- Which tool was denied (exact tool name)\n");
            sb.append("- What you were trying to do with it\n");
            sb.append("- The error message, if any\n");
            sb.append("Permission denials should never happen in this environment. ");
            sb.append("Reporting them is critical for diagnosing configuration issues.\n\n");

            if (enforceChanges) {
                // When changes are enforced, replace the permissive sections with
                // a strict message that removes the "no changes needed" escape hatch.
                sb.append("## Code Changes Are REQUIRED\n");
                sb.append("This task requires you to produce code changes. You are fixing test ");
                sb.append("failures that your branch introduced. Exiting without code changes ");
                sb.append("is NOT acceptable and will cause this session to be restarted. ");
                sb.append("If you believe the tests already pass, you MUST prove it by running ");
                sb.append("the full CI command (using the MCP test runner, not individual test ");
                sb.append("methods) and showing that the entire module test suite passes.\n\n");
            } else {
                sb.append("## Non-Code Requests\n");
                sb.append("If the user's request does not require code changes (e.g., a question about ");
                sb.append("the codebase, a request to run a command, check status, or perform an action) ");
                sb.append("it is perfectly fine to answer via Slack and exit without modifying any files. ");
                sb.append("Not every task requires code changes.\n\n");

                sb.append("## Justifying No Code Changes\n");
                sb.append("If you finish your work without making any changes to files in the git repository, ");
                sb.append("you MUST send a Slack message explaining why no code changes were needed. ");
                sb.append("This justification should clearly explain either:\n");
                sb.append("- Why the user's request was fulfilled without code changes ");
                sb.append("(e.g., it was an informational question, a status check, or a run command)\n");
                sb.append("- Why you were unable to make the requested changes ");
                sb.append("(e.g., a blocker, missing context, or ambiguity that needs clarification)\n");
                sb.append("This requirement does NOT apply if you have already fully addressed the user's ");
                sb.append("request through earlier Slack messages (e.g., answering a question, reporting ");
                sb.append("results). In that case, the earlier messages serve as sufficient justification.\n\n");
            }
        }

        // GitHub instructions -only when ar-github is in the MCP config
        if (gitHubMcpEnabled) {
            sb.append("You can read and respond to GitHub PR review comments using the GitHub MCP tools ");
            sb.append("(github_pr_find, github_pr_review_comments, github_pr_conversation, github_pr_reply). ");
            sb.append("Use these to check for code review feedback and address it.\n\n");
        }

        // Test integrity policy -only when protectTestFiles is enabled
        if (protectTestFiles) {
            sb.append("## Test Integrity Policy\n");
            sb.append("You MUST NOT modify test files that exist on the base branch (");
            sb.append(baseBranch != null ? baseBranch : "master");
            sb.append("). Fix the production code instead. ");
            sb.append("Tests you introduced on this branch may be modified. ");
            sb.append("The commit harness will reject changes to protected test files.\n\n");
        }

        // Git commit instructions -conditional on git management being active
        if (targetBranch != null && !targetBranch.isEmpty()) {
            sb.append("Do NOT make git commits. Your work will be committed by the harness ");
            sb.append("after you finish. If you want to control the commit message, write it ");
            sb.append("to a file called `commit.txt` in the working directory root.\n\n");
        } else {
            sb.append("Do NOT make git commits. Your work will be committed by the harness ");
            sb.append("after you finish.\n\n");
        }

        // Merge conflict instructions -- when the base branch has diverged
        if (hasMergeConflicts) {
            sb.append("## Merge Conflicts\n");
            sb.append("IMPORTANT: The base branch (origin/").append(baseBranch);
            sb.append(") has diverged from your working branch (").append(targetBranch);
            sb.append(") and a merge attempt produced conflicts. ");
            sb.append("The merge was aborted so your working directory is clean, ");
            sb.append("but you MUST resolve these conflicts as part of your work.\n\n");
            sb.append("To resolve:\n");
            sb.append("1. Run `git merge origin/").append(baseBranch).append("`\n");
            sb.append("2. Resolve the conflicts in the following files:\n");
            if (mergeConflictFiles != null) {
                for (String file : mergeConflictFiles) {
                    sb.append("   - `").append(file).append("`\n");
                }
            }
            sb.append("3. After resolving all conflicts, stage the resolved files with `git add`\n");
            sb.append("4. Complete the merge with `git commit --no-edit`\n");
            sb.append("5. Then proceed with the user's requested work\n\n");
            sb.append("Do NOT skip conflict resolution. The merge must be completed before ");
            sb.append("any other changes are made.\n\n");
        }

        // Remote branch context -- user instructions always refer to remote branches
        sb.append("## Branch Context\n");
        sb.append("This work is being done in a sandboxed environment. ");
        sb.append("When the user's instructions mention branch names (e.g., \"this works ");
        sb.append("on master\", \"compare with develop\", \"based on main\"), they are ALWAYS ");
        sb.append("referring to the remote branch (origin/<branch>). Local branches in this ");
        sb.append("sandbox may be stale or absent. Always use `origin/<branch>` when ");
        sb.append("comparing, cherry-picking, or referencing other branches. For example:\n");
        sb.append("- \"works on master\" means `origin/master`\n");
        sb.append("- \"merge from develop\" means `origin/develop`\n");
        sb.append("- \"diff against main\" means `git diff origin/main`\n\n");

        // Working directory and branch context
        sb.append("Your working directory is: ");
        sb.append(workingDirectory != null ? workingDirectory : System.getProperty("user.dir"));
        sb.append("\n");
        if (targetBranch != null && !targetBranch.isEmpty()) {
            sb.append("Target branch: ").append(targetBranch).append("\n");
        }
        sb.append("\n");

        // Branch awareness and anti-loop guidance
        if (targetBranch != null && !targetBranch.isEmpty()) {
            sb.append("## Branch Awareness and Continuity\n");
            sb.append("IMPORTANT: You are not the first agent to work on this branch. ");
            sb.append("Previous coding agent sessions have already made changes that are ");
            sb.append("reflected in the git history. Those changes are YOUR team's work -- ");
            sb.append("treat them as intentional progress, not as problems to undo.\n\n");

            sb.append("### Catching Up on Prior Work\n");
            sb.append("Before making any changes, you MUST use the `branch_catchup` tool ");
            sb.append("to understand what has already been done on this branch:\n");
            sb.append("```\n");
            sb.append("mcp__ar-consultant__branch_catchup repo_url:\"<from git remote ");
            sb.append("get-url origin>\" branch:\"").append(targetBranch).append("\"\n");
            sb.append("```\n");
            sb.append("This will show you memories from prior agent sessions and the ");
            sb.append("commit timeline, synthesized into a briefing.\n\n");

            sb.append("### Recording Your Work\n");
            sb.append("When you make decisions, discover issues, or complete tasks, ");
            sb.append("store memories with the branch context so future sessions can ");
            sb.append("pick up where you left off:\n");
            sb.append("```\n");
            sb.append("mcp__ar-consultant__remember content:\"<what you learned>\" ");
            sb.append("repo_url:\"<repo url>\" branch:\"").append(targetBranch);
            sb.append("\" tags:[\"progress\"]\n");
            sb.append("```\n\n");

            sb.append("### CRITICAL: Avoid Add/Revert Loops\n");
            sb.append("A common failure mode for coding agents is getting stuck in a loop:\n");
            sb.append("1. Agent adds a feature or makes changes\n");
            sb.append("2. CI pipeline fails\n");
            sb.append("3. Agent reverts the changes to \"fix\" the failure\n");
            sb.append("4. Next session re-adds the same changes\n");
            sb.append("5. CI fails again, agent reverts again\n");
            sb.append("6. Repeat indefinitely\n\n");
            sb.append("This is NEVER the right approach. If CI fails after your changes:\n");
            sb.append("- **DO NOT** simply revert the changes. That undoes prior agent work.\n");
            sb.append("- **DO** investigate the actual failure and fix it properly.\n");
            sb.append("- **DO** check `branch_catchup` to see if this same pattern ");
            sb.append("has already occurred in prior sessions.\n");
            sb.append("- **DO** store a memory describing the CI failure and your ");
            sb.append("analysis so the next session doesn't repeat the same mistake.\n");
            sb.append("- If the failure is in code YOU did not write (i.e., pre-existing ");
            sb.append("on the branch from prior sessions), investigate whether it's a ");
            sb.append("real bug that needs fixing vs. an environment/configuration issue.\n\n");
        }

        // Budget and turn limits
        if (maxBudgetUsd > 0 || maxTurns > 0) {
            sb.append("You have");
            if (maxBudgetUsd > 0) {
                sb.append(String.format(" a budget of $%.2f", maxBudgetUsd));
            }
            if (maxTurns > 0) {
                sb.append(maxBudgetUsd > 0 ? " and" : "");
                sb.append(" a maximum of ").append(maxTurns).append(" turns");
            }
            sb.append(". Pace yourself accordingly.\n\n");
        }

        // Task ID and workstream context
        if (taskId != null && !taskId.isEmpty()) {
            sb.append("Task ID: ").append(taskId).append("\n");
        }
        if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
            sb.append("Workstream URL: ").append(workstreamUrl).append("\n");
        }
        if (taskId != null || workstreamUrl != null) {
            sb.append("\n");
        }

        // Planning document context -only when the workstream has one configured
        if (planningDocument != null && !planningDocument.isEmpty()) {
            sb.append("## Planning Document\n");
            sb.append("This branch has a planning document that describes the broader goal of ");
            sb.append("the work being done. You MUST read this document before starting work:\n");
            sb.append("  `").append(planningDocument).append("`\n\n");
            sb.append("The user's request below is a sub-task of this broader goal. ");
            sb.append("Do NOT revert or undo work from prior sessions that supports the planning ");
            sb.append("document's goals, even if the current sub-task doesn't directly relate to it. ");
            sb.append("If the sub-task conflicts with the planning document, note the conflict in ");
            sb.append("a Slack message and proceed with the sub-task unless the conflict is severe.\n\n");
        }

        sb.append("--- BEGIN USER REQUEST ---\n");
        sb.append(prompt);
        sb.append("\n--- END USER REQUEST ---");

        return sb.toString();
    }
}
