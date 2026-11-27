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

import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * The resource limits a {@link CodingAgentJobFactory} gives each job it
 * creates: the per-session turn and spend caps, the job-wide wall-clock
 * ceiling, and the job's own silence budget.
 *
 * <p>The factory delegates to an instance of this class and publishes every
 * change through its property sink; see {@link WireConfig}. On the job these
 * limits live where they take effect, so {@link #applyTo(CodingAgentJob)}
 * hands the caps to the job and the ceiling and silence budget to its
 * {@link RestartGovernor}, which the job encodes itself.</p>
 *
 * @author Michael Murray
 * @see RestartGovernor
 */
class JobResourceLimits extends WireConfig {

    /** Wire key of {@link #maxTurns}. */
    private static final String MAX_TURNS_KEY = "maxTurns";

    /** Wire key of {@link #maxBudgetUsd}. */
    private static final String MAX_BUDGET_KEY = "maxBudget";

    /** Wire key of {@link #maxWallClockHours}; an empty value means unset. */
    private static final String MAX_WALL_CLOCK_HOURS_KEY = "maxWallClockHours";

    /** Wire key of {@link #inactivityTimeoutSeconds}. */
    private static final String INACTIVITY_TIMEOUT_SECONDS_KEY = "inactivityTimeoutSeconds";

    /** Turn limit a factory starts with. */
    static final int DEFAULT_MAX_TURNS = 50;

    /** Spend limit, in US dollars, a factory starts with. */
    static final double DEFAULT_MAX_BUDGET_USD = 10.0;

    /** Maximum number of agentic turns an agent session may take. */
    private int maxTurns = DEFAULT_MAX_TURNS;

    /** Maximum spend per agent session, in US dollars; negative disables the limit. */
    private double maxBudgetUsd = DEFAULT_MAX_BUDGET_USD;

    /**
     * Job-wide wall-clock ceiling in hours, or {@code null} when the job
     * inherits {@link RestartGovernor#DEFAULT_MAX_WALL_CLOCK}. Zero disables
     * the ceiling.
     */
    private Integer maxWallClockHours;

    /** The job's own silence budget in seconds, or {@code null} to use its runner's. */
    private Integer inactivityTimeoutSeconds;

    /**
     * Constructs limits that publish their wire properties as they change.
     *
     * @param propertySink receives {@code (key, value)} for each wire property
     *                     a mutation changed, or {@code null} for none
     */
    JobResourceLimits(BiConsumer<String, String> propertySink) {
        super(propertySink);
    }

    /** Returns the turn limit for an agent session. */
    int getMaxTurns() { return maxTurns; }

    /**
     * Sets the turn limit for an agent session.
     *
     * @param maxTurns the turn limit
     */
    void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
        publish(MAX_TURNS_KEY, String.valueOf(maxTurns));
    }

    /** Returns the spend limit for an agent session, in US dollars. */
    double getMaxBudgetUsd() { return maxBudgetUsd; }

    /**
     * Sets the spend limit for an agent session.
     *
     * @param maxBudgetUsd the limit in US dollars; negative values disable it
     */
    void setMaxBudgetUsd(double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
        publish(MAX_BUDGET_KEY, String.valueOf(maxBudgetUsd));
    }

    /** Returns the wall-clock ceiling in hours, or {@code null} when the job inherits the default. */
    Integer getMaxWallClockHours() { return maxWallClockHours; }

    /**
     * Sets the job-wide wall-clock ceiling.
     *
     * @param maxWallClockHours the ceiling in hours, {@code 0} to disable it,
     *                          or {@code null} to inherit the default
     */
    void setMaxWallClockHours(Integer maxWallClockHours) {
        this.maxWallClockHours = maxWallClockHours;
        publish(MAX_WALL_CLOCK_HOURS_KEY,
                maxWallClockHours == null ? "" : String.valueOf(maxWallClockHours.intValue()));
    }

    /** Returns the job's own silence budget in seconds, or {@code null} when it uses its runner's. */
    Integer getInactivityTimeoutSeconds() { return inactivityTimeoutSeconds; }

    /**
     * Sets the job's own silence budget, which can only shorten its runner's;
     * see {@link RestartGovernor#inactivityTimeoutMillis(long)}.
     *
     * @param inactivityTimeoutSeconds the budget in seconds, which must be
     *                                 positive, or {@code null} to use the runner's
     * @throws IllegalArgumentException if the budget is not positive
     */
    void setInactivityTimeoutSeconds(Integer inactivityTimeoutSeconds) {
        if (inactivityTimeoutSeconds != null && inactivityTimeoutSeconds.intValue() <= 0) {
            throw new IllegalArgumentException(
                    "inactivityTimeoutSeconds must be positive, got: " + inactivityTimeoutSeconds);
        }
        this.inactivityTimeoutSeconds = inactivityTimeoutSeconds;
        publish(INACTIVITY_TIMEOUT_SECONDS_KEY,
                inactivityTimeoutSeconds == null ? null : String.valueOf(inactivityTimeoutSeconds.intValue()));
    }

    /**
     * Gives these limits to a newly created job: the caps to the job itself,
     * and the ceiling and silence budget, when set, to its governor.
     *
     * @param job the job to populate
     */
    void applyTo(CodingAgentJob job) {
        job.setMaxTurns(maxTurns);
        job.setMaxBudgetUsd(maxBudgetUsd);
        if (maxWallClockHours != null) {
            job.restartGovernor().setMaxWallClock(Duration.ofHours(maxWallClockHours.intValue()));
        }
        if (inactivityTimeoutSeconds != null) {
            job.restartGovernor().setInactivityTimeout(Duration.ofSeconds(inactivityTimeoutSeconds.intValue()));
        }
    }

    /**
     * Hands these limits' wire properties to {@code sink}. The caps are always
     * present; the ceiling and silence budget are {@code null} while unset.
     *
     * @param sink receives {@code (key, value)} for each wire property
     */
    @Override
    protected void writeWireProperties(BiConsumer<String, String> sink) {
        sink.accept(MAX_TURNS_KEY, String.valueOf(maxTurns));
        sink.accept(MAX_BUDGET_KEY, String.valueOf(maxBudgetUsd));
        sink.accept(MAX_WALL_CLOCK_HOURS_KEY,
                maxWallClockHours == null ? null : String.valueOf(maxWallClockHours.intValue()));
        sink.accept(INACTIVITY_TIMEOUT_SECONDS_KEY,
                inactivityTimeoutSeconds == null ? null : String.valueOf(inactivityTimeoutSeconds.intValue()));
    }

    /**
     * Applies one wire-format key/value pair, assigning without publishing.
     *
     * @param key   the wire-format key (without the leading {@code ::})
     * @param value the raw wire value
     * @return {@code true} if {@code key} is one of these limits and was consumed
     */
    boolean applySetting(String key, String value) {
        switch (key) {
            case MAX_TURNS_KEY:
                this.maxTurns = Integer.parseInt(value);
                return true;
            case MAX_BUDGET_KEY:
                this.maxBudgetUsd = Double.parseDouble(value);
                return true;
            case MAX_WALL_CLOCK_HOURS_KEY:
                this.maxWallClockHours = value == null || value.isEmpty()
                        ? null : Integer.valueOf(Integer.parseInt(value));
                return true;
            case INACTIVITY_TIMEOUT_SECONDS_KEY:
                int seconds = parsePositiveOrDefault(value, 0);
                this.inactivityTimeoutSeconds = seconds > 0 ? Integer.valueOf(seconds) : null;
                return true;
            default:
                return false;
        }
    }
}
