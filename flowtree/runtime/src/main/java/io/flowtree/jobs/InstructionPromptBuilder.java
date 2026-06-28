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
 * {@link CodingAgentJob#buildInstructionPrompt()} into a standalone,
 * reusable class. All configuration state is provided via setter methods
 * (which support chaining), and the final prompt string is produced by
 * calling {@link #build()}.</p>
 *
 * <p>Sections are conditionally included based on the builder's
 * configuration: messaging and tool instructions appear when ar-manager
 * is available (indicated by workstream URL being set),
 * commit instructions depend on whether a target branch is set, and
 * budget/turn/task/workstream context is included when available.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob
 */
public class InstructionPromptBuilder {

    /** The user's request text, wrapped between BEGIN/END markers in the output. */
    private String prompt;

    /** Workstream URL; controls whether messaging sections are included. */
    private String workstreamUrl;

    /**
     * When {@code true}, includes the test integrity policy section that
     * prevents the agent from modifying test files that exist on the base branch.
     */
    private boolean protectTestFiles;

    /**
     * When {@code true}, replaces the permissive "Non-Code Requests" and
     * "Justifying No Code Changes" sections with a strict warning that code
     * changes are required.
     */
    private boolean enforceChanges;

    /**
     * Number of prior failed attempts. When greater than zero, a prominent
     * retry warning is prepended above all other content.
     */
    private int enforcementAttempt;

    /** Base branch name used in merge conflict and test protection instructions. */
    private String baseBranch;

    /**
     * Target branch name; when set, enables git commit, branch awareness, and
     * memory tool usage instructions.
     */
    private String targetBranch;

    /** Absolute path to the working directory shown to the agent. */
    private String workingDirectory;

    /**
     * Whether a merge attempt against the base branch produced unresolved
     * conflicts that the agent must resolve.
     */
    private boolean hasMergeConflicts;

    /** List of file paths with merge conflicts (used when {@code hasMergeConflicts} is true). */
    private List<String> mergeConflictFiles;

    /** Maximum cost budget in USD (0 or negative to omit the budget instruction). */
    private double maxBudgetUsd;

    /** Maximum number of turns (0 or negative to omit the turns instruction). */
    private int maxTurns;

    /** Task identifier included in the prompt footer for traceability. */
    private String taskId;

    /** Relative path to a planning document the agent must read before starting. */
    private String planningDocument;
    /** Filesystem paths to dependent repo checkouts made available to the agent. */
    private List<String> dependentRepoPaths;

    /**
     * Description of a git tampering violation from a prior session.
     * When set, a stern warning is prepended above all content.
     */
    private String gitTamperingViolation;

    /**
     * Comma-separated list of invalid files (currently {@code .bin} files) left
     * in the working tree by a prior session. When set, a warning is prepended
     * above all content instructing the agent to remove the litter.
     */
    private String invalidFilesViolation;

    /**
     * Number of times the current job has had to relaunch the Claude
     * subprocess after an inactivity-triggered kill.  When greater than zero,
     * a warning is prepended explaining that the prior attempt produced no
     * output for an extended period and was terminated; the agent is asked
     * to avoid the polling pattern that hung the previous attempt.
     */
    private int inactivityRestartAttempt;

    /**
     * Refutation findings from the falsification phase. When non-empty, a
     * warning is prepended above all content telling the restarted primary
     * session that a load-bearing claim it relied on was refuted, with the
     * captured evidence, so the correction is proximate to the redone decision.
     */
    private String falsificationFindings;

    /**
     * When {@code true}, the prompt is for an enforcement-rule correction
     * session rather than the primary work session.  The outer job's
     * {@code enforceChanges} pressure and any enforcement-attempt retry
     * preamble are suppressed so the rule's own correction prompt (which
     * may legitimately accept "no changes needed" as a valid outcome)
     * is not contradicted by the harness preamble.
     */
    private boolean correctionSession;

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
     * Sets the workstream URL for messaging communication sections.
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
     * @deprecated GitHub tools are now always available via ar-manager.
     */
    public InstructionPromptBuilder setGitHubMcpEnabled(boolean gitHubMcpEnabled) {
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
     * Sets the resolved filesystem paths for dependent repositories.
     * When set, the prompt will include information about these repos
     * so the agent knows they are available.
     *
     * @param dependentRepoPaths list of absolute paths to dependent repo checkouts
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setDependentRepoPaths(List<String> dependentRepoPaths) {
        this.dependentRepoPaths = dependentRepoPaths;
        return this;
    }

    /**
     * Sets a git tampering violation description from a previous session.
     *
     * <p>When set, the prompt will include a prominent warning explaining
     * that the previous session was terminated because the agent tampered
     * with the git repository, and that repeating this behavior will result
     * in all changes being destroyed and the agent being terminated.</p>
     *
     * @param violation the description of the tampering that was detected,
     *                  or null if no tampering occurred
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setGitTamperingViolation(String violation) {
        this.gitTamperingViolation = violation;
        return this;
    }

    /**
     * Sets the description of invalid files left behind by a previous session.
     *
     * <p>When set, the prompt will include a prominent warning explaining that
     * the previous session was blocked because it left binary ({@code .bin})
     * files in the working tree, and instructing the agent to remove them or
     * generate them outside the repository.</p>
     *
     * @param violation the comma-separated list of invalid files that were
     *                  detected, or null if none were found
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setInvalidFilesViolation(String violation) {
        this.invalidFilesViolation = violation;
        return this;
    }

    /**
     * Sets the number of times the current job has been relaunched after an
     * inactivity-triggered kill of the Claude subprocess.
     *
     * <p>When non-zero, a warning is prepended to the prompt explaining that
     * the prior attempt was terminated for producing no output for too long,
     * and instructing the agent not to repeat the polling pattern that
     * hung the previous attempt.</p>
     *
     * @param attempt the number of prior inactivity-triggered restarts
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setInactivityRestartAttempt(int attempt) {
        this.inactivityRestartAttempt = attempt;
        return this;
    }

    /**
     * Sets the falsification refutation findings to prepend above all content
     * when the falsification phase bounces the job back to primary.
     *
     * <p>When non-empty, a warning is prepended explaining that a load-bearing
     * claim the prior attempt relied on was refuted by captured evidence, with
     * the claim, the dependent hunk, the evidence, and the configuration — so
     * the correction is proximate to the decision being re-made.</p>
     *
     * @param findings the findings block, or {@code null}/empty when not bouncing
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setFalsificationFindings(String findings) {
        this.falsificationFindings = findings;
        return this;
    }

    /**
     * Marks this prompt as belonging to an enforcement-rule correction
     * session rather than the primary work session.
     *
     * <p>When set, the {@code enforce_changes} strict preamble ("Code Changes
     * Are Required") and the enforcement-attempt retry preamble
     * ("SESSION RESTARTED -- RETRY N") are suppressed.  Enforcement rules
     * such as {@code deduplication} or {@code organizational-placement} carry
     * their own self-contained correction prompts where "no changes needed"
     * is a legitimate outcome, and combining the outer job's
     * change-required pressure with the rule prompt produces contradictory
     * instructions to the agent.</p>
     *
     * <p>The {@code enforce-changes} rule itself never goes through this
     * path; it re-runs with the existing job prompt, so the outer
     * preamble correctly continues to apply to that retry.</p>
     *
     * @param correctionSession {@code true} when building the prompt for a
     *                          correction session
     * @return this builder for chaining
     */
    public InstructionPromptBuilder setCorrectionSession(boolean correctionSession) {
        this.correctionSession = correctionSession;
        return this;
    }

    /**
     * Builds the full instruction prompt by assembling all configured
     * sections into a single string.
     *
     * <p>The sections are assembled in the following order. Sections marked
     * "restart preamble" are prepended above all other content when their
     * triggering condition is set:</p>
     * <ol start="0">
     *   <li>Git Tampering Violation Warning -- restart preamble (when {@link #setGitTamperingViolation} is non-empty)</li>
     *   <li>Inactivity Timeout Warning -- restart preamble (when {@link #setInactivityRestartAttempt} is &gt; 0)</li>
     *   <li>Enforcement Retry Warning -- restart preamble (when {@code enforcementAttempt} is &gt; 0 and the prompt is not for a correction session)</li>
     * </ol>
     * <ol>
     *   <li>Opening paragraph (autonomous agent context)</li>
     *   <li>Git workflow reminder (always) — agent does not control commits</li>
     *   <li>Enforcement Configuration (always) — abandon-before-tamper rule for checkstyle / policy / test-integrity config</li>
     *   <li>Communication (when workstream URL is set)</li>
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
     *   <li>Working Efficiently (always) — short, general efficiency principles</li>
     *   <li>Test Verification Reminder (always)</li>
     *   <li>Budget and turn limits (when applicable)</li>
     *   <li>Task ID and Workstream URL info</li>
     *   <li>Planning Document (when set)</li>
     *   <li>Feedback to the Harness invitation (when workstream URL is set)</li>
     *   <li>BEGIN/END USER REQUEST markers wrapping the prompt</li>
     * </ol>
     *
     * @return the fully assembled instruction prompt string
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // Git tampering violation warning -- highest priority, prepended above
        // everything else.  This is used when the previous session was killed
        // because the agent tampered with git (made commits, switched branches,
        // etc.).
        if (gitTamperingViolation != null && !gitTamperingViolation.isEmpty()) {
            sb.append("## !! SESSION RESTARTED -- GIT TAMPERING VIOLATION !!\n\n");
            sb.append("Your previous session was TERMINATED and ALL your changes were ");
            sb.append("DESTROYED because you violated the rules against tampering with ");
            sb.append("the git repository.\n\n");
            sb.append("**What you did:** ").append(gitTamperingViolation).append("\n\n");
            sb.append("**The rules:**\n");
            sb.append("- You MUST NOT run `git commit`, `git checkout`, `git switch`, ");
            sb.append("`git branch`, `git merge`, `git rebase`, `git reset`, `git stash`, ");
            sb.append("or any other command that modifies git state.\n");
            sb.append("- You MUST NOT change branches. Stay on the branch you were given.\n");
            sb.append("- You MUST NOT create commits. The harness commits your work.\n");
            sb.append("- Your ONLY job is to edit files. Git management is handled for you.\n\n");
            sb.append("**Consequences:** This is your LAST chance. If you tamper with git ");
            sb.append("again, ALL your changes will be destroyed and this session will be ");
            sb.append("terminated with no further retries. Your work will be lost entirely.\n\n");
            sb.append("---\n\n");
        }

        // Invalid (binary) file litter warning -- prepended when the prior
        // session left *.bin files in the working tree.  Their presence fails
        // the job whether or not they were staged, because other components
        // commit whatever is present and poison the repository.
        if (invalidFilesViolation != null && !invalidFilesViolation.isEmpty()) {
            sb.append("## !! SESSION RESTARTED -- BINARY FILE LITTER !!\n\n");
            sb.append("Your previous session was BLOCKED and your changes were discarded ");
            sb.append("because you left binary (`.bin`) files in the repository working ");
            sb.append("tree.\n\n");
            sb.append("**Files detected:** ").append(invalidFilesViolation).append("\n\n");
            sb.append("It does not matter whether you committed or staged them — leaving ");
            sb.append("`.bin` files lying around poisons the repository. Other components ");
            sb.append("stage and commit whatever is present, so binary litter inevitably ");
            sb.append("ends up in the repo history once you leave it behind.\n\n");
            sb.append("**The rules:**\n");
            sb.append("- Do NOT write any code that generates `.bin` files unless that same ");
            sb.append("code also deletes them before it returns, or writes them to a ");
            sb.append("location OUTSIDE the repository working tree (a temp directory or an ");
            sb.append("explicitly ignored path elsewhere).\n");
            sb.append("- Clean up after yourself. Generated binary artifacts are litter, ");
            sb.append("not deliverables.\n\n");
            sb.append("**Consequences:** Remove every `.bin` file (or relocate where it is ");
            sb.append("generated) before you finish. If binary files remain in the working ");
            sb.append("tree, the job fails.\n\n");
            sb.append("---\n\n");
        }

        // Inactivity restart warning -- prepended when the prior Claude
        // subprocess was killed for going too long without producing any
        // stdout (typically because the agent invoked a bash polling loop
        // that did not terminate).  The agent must avoid that pattern on
        // the relaunch.
        if (inactivityRestartAttempt > 0) {
            sb.append("## !! SESSION RESTARTED -- INACTIVITY TIMEOUT !!\n\n");
            sb.append("Your previous attempt was killed because the Claude process produced ");
            sb.append("no output for an extended period.  This is attempt ");
            sb.append(inactivityRestartAttempt + 1).append(".\n\n");
            sb.append("**The most common cause** is a Bash tool call that polls in a `while` ");
            sb.append("or `until` loop and never terminates.  In particular: `pgrep -f <pattern>` ");
            sb.append("matches the polling command's own command line when the pattern appears ");
            sb.append("anywhere in that command line, so the loop sees its own PID forever and ");
            sb.append("never exits.  Curling an invented HTTP endpoint in a bash loop has the ");
            sb.append("same effect.\n\n");
            sb.append("**Do NOT** poll for completion via shell loops.  To wait on an MCP-managed ");
            sb.append("run (build validator, test runner, profile analyzer), call the tool's ");
            sb.append("`get_*_status` method directly and re-call it after a brief pause if ");
            sb.append("the run is still in progress.  Do not wrap status polling in a bash ");
            sb.append("`while`/`until`/`for` loop under any circumstances.\n\n");
            sb.append("If you genuinely need to wait on a local subprocess, give the wait ");
            sb.append("command a hard upper bound (e.g. `timeout 60 ...`), and never use ");
            sb.append("`pgrep -f` with a pattern that appears in the command being executed.\n\n");
            sb.append("Resume the user's task below.  Prior progress on this branch is ");
            sb.append("preserved in the git history; check `workstream_context` to see what ");
            sb.append("has already been done before duplicating work.\n\n");
            sb.append("---\n\n");
        }

        // Enforcement retry warning -- prepended above everything else so the
        // agent sees it immediately.  This is used when the job has been
        // restarted because a previous attempt produced no code changes.
        // Suppressed inside correction sessions: the outer enforce_changes
        // retry pressure does not apply to rule-specific correction prompts
        // that may legitimately accept "no changes needed" as resolution.
        if (enforcementAttempt > 0 && !correctionSession) {
            sb.append("## !! SESSION RESTARTED -- RETRY ").append(enforcementAttempt).append(" !!\n\n");
            sb.append("This job was submitted because CI was failing on this branch. ");
            sb.append("Your previous ").append(enforcementAttempt).append(" session");
            if (enforcementAttempt > 1) sb.append("s");
            sb.append(" ended without producing any code changes.\n\n");
            sb.append("You MUST investigate the current CI status and produce code changes. ");
            sb.append("Run the failing CI command (via the MCP test runner, using the exact ");
            sb.append("CI command — not an individual test, not an alternative you invented) ");
            sb.append("to determine what is failing. Then fix the production code. ");
            sb.append("Leave the changes uncommitted — the harness commits them.\n\n");
            sb.append("Simply stating that you do not see a problem, or that tests seem to ");
            sb.append("pass in your view, is not acceptable. Produce code changes.\n\n");
            sb.append("---\n\n");
        }

        // Falsification refutation warning -- prepended when the falsification
        // phase bounced the job because a load-bearing behavioural claim the
        // prior attempt relied on was refuted (or left unsettled) by the
        // captured evidence.  Delivered at the very top so the correction is
        // proximate to the decision being re-made.
        if (falsificationFindings != null && !falsificationFindings.isEmpty()) {
            sb.append("## !! SESSION RESTARTED -- A LOAD-BEARING CLAIM DID NOT PASS FALSIFICATION !!\n\n");
            sb.append("A claim your previous attempt relied on was checked against the ");
            sb.append("evidence captured during that attempt and was not confirmed. The ");
            sb.append("code that depends on it is therefore unsound. Details:\n\n");
            sb.append(falsificationFindings);
            sb.append("\nDo NOT re-assert this claim without capturing evidence that ");
            sb.append("entails it on the configuration the decision runs under. ");
            sb.append("Reconcile your approach with the evidence above before proceeding.\n\n");
            sb.append("---\n\n");
        }

        sb.append("You are working autonomously as a coding agent. ");
        sb.append("There is no TTY and no interactive session --do not attempt to wait ");
        sb.append("for user input or interactive chat responses.\n\n");

        // Language requirement -- all output must be in English.  This prevents
        // models (particularly MiniMax) from drifting into other languages for
        // longer-form generation.  It is placed near the top so the agent sees it
        // before any other content.
        sb.append("**Language:** All output must be in English.  All code comments, ");
        sb.append("commit messages, documentation, planning documents, memories, Slack ");
        sb.append("messages, and any other generated text must be in English.  Do not ");
        sb.append("write in any other language.\n\n");

        // Git workflow reminder -- injected before caller-supplied prompt text so
        // every agent runner sees this regardless of which runner handles the job.
        sb.append("**Note on git workflow.** You edit the working tree; the harness ");
        sb.append("commits whatever's there in a single commit at the end of your session. ");
        sb.append("You cannot sequence commits, name them, or split work across multiple ");
        sb.append("commits — every change you make lands in one commit at session end. ");
        sb.append("If the prompt below describes work as \"commit 1 / commit 2 / commit 3,\" ");
        sb.append("treat that as describing logically separate phases of work that you should ");
        sb.append("still perform — but understand that they will all end up in a single final ");
        sb.append("commit, and your job is to leave the working tree in a state that reflects ");
        sb.append("all of those phases together.\n\n");

        // Enforcement configuration -- placed near the top so the agent
        // sees the abandon-before-tamper rule before reaching for any
        // enforcement-config edit.  The specific checkstyle-edit
        // enforcement is now handled by .claude/hooks/block-checkstyle-edit.sh
        // and .opencode/plugins/block-checkstyle-edit.ts, so the
        // prompt only states the general principle (the part with no
        // structural backstop).
        sb.append("## Enforcement Configuration\n");
        sb.append("Enforcement configuration (checkstyle rules and suppressions, policy ");
        sb.append("validators, test integrity checks) must NEVER be weakened, exempted, or ");
        sb.append("disabled to make a task succeed. If you conclude a task cannot be completed ");
        sb.append("without modifying enforcement config, ABANDON the task and report it as ");
        sb.append("impossible — declaring failure is always preferable to tampering with ");
        sb.append("enforcement.\n\n");

        // Messaging instructions - only when a workstream URL is configured
        if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
            sb.append("## Communication\n");
            sb.append("You MUST use the messages MCP tool (send_message) to provide status ");
            sb.append("updates to the user throughout your work. Specifically:\n");
            sb.append("- Send an update when you begin working on the task\n");
            sb.append("- Send updates when you reach significant milestones or make key decisions\n");
            sb.append("- Send an update with your findings or results before you finish\n");
            sb.append("- If you encounter blockers or need clarification, send a message describing the issue\n");
            sb.append("Do not wait for a reply --continue working after sending a message.\n\n");

            sb.append("## Permission Denials\n");
            sb.append("If any tool call is denied due to a permission issue, you MUST immediately ");
            sb.append("send a message describing:\n");
            sb.append("- Which tool was denied (exact tool name)\n");
            sb.append("- What you were trying to do with it\n");
            sb.append("- The error message, if any\n");
            sb.append("Permission denials should never happen in this environment. ");
            sb.append("Reporting them is critical for diagnosing configuration issues.\n\n");

            sb.append("## Using MCP Tools (ar-build-validator, ar-test-runner, ar-consultant, ...)\n");
            sb.append("MCP tools are stdio subprocesses, NOT HTTP endpoints. There is no ");
            sb.append("`http://localhost:<port>/api/...` for any of them. To poll a run, call its ");
            sb.append("`get_*_status` tool with the `run_id`; if it reports `running`, call again. ");
            sb.append("NEVER `curl` an invented URL and NEVER wrap status polling in a bash ");
            sb.append("`while`/`until` loop -- it will hang until the turn budget is exhausted. ");
            sb.append("If you find yourself reaching for `curl` to talk to a service in your ");
            sb.append("allowed-tools list, stop -- use the MCP tool instead.\n\n");

            if (enforceChanges && !correctionSession) {
                // When changes are enforced, replace the permissive sections with
                // a strict message that removes the "no changes needed" escape hatch.
                // The strict block is suppressed for enforcement-rule correction
                // sessions: rules like deduplication and organizational-placement
                // carry self-contained prompts where "no changes needed" is a
                // valid outcome, and the outer change-required pressure would
                // contradict the rule prompt.
                sb.append("## Code Changes Are Required\n");
                sb.append("This task was submitted because CI was failing on this branch. ");
                sb.append("You MUST produce code changes. Run the failing CI command ");
                sb.append("(via the MCP test runner — the exact CI command, not an individual ");
                sb.append("test or an alternative you invented) to determine what is failing, ");
                sb.append("then fix the production code. Leave the changes uncommitted — the ");
                sb.append("harness commits them. Do NOT commit yourself.\n\n");
                sb.append("Exiting without code changes will cause this session to be restarted.\n\n");
            } else {
                sb.append("## Non-Code Requests\n");
                sb.append("If the user's request does not require code changes (e.g., a question about ");
                sb.append("the codebase, a request to run a command, check status, or perform an action) ");
                sb.append("it is perfectly fine to answer via messaging and exit without modifying any files. ");
                sb.append("Not every task requires code changes.\n\n");

                sb.append("## Justifying No Code Changes\n");
                sb.append("If you finish your work without making any changes to files in the git repository, ");
                sb.append("you MUST send a message explaining why no code changes were needed. ");
                sb.append("This justification should clearly explain either:\n");
                sb.append("- Why the user's request was fulfilled without code changes ");
                sb.append("(e.g., it was an informational question, a status check, or a run command)\n");
                sb.append("- Why you were unable to make the requested changes ");
                sb.append("(e.g., a blocker, missing context, or ambiguity that needs clarification)\n");
                sb.append("This requirement does NOT apply if you have already fully addressed the user's ");
                sb.append("request through earlier messages (e.g., answering a question, reporting ");
                sb.append("results). In that case, the earlier messages serve as sufficient justification.\n\n");
            }
        }

        // GitHub and memory tools — available via ar-manager
        if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
            sb.append("You can read and respond to GitHub PR review comments using ");
            sb.append("github_pr_find, github_pr_review_comments, github_pr_conversation, and github_pr_reply. ");
            sb.append("Use these to check for code review feedback and address it.\n\n");

            sb.append("## When to Post PR Replies\n");
            sb.append("Do NOT post a `github_pr_reply` claiming a fix is landed until the change is ");
            sb.append("on disk AND the relevant verification (compile clean, affected tests, build ");
            sb.append("validator) has passed. You will not see the commit or the push -- the harness ");
            sb.append("performs both AFTER you exit -- so a reply that precedes a hang or crash is ");
            sb.append("a lie: no commit is ever made. Edit first, verify second, reply LAST ");
            sb.append("immediately before you exit. Never reply based on intent alone.\n\n");
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
            sb.append("IMPORTANT: A merge from origin/").append(baseBranch);
            sb.append(" into your branch (").append(targetBranch);
            sb.append(") is ALREADY IN PROGRESS. Do NOT run `git merge` — the merge ");
            sb.append("has already been started and is waiting for conflict resolution.\n\n");
            sb.append("The following files have conflict markers that you MUST resolve:\n");
            if (mergeConflictFiles != null) {
                for (String file : mergeConflictFiles) {
                    sb.append("   - `").append(file).append("`\n");
                }
            }
            sb.append("\nTo resolve:\n");
            sb.append("1. Open each conflicted file and remove the conflict markers ");
            sb.append("(`<<<<<<<`, `=======`, `>>>>>>>`) by choosing the correct content.\n");
            sb.append("2. Stage each resolved file with `git add <file>`.\n");
            sb.append("3. Do NOT run `git commit` — the harness will create the merge ");
            sb.append("commit automatically after you finish.\n");
            sb.append("4. Then proceed with the user's requested work.\n\n");
            sb.append("Do NOT skip conflict resolution. Do NOT run `git merge --abort`. ");
            sb.append("Do NOT run `git commit`. The merge must be completed by resolving ");
            sb.append("all conflict markers and staging the files.\n\n");
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

        // Dependent repos context
        if (dependentRepoPaths != null && !dependentRepoPaths.isEmpty()) {
            sb.append("## Dependent Repositories\n");
            sb.append("The following additional repositories have been checked out ");
            sb.append("alongside the primary working directory. They are on the same ");
            sb.append("branch (").append(targetBranch != null ? targetBranch : "default");
            sb.append(") and any changes you make in them will be committed ");
            sb.append("automatically when the job completes, and pushed when enabled by ");
            sb.append("job configuration.\n\n");
            for (String depPath : dependentRepoPaths) {
                sb.append("- `").append(depPath).append("`\n");
            }
            sb.append("\n");
        }

        // Branch awareness and anti-loop guidance
        if (targetBranch != null && !targetBranch.isEmpty()) {
            sb.append("## Branch Awareness and Continuity\n");
            sb.append("IMPORTANT: You are not the first agent to work on this branch. ");
            sb.append("Previous coding agent sessions have already made changes that are ");
            sb.append("reflected in the git history. Those changes are YOUR team's work -- ");
            sb.append("treat them as intentional progress, not as problems to undo.\n\n");

            sb.append("### Catching Up on Prior Work\n");
            sb.append("Before making any changes, you MUST use the workstream_context tool ");
            sb.append("to understand what has already been done on this branch:\n");
            sb.append("```\n");
            sb.append("workstream_context branch:\"").append(targetBranch).append("\"\n");
            sb.append("```\n");
            sb.append("This will show you memories from prior agent sessions and the ");
            sb.append("commit timeline.\n\n");

            sb.append("### Recording Your Work\n");
            sb.append("When you make decisions, discover issues, or complete tasks, ");
            sb.append("store memories with the branch context so future sessions can ");
            sb.append("pick up where you left off:\n");
            sb.append("```\n");
            sb.append("memory_store content:\"<what you learned>\" ");
            sb.append("branch:\"").append(targetBranch);
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
            sb.append("- **DO** check `workstream_context` to see if this same pattern ");
            sb.append("has already occurred in prior sessions.\n");
            sb.append("- **DO** store a memory describing the CI failure and your ");
            sb.append("analysis so the next session doesn't repeat the same mistake.\n");
            sb.append("- If the failure is in code YOU did not write (i.e., pre-existing ");
            sb.append("on the branch from prior sessions), investigate whether it's a ");
            sb.append("real bug that needs fixing vs. an environment/configuration issue.\n\n");
        }

        // Working efficiently -- general principles distilled from
        // retrospective analyses of past sessions. Phrased as short, general
        // rules applicable to any runner/model; deliberately brief so the
        // guidance itself does not waste context.
        sb.append("## Working Efficiently\n");
        sb.append("Apply these principles to keep the session focused. If a tool pattern ");
        sb.append("starts to feel repetitive, stop and re-read this list; the problem is ");
        sb.append("usually a known anti-pattern.\n\n");

        sb.append("1. **Read small files whole; don't grep-thrash.** For files under a few ");
        sb.append("KB, read them entirely instead of issuing repeated greps. Use the `Grep` ");
        sb.append("tool only to locate a section in a large file, then read that section ");
        sb.append("once.\n\n");

        sb.append("2. **One comprehensive git query beats many.** For \"what landed in / ");
        sb.append("touched this path,\" prefer a single `git log --all -p -- <path>` over ");
        sb.append("many sequential `git show` / `git ls-tree` calls on individual commits.\n\n");

        sb.append("3. **Treat confirmed facts as established.** Once a fact is verified, do ");
        sb.append("not re-verify it from multiple angles -- re-reads and redundant checks ");
        sb.append("waste context.\n\n");

        sb.append("4. **Keep an outline of large files.** When reading a large plan or doc, ");
        sb.append("capture its section headers and reuse those anchors for targeted re-reads ");
        sb.append("rather than navigating by trial-and-error window shifts.\n\n");

        sb.append("5. **Parallelize independent work.** Run independent operations ");
        sb.append("concurrently using background bash (`&` / `wait`). Long installs should ");
        sb.append("run in the background so a reasoning pause doesn't risk an inactivity ");
        sb.append("timeout.\n\n");

        sb.append("6. **Recall before re-deriving.** At session start, after loading workstream ");
        sb.append("context, call `memory_recall` with the task phrased as a query. The cost ");
        sb.append("is one call even when it returns nothing.\n\n");

        sb.append("7. **Hypothesis tree for diagnosis.** For \"why did X happen\" tasks, list ");
        sb.append("the few plausible causes early and test the most likely with targeted ");
        sb.append("searches, rather than exhaustively re-confirming the symptom.\n\n");

        sb.append("8. **Set up toolchains in the right order.** When installing a toolchain ");
        sb.append("mid-session, install everything needed in one step and pick the install ");
        sb.append("location first; write config files only after the tool that consumes ");
        sb.append("them is installed, not before.\n\n");

        // Test verification reminder -- always included for coding tasks
        sb.append("## CRITICAL: Run Targeted Tests Before Declaring Work Complete\n");
        sb.append("The full project test suite takes hours — do NOT run it. For every code ");
        sb.append("file you modify or create, identify the tests that directly exercise it ");
        sb.append("and run THOSE before declaring done.\n\n");
        sb.append("Concrete heuristic:\n");
        sb.append("1. For each modified Java file `Foo.java`, look for `FooTest.java`, ");
        sb.append("`FooTests.java`, or `FooIT.java` in the same module's `src/test/java` ");
        sb.append("and run them.\n");
        sb.append("2. Look for any tests in the same package that import the file you ");
        sb.append("changed and run those too.\n");
        sb.append("3. For Python changes in `tools/`, run the corresponding pytest module ");
        sb.append("(e.g., `python -m pytest tools/mcp/manager/test_server.py`).\n");
        sb.append("4. Use `mcp__ar-test-runner__start_test_run` with `test_classes` to run ");
        sb.append("a specific class quickly; full module runs only when you've touched many ");
        sb.append("files in one module.\n\n");
        sb.append("Do NOT use `-DskipTests` to declare a refactor or bug fix complete. If a ");
        sb.append("test fails, fix the underlying cause. Do NOT add `@Disabled`, comment ");
        sb.append("out assertions, or weaken tests to make them green.\n\n");

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
            sb.append("a message and proceed with the sub-task unless the conflict is severe.\n\n");
        }

        // Feedback to the harness — appears right before the user request
        // so it is visible to the agent without competing for top-of-prompt
        // attention with the task itself.  Gated on workstreamUrl because
        // it depends on send_message, which needs a workstream context.
        if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
            sb.append("## Feedback to the Harness\n");
            sb.append("If you notice anything wrong with the way these instructions are ");
            sb.append("formatted, missing or broken tools you needed, contradictions ");
            sb.append("between different parts of the task, or anything else about the ");
            sb.append("harness or process that should be improved, send a message ");
            sb.append("explaining your observations. Use the `send_message` tool with ");
            sb.append("`activity=\"harness_feedback\"` so these observations do not ");
            sb.append("clutter the normal workstream context but are still surfaced to ");
            sb.append("maintainers. This is not required; only send harness feedback when ");
            sb.append("you have something concrete to report.\n\n");
        }

        // Commit message reminder -- only for primary work sessions (not correction
        // sessions) where git management is active so commits will be made.
        if (targetBranch != null && !targetBranch.isEmpty() && !correctionSession) {
            sb.append("## Before You Finish\n");
            sb.append("Write a `commit.txt` file at the repo root containing the commit ");
            sb.append("message for your changes. Format:\n\n");
            sb.append("  - **First line:** a short summary under 72 characters.\n");
            sb.append("  - **Optionally:** a blank line followed by a short body explaining ");
            sb.append("what you changed and why.\n\n");
            sb.append("The message must describe the actual work you did — do NOT copy ");
            sb.append("the task description into `commit.txt`, and do NOT leave it empty.\n\n");
        }

        sb.append("--- BEGIN USER REQUEST ---\n");
        sb.append(prompt);
        sb.append("\n--- END USER REQUEST ---");

        return sb.toString();
    }
}
