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

import java.util.Collections;
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

    /** Requested turn cap as parsed, or {@code null} when none was given. */
    private final Double maxTurns;

    /** Requested spend cap in US dollars as parsed, or {@code null} when none was given. */
    private final Double maxBudgetUsd;

    /** Whether the submission names a silence budget at all, numeric or not. */
    private final boolean inactivityTimeoutGiven;

    /** Requested silence budget in seconds as parsed, or {@code null} when it is absent or not numeric. */
    private final Double inactivityTimeoutSeconds;

    /**
     * The overrides that were clamped, by request field name, to the value the
     * job actually received. Filled by {@link #applyTo}.
     */
    private final Map<String, String> clamped = new LinkedHashMap<>();

    /**
     * Reads the overrides from a submission body. Values are kept exactly as
     * parsed; {@link #validationError()} decides whether they are acceptable.
     *
     * @param body the JSON submission body
     */
    public ResourceLimitOverrides(String body) {
        this.maxTurns = JsonFieldExtractor.getNumber(body, "maxTurns");
        this.maxBudgetUsd = JsonFieldExtractor.getNumber(body, "maxBudgetUsd");
        this.inactivityTimeoutGiven = JsonFieldExtractor.hasField(body, "inactivityTimeoutSeconds");
        this.inactivityTimeoutSeconds = JsonFieldExtractor.getNumber(body, "inactivityTimeoutSeconds");
    }

    /**
     * Returns why these overrides cannot be accepted, or {@code null} when
     * they can. A turn cap and a silence budget must be whole numbers that fit
     * an {@code int}, and a spend cap must be finite: a fractional or
     * non-finite request is refused rather than truncated or passed through.
     * The silence budget must also be positive.
     *
     * @return the error message, or {@code null}
     */
    public String validationError() {
        if (maxTurns != null && !isWholeInt(maxTurns)) {
            return "maxTurns must be a whole number";
        }
        if (maxBudgetUsd != null && !Double.isFinite(maxBudgetUsd)) {
            return "maxBudgetUsd must be a finite number";
        }
        if (inactivityTimeoutGiven) {
            if (inactivityTimeoutSeconds == null || !isWholeInt(inactivityTimeoutSeconds)) {
                return "inactivityTimeoutSeconds must be a whole number";
            }
            if (inactivityTimeoutSeconds <= 0) {
                return "inactivityTimeoutSeconds must be positive";
            }
        }
        return null;
    }

    /**
     * Returns whether a parsed value is a whole number within the range of an
     * {@code int}.
     *
     * @param value the parsed value
     * @return {@code true} when the value converts to an {@code int} exactly
     */
    private static boolean isWholeInt(double value) {
        return Double.isFinite(value) && value == Math.rint(value)
                && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    /**
     * Applies these overrides to a job factory that already holds the
     * workstream's limits, clamping each cap to the workstream's ceiling.
     * Requested caps that are not positive leave the workstream's value in
     * place.
     *
     * @param workstream the workstream whose limits are the ceiling
     * @param factory    the job factory to configure
     * @throws IllegalStateException when {@link #validationError()} reports an error
     */
    public void applyTo(Workstream workstream, CodingAgentJobFactory factory) {
        String error = validationError();
        if (error != null) throw new IllegalStateException(error);

        if (maxTurns != null && maxTurns > 0) {
            int requested = maxTurns.intValue();
            int ceiling = workstream.getMaxTurns();
            int turns = ceiling > 0 ? Math.min(requested, ceiling) : requested;
            if (turns != requested) clamped.put("maxTurns", String.valueOf(turns));
            factory.setMaxTurns(turns);
        }
        if (maxBudgetUsd != null && maxBudgetUsd > 0) {
            double ceiling = workstream.getMaxBudgetUsd();
            double budget = ceiling > 0 ? Math.min(maxBudgetUsd, ceiling) : maxBudgetUsd;
            if (budget != maxBudgetUsd) clamped.put("maxBudgetUsd", String.valueOf(budget));
            factory.setMaxBudgetUsd(budget);
        }
        if (inactivityTimeoutGiven) {
            factory.setInactivityTimeoutSeconds(inactivityTimeoutSeconds.intValue());
        }
    }

    /**
     * Returns the overrides {@link #applyTo} clamped, by request field name, to
     * the value the job received.
     *
     * @return an unmodifiable view of the clamped overrides; empty when none were
     */
    public Map<String, String> getClamped() {
        return Collections.unmodifiableMap(clamped);
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
