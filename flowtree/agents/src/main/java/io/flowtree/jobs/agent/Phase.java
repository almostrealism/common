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

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

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
     * Returns a short human-readable description of this phase.
     *
     * @return the description; never {@code null}
     */
    public String description() {
        return description;
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
     * Encodes a per-phase runner map as a CSV of {@code phase=runner} pairs
     * in declaration order, suitable for inclusion in a job's wire format.
     *
     * @param map the map to encode; must not be {@code null}
     * @return the CSV string; empty when the map is empty
     */
    public static String encodeRunnerMap(Map<Phase, String> map) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Phase phase : values()) {
            String runner = map.get(phase);
            if (runner == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(phase.wireName).append('=').append(runner);
        }
        return sb.toString();
    }

    /**
     * Parses a CSV runner map produced by {@link #encodeRunnerMap(Map)}.
     * Unknown phase names are passed to {@code warningSink} and skipped so
     * a future producer cannot brick a current consumer.
     *
     * @param value       the CSV string; {@code null} or empty yields an
     *                    empty map
     * @param warningSink consumer for warnings about unknown or malformed
     *                    entries; may be {@code null} to discard them
     * @return the parsed map; never {@code null}
     */
    public static Map<Phase, String> decodeRunnerMap(String value, Consumer<String> warningSink) {
        Map<Phase, String> out = new EnumMap<>(Phase.class);
        if (value == null || value.isEmpty()) return out;
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                if (warningSink != null) warningSink.accept("Ignoring malformed runner entry: " + trimmed);
                continue;
            }
            String phaseName = trimmed.substring(0, eq);
            String runner = trimmed.substring(eq + 1);
            try {
                Phase phase = fromWireName(phaseName);
                out.put(phase, runner);
            } catch (IllegalArgumentException e) {
                if (warningSink != null) {
                    warningSink.accept("Ignoring unknown phase '" + phaseName
                            + "' in runners map");
                }
            }
        }
        return out;
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
