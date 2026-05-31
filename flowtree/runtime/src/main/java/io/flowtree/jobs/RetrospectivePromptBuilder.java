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

import io.flowtree.jobs.agent.Phase;

/**
 * Builds the retrospective analysis prompt sent to the {@link Phase#RETROSPECTIVE}
 * agent session. The prompt instructs the retrospective agent to locate and
 * analyze the primary phase transcript, then emit structured findings as
 * memories covering tool-use quality and context efficiency.
 *
 * <p>This phase produces ANALYSIS, not code. It must NOT modify prompts, code,
 * or configuration. Findings are emitted exclusively as {@code memory_store}
 * calls for human review.</p>
 *
 * <p>Scope (v1):</p>
 * <ul>
 *   <li>Tool-use quality — effective tool use, missed tools, inefficient calls</li>
 *   <li>Context efficiency — redundant reads, re-reading files, exploring irrelevant areas</li>
 * </ul>
 *
 * <p>Out of scope for v1: code quality/correctness, architectural decisions,
 * cost efficiency, error recovery behavior.</p>
 *
 * <p>Graceful degradation: when no transcript is found, emits a single
 * "no transcript" memory and exits cleanly.</p>
 *
 * <p>Package-private; tests use {@link #build(CodingAgentJob)}.</p>
 *
 * @author Michael Murray
 * @see Phase#RETROSPECTIVE
 */
final class RetrospectivePromptBuilder {

    /** Well-known directory for persistent per-agent transcript storage. */
    static final String TRANSCRIPT_DIR = "/agent-transcripts";

    /** Static-only helper. */
    private RetrospectivePromptBuilder() {}

    /**
     * Builds the retrospective analysis prompt for {@code job}.
     *
     * @param job the job whose primary phase transcript is to be analyzed
     * @return the prompt string sent to the retrospective agent
     */
    static String build(CodingAgentJob job) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb);
        appendRole(sb);
        appendWhatToStudy(sb, job);
        appendFocusAreas(sb);
        appendHowToStoreFindings(sb, job);
        appendGracefulDegradation(sb, job);
        appendForbidden(sb);
        appendExpectedOutcome(sb);
        return sb.toString();
    }

    /** Appends the phase header. */
    private static void appendHeader(StringBuilder sb) {
        sb.append("RETROSPECTIVE ANALYSIS\n\n");
        sb.append("This is the RETROSPECTIVE phase of a FlowTree coding job.\n");
        sb.append("Your task is to analyze a transcript of the PRIMARY phase\n");
        sb.append("and identify concrete opportunities to improve tool-use and\n");
        sb.append("context efficiency. You are NOT here to redo the work.\n\n");
    }

    /** Appends the role statement. */
    private static void appendRole(StringBuilder sb) {
        sb.append("ROLE\n");
        sb.append("You are reviewing a transcript of another agent's working session.\n");
        sb.append("You did NOT participate in that session. Your job is to identify\n");
        sb.append("patterns in tool use and context management that could be improved.\n");
        sb.append("Findings are stored as memories for human review — you must NOT\n");
        sb.append("modify any code, prompts, or configuration.\n\n");
    }

    /** Appends what to study (transcript location and context). */
    private static void appendWhatToStudy(StringBuilder sb, CodingAgentJob job) {
        String jobId = job.getTaskId();
        String wsUrl = job.resolveWorkstreamUrl();
        String wsId = WorkstreamUtils.extractWorkstreamId(wsUrl);

        sb.append("WHAT TO STUDY\n\n");

        sb.append("Step 1 — Locate the primary phase transcript:\n");
        sb.append("  Use list_transcripts(directory=\"").append(TRANSCRIPT_DIR).append("\"");
        sb.append(", job_id=\"").append(jobId != null ? jobId : "").append("\")\n");
        sb.append("  to find transcript files for this job.\n\n");

        sb.append("Step 2 — Select the primary phase transcript:\n");
        sb.append("  Filter the results for phase=\"primary\" (check each transcript's\n");
        sb.append("  metadata using get_transcript_metadata(path=...)).\n");
        sb.append("  If multiple primary transcripts exist (e.g., retries), use the\n");
        sb.append("  most recent one.\n\n");

        sb.append("Step 3 — Analyze the transcript:\n");
        sb.append("  Use these tools on the selected transcript:\n");
        sb.append("  - get_transcript_summary() for an overview\n");
        sb.append("  - get_tool_usage() for per-tool counts and denied tools\n");
        sb.append("  - get_timeline_summary() for step-by-step walkthrough\n");
        sb.append("  - get_errors() for error and corruption events\n");
        sb.append("  - get_transcript_metadata() to confirm job_id, model, and phase\n\n");

        sb.append("CONTEXT FOR THIS JOB\n");
        sb.append("  job_id: ").append(jobId != null ? jobId : "(unknown)").append("\n");
        sb.append("  workstream_id: ").append(wsId != null ? wsId : "(unknown)").append("\n");
        String target = job.getTargetBranch();
        String base = job.getBaseBranch();
        sb.append("  target branch: ").append(target != null ? target : "(unset)").append("\n");
        sb.append("  base branch:   ").append(base != null ? base : "(unset)").append("\n");
        sb.append("\n");
    }

    /** Appends the v1 focus areas. */
    private static void appendFocusAreas(StringBuilder sb) {
        sb.append("FOCUS AREAS (v1 scope — tool-use and context efficiency only)\n\n");

        sb.append("1. TOOL USE QUALITY\n");
        sb.append("   - Did the agent use all relevant tools available to it?\n");
        sb.append("   - Did it miss tools that would have helped (missed tools)?\n");
        sb.append("     e.g., send_message for status updates, memory_recall for context,\n");
        sb.append("     or targeted Grep instead of reading entire files.\n");
        sb.append("   - Did it use a tool inefficiently?\n");
        sb.append("     e.g., reading an entire file when a targeted Grep would do,\n");
        sb.append("     or making many sequential calls when a single glob+grep would suffice.\n");
        sb.append("   - Did it call send_message to surface important state or decisions?\n");
        sb.append("   - Were tools denied (check denied_tool_names in the transcript footer)?\n");
        sb.append("     Note: denied tools may indicate security restrictions or bugs,\n");
        sb.append("     not necessarily poor agent behavior.\n\n");

        sb.append("2. CONTEXT EFFICIENCY\n");
        sb.append("   - Did the agent waste context on redundant reads or re-reading files\n");
        sb.append("     it already had loaded?\n");
        sb.append("   - Did it explore irrelevant areas of the codebase?\n");
        sb.append("   - Did it lose track of earlier findings in a long session?\n");
        sb.append("   - Did it manage the session well (turn count vs. work done)?\n\n");

        sb.append("NOT IN V1 SCOPE (defer these to future phases)\n");
        sb.append("   - Code quality or correctness\n");
        sb.append("   - Architectural decisions\n");
        sb.append("   - Cost efficiency (hard to infer from transcript alone)\n");
        sb.append("   - Error recovery behavior\n\n");
    }

    /** Appends how to store findings as memories. */
    private static void appendHowToStoreFindings(StringBuilder sb, CodingAgentJob job) {
        String wsId = WorkstreamUtils.extractWorkstreamId(job.resolveWorkstreamUrl());
        String jobId = job.getTaskId();

        sb.append("HOW TO STORE FINDINGS\n\n");
        sb.append("For each finding, call memory_store with:\n\n");

        sb.append("  memory_store(\n");
        sb.append("    namespace=\"self-improvement\",\n");
        sb.append("    tags=[\"retrospective\", \"<focus-area>\",\n");
        sb.append("            \"workstream:").append(wsId != null ? wsId : "").append("\",\n");
        sb.append("            \"job:").append(jobId != null ? jobId : "").append("\"],\n");
        sb.append("    content=(\"OBSERVED: <what was seen in the transcript>\\n\"\n");
        sb.append("            \"WHY_SUBOPTIMAL: <why this is a problem or missed opportunity>\\n\"\n");
        sb.append("            \"SUGGESTED_IMPROVEMENT: <concrete action the agent could have taken>\")\n");
        sb.append("  )\n\n");

        sb.append("Focus-area tag values:\n");
        sb.append("  - \"tool-use\" — for tool-use quality findings\n");
        sb.append("  - \"context-efficiency\" — for context efficiency findings\n\n");

        sb.append("Model tag: The model that ran the primary phase is in the transcript\n");
        sb.append("metadata (get_transcript_metadata). Add \"model:<modelName>\" to the tags\n");
        sb.append("when the model information is available.\n\n");

        sb.append("IMPORTANT — WRITE RESULTS FILE\n\n");
        sb.append("After storing all findings, write a JSON file to the working directory:\n\n");
        sb.append("  File: retrospective-results.json\n");
        sb.append("  Content: {\"transcriptFound\": true, \"findingsCount\": <N>}\n\n");
        sb.append("Replace <N> with the number of finding memories you stored.\n");
        sb.append("This file communicates structured results to the parent job\n");
        sb.append("and is the ONLY file you may write.\n\n");
    }

    /** Appends graceful degradation instructions when no transcript is found. */
    private static void appendGracefulDegradation(StringBuilder sb, CodingAgentJob job) {
        String wsId = WorkstreamUtils.extractWorkstreamId(job.resolveWorkstreamUrl());
        String jobId = job.getTaskId();

        sb.append("GRACEFUL DEGRADATION — NO TRANSCRIPT FOUND\n\n");
        sb.append("If list_transcripts returns no matching primary transcript (or an error),\n");
        sb.append("emit ONE memory noting that no transcript was available:\n\n");

        sb.append("  memory_store(\n");
        sb.append("    namespace=\"self-improvement\",\n");
        sb.append("    tags=[\"retrospective\", \"no-transcript\",\n");
        sb.append("            \"workstream:").append(wsId != null ? wsId : "").append("\",\n");
        sb.append("            \"job:").append(jobId != null ? jobId : "").append("\"],\n");
        sb.append("    content=(\"No transcript available for retrospective analysis. \"\n");
        sb.append("            \"Job ID: ").append(jobId != null ? jobId : "(unknown)").append(". \"\n");
        sb.append("            \"Possible causes: primary ran on Claude Code (not opencode), \"\n");
        sb.append("            \"transcript recording failed, or session was very short.\")\n");
        sb.append("  )\n\n");

        sb.append("Then write the results file and exit:\n");
        sb.append("  File: retrospective-results.json\n");
        sb.append("  Content: {\"transcriptFound\": false, \"findingsCount\": 0}\n\n");
    }

    /** Appends forbidden actions. */
    private static void appendForbidden(StringBuilder sb) {
        sb.append("FORBIDDEN ACTIONS\n");
        sb.append("  - No git commands of any kind.\n");
        sb.append("  - No Read, Edit, Write, Bash, Glob, or Grep on the codebase.\n");
        sb.append("  - No commits — the harness will commit at session end.\n");
        sb.append("  - No changes to any configuration files.\n");
        sb.append("  - No prompt modifications.\n");
        sb.append("  - EXCEPT: You MAY write one file: retrospective-results.json\n");
        sb.append("    (structured output required by the parent job; see HOW TO STORE FINDINGS).\n\n");
    }

    /** Appends the expected outcome statement. */
    private static void appendExpectedOutcome(StringBuilder sb) {
        sb.append("EXPECTED OUTCOME\n");
        sb.append("Store zero or more finding memories (or one no-transcript memory if\n");
        sb.append("no transcript is available), write retrospective-results.json, then exit.\n");
        sb.append("The session should produce no commits and no other file changes.\n\n");
    }
}
