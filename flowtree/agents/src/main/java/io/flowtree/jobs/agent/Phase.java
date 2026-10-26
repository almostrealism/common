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

package io.flowtree.jobs.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.JsonFieldExtractor;

/**
 * Identifies the lifecycle phase of a coding-agent job for the purpose of
 * selecting which {@link AgentRunner} should dispatch the session.
 *
 * <p>Each phase carries a canonical kebab-case {@linkplain #wireName() wire
 * name} that is used in serialization, MCP submission bodies, YAML config,
 * and HTTP requests. The wire names for the six enforcement-rule phases
 * match the {@code getName()} values of their corresponding
 * {@code EnforcementRule} implementations so that operators do not have to
 * memorize two parallel vocabularies.</p>
 *
 * @author Michael Murray
 */
public enum Phase {
    /** Primary work — the initial agent session that runs the user's prompt. */
    PRIMARY("primary", "Primary work — the initial agent session that runs the user's prompt."),
    /** Retry of the primary prompt triggered by {@code EnforceChangesRule} (DEPRECATED). */
    ENFORCE_CHANGES("enforce-changes", "Retry of the primary prompt triggered by EnforceChangesRule (DEPRECATED)."),
    /** Review session — second-pass sanity check by a different runner. */
    REVIEW("review", "Review session — second-pass sanity check by a different runner."),
    /** Deduplication audit session. */
    DEDUPLICATION("deduplication", "Deduplication audit session."),
    /** Organizational placement review session. */
    ORGANIZATIONAL_PLACEMENT("organizational-placement", "Organizational placement review session."),
    /** Correction session triggered when Maven dependency changes are detected. */
    MAVEN_DEPENDENCY_PROTECTION("maven-dependency-protection", "Correction session triggered when Maven dependency changes are detected."),
    /** Correction session triggered by a non-zero post-completion command exit. */
    POST_COMPLETION("post-completion", "Correction session triggered by a non-zero post-completion command exit."),
    /** Correction session for missing or invalid {@code commit.txt}. */
    COMMIT_MESSAGE("commit-message", "Correction session for missing or invalid commit.txt."),
    /** Restart triggered when the agent tampered with git state. */
    GIT_TAMPERING_RESTART("git-tampering-restart", "Restart triggered when the agent tampered with git state."),
    /** Focused session that resolves merge conflicts encountered while reconciling a push against an advanced target branch. */
    PUSH_CONFLICT_RESOLUTION("push-conflict-resolution", "Focused session that resolves merge conflicts encountered while reconciling a push against an advanced target branch."),
    /** Retrospective session — analyzes the primary phase transcript for improvement opportunities. */
    RETROSPECTIVE("retrospective", "Retrospective session — analyzes the primary phase transcript for improvement opportunities."),
    /** Falsification session — extracts load-bearing behavioural claims and bounces to primary when captured evidence refutes them. */
    FALSIFICATION("falsification", "Falsification session — extracts load-bearing behavioural claims and bounces to primary when captured evidence refutes them.");

    /** Canonical kebab-case identifier used on the wire. */
    private final String wireName;

    /** Short human-readable description of this phase. */
    private final String description;

    /**
     * Constructs a phase with the given wire identifier and description.
     *
     * @param wireName    the canonical kebab-case identifier
     * @param description the short human-readable description
     */
    Phase(String wireName, String description) {
        this.wireName = wireName;
        this.description = description;
    }

    /**
     * Returns the canonical kebab-case identifier used to refer to this
     * phase in serialization, configuration, and submission APIs.
     *
     * @return the wire name; never {@code null}
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Returns whether a session in this phase is expected to change the
     * repository working tree.
     *
     * <p>Only {@link #RETROSPECTIVE} does not: it reads the previous
     * session's transcript and writes memories and a results file under
     * {@code .flowtree/}, which never reaches a commit. Every other phase
     * either produces the job's changes ({@link #PRIMARY}), corrects them (the
     * enforcement phases), redoes them after a guardrail tripped
     * ({@link #GIT_TAMPERING_RESTART}, which runs against a tree that has
     * already been reverted), or resolves conflicts in them
     * ({@link #PUSH_CONFLICT_RESOLUTION}).</p>
     *
     * <p>The distinction matters when a session cannot be trusted — it ran
     * without a required tool, say. A phase that touched the tree has left
     * untrusted content in it and there is no way to tell that content from
     * the work around it, so the job cannot go on to commit. A phase that
     * touched nothing has cost the job only that phase.</p>
     *
     * @return {@code true} when a session in this phase may edit the tree
     */
    public boolean modifiesWorkingTree() {
        return this != RETROSPECTIVE;
    }

    /**
     * Returns a short human-readable description of this phase.
     *
     * @return the description; never {@code null}
     */
    public String description() {
        return description;
    }

    /**
     * Returns this phase as a JSON object holding its {@code name} (the
     * {@linkplain #wireName() wire name}) and {@code description}, the shape
     * {@code GET /api/agents} advertises to clients choosing per-phase runners.
     *
     * @return a new JSON object; never {@code null}
     */
    public ObjectNode toJson() {
        ObjectNode json = JsonFieldExtractor.MAPPER.createObjectNode();
        json.put("name", wireName);
        json.put("description", description);
        return json;
    }

    /**
     * Names the lifecycle state a job is in once it has entered or finished
     * this phase. This is what the controller records on the job so a poller
     * can tell "primary still running" from "primary done, review running"
     * while the job's status is still {@code STARTED}.
     *
     * @param complete {@code true} when this phase has finished
     * @return the state, e.g. {@code "primary"} or {@code "primary complete"}
     */
    public String lifecycleState(boolean complete) {
        return complete ? wireName + " complete" : wireName;
    }

    /**
     * Resolves a wire name back to its {@link Phase} value.
     *
     * @param wireName the kebab-case identifier produced by {@link #wireName()}
     * @return the matching phase
     * @throws IllegalArgumentException when {@code wireName} is null or does
     *                                  not match any known phase
     */
    public static Phase fromWireName(String wireName) {
        if (wireName == null) {
            throw new IllegalArgumentException("Phase wire name must not be null");
        }
        for (Phase phase : values()) {
            if (phase.wireName.equals(wireName)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown phase wire name: " + wireName);
    }

    /**
     * Maps the {@code getName()} of an {@code EnforcementRule} to its
     * matching phase. Two of the six rule names ({@code "no-maven-dependency-changes"}
     * and {@code "post-completion-command"}) differ from the canonical
     * {@linkplain #wireName() phase wire name}, so a direct
     * {@link #fromWireName(String)} call would fail for them.
     *
     * @param ruleName the {@code EnforcementRule#getName()} value
     * @return the matching phase, or {@code null} when {@code ruleName} does
     *         not correspond to a known phase
     */
    public static Phase fromRuleName(String ruleName) {
        if (ruleName == null) {
            return null;
        }
        switch (ruleName) {
            case "enforce-changes":            return ENFORCE_CHANGES;
            case "review":                     return REVIEW;
            case "deduplication":              return DEDUPLICATION;
            case "organizational-placement":   return ORGANIZATIONAL_PLACEMENT;
            case "no-maven-dependency-changes":return MAVEN_DEPENDENCY_PROTECTION;
            case "post-completion-command":    return POST_COMPLETION;
            case "commit-message":             return COMMIT_MESSAGE;
            case "retrospective":               return RETROSPECTIVE;
            case "falsification":               return FALSIFICATION;
            default:                            return null;
        }
    }

    /**
     * Resolves the {@link Phase} a session is running as from its activity tag.
     *
     * <p>An empty or null tag means the primary session ({@link #PRIMARY}). A
     * non-empty tag is either an {@code EnforcementRule} name (tried first via
     * {@link #fromRuleName(String)}) or a phase {@linkplain #wireName() wire
     * name} from a restart path (tried via {@link #fromWireName(String)}). An
     * unrecognised tag falls back to {@link #PRIMARY} so a stray value never
     * breaks dispatch.</p>
     *
     * @param activity the activity tag of the current session; may be {@code null}
     * @return the resolved phase; never {@code null}
     */
    public static Phase fromActivity(String activity) {
        if (activity == null || activity.isEmpty()) {
            return PRIMARY;
        }
        Phase ruleMatch = fromRuleName(activity);
        if (ruleMatch != null) {
            return ruleMatch;
        }
        try {
            return fromWireName(activity);
        } catch (IllegalArgumentException e) {
            return PRIMARY;
        }
    }
}
