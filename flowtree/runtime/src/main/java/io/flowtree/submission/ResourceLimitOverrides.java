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

package io.flowtree.submission;

import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.workstream.Workstream;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The resource limits one job submission asks for: a turn cap, a spend cap
 * and a silence budget, each held to what the workstream allows.
 *
 * <p>A workstream's owner configures its {@link Workstream#getMaxTurns() turn}
 * and {@link Workstream#getMaxBudgetUsd() spend} limits, and every job starts
 * from them. A submission may ask for less; asking for more is clamped to the
 * workstream's value, and the response says so, rather than letting one
 * submission exceed what the owner allowed. A ceiling that is not positive is
 * no ceiling. The silence budget ({@code inactivityTimeoutSeconds}) is
 * held to the runner's own budget when the job launches, because only then is
 * the runner known; see the job's {@code RestartGovernor}.</p>
 *
 * @author Michael Murray
 */
public final class ResourceLimitOverrides {

    /** Requested turn cap, or a non-positive value when none was given. */
    private final int maxTurns;

    /** Requested spend cap in US dollars, or a non-positive value when none was given. */
    private final double maxBudgetUsd;

    /** Requested silence budget in seconds, or {@code null} when none was given. */
    private final Integer inactivityTimeoutSeconds;

    /**
     * The overrides that were clamped, by request field name, to the value the
     * job actually received. Filled by {@link #applyTo}.
     */
    private final Map<String, String> clamped = new LinkedHashMap<>();

    /**
     * Reads the overrides from a submission body.
     *
     * @param body the JSON submission body
     */
    public ResourceLimitOverrides(String body) {
        this.maxTurns = JsonFieldExtractor.extractInt(body, "maxTurns");
        this.maxBudgetUsd = JsonFieldExtractor.extractDouble(body, "maxBudgetUsd");
        this.inactivityTimeoutSeconds = JsonFieldExtractor.hasField(body, "inactivityTimeoutSeconds")
                ? Integer.valueOf(JsonFieldExtractor.extractInt(body, "inactivityTimeoutSeconds")) : null;
    }

    /**
     * Returns why these overrides cannot be accepted, or {@code null} when
     * they can.
     *
     * @return the error message, or {@code null}
     */
    public String validationError() {
        if (inactivityTimeoutSeconds != null && inactivityTimeoutSeconds.intValue() <= 0) {
            return "inactivityTimeoutSeconds must be positive";
        }
        return null;
    }

    /**
     * Applies these overrides to a job factory that already holds the
     * workstream's limits, clamping each cap to the workstream's ceiling.
     * Requested caps that are not positive leave the workstream's value in
     * place.
     *
     * @param workstream the workstream whose limits are the ceiling
     * @param factory    the job factory to configure
     */
    public void applyTo(Workstream workstream, CodingAgentJobFactory factory) {
        if (maxTurns > 0) {
            int ceiling = workstream.getMaxTurns();
            int turns = ceiling > 0 ? Math.min(maxTurns, ceiling) : maxTurns;
            if (turns != maxTurns) clamped.put("maxTurns", String.valueOf(turns));
            factory.setMaxTurns(turns);
        }
        if (maxBudgetUsd > 0) {
            double ceiling = workstream.getMaxBudgetUsd();
            double budget = ceiling > 0 ? Math.min(maxBudgetUsd, ceiling) : maxBudgetUsd;
            if (budget != maxBudgetUsd) clamped.put("maxBudgetUsd", String.valueOf(budget));
            factory.setMaxBudgetUsd(budget);
        }
        if (inactivityTimeoutSeconds != null) {
            factory.setInactivityTimeoutSeconds(inactivityTimeoutSeconds);
        }
    }

    /**
     * Returns the overrides {@link #applyTo} clamped, by request field name, to
     * the value the job received.
     *
     * @return the clamped overrides; empty when none were
     */
    public Map<String, String> getClamped() {
        return clamped;
    }

    /**
     * Appends {@code "clampedLimits":{...}} to a submission response when an
     * override was clamped, so the caller can see it did not get what it asked
     * for.
     *
     * @param json the response being built
     */
    public void appendClampedJson(StringBuilder json) {
        if (!clamped.isEmpty()) {
            json.append(",\"clampedLimits\":").append(JsonFieldExtractor.toJsonObject(clamped));
        }
    }
}
