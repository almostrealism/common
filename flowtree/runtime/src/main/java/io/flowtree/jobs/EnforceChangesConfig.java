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

import java.util.function.BiConsumer;

/**
 * The change requirement of a {@link CodingAgentJob}: whether the job must
 * produce a change ({@code enforceChanges}), and how many times
 * {@link EnforceChangesRule} may re-run the agent, over the whole job, when a
 * run left none. A job that still commits nothing once that cap is spent is
 * reported {@link JobCompletionEvent.Status#DEGRADED} by
 * {@link CodingAgentJobEvent}. A cap of 0 is meaningful: the agent is never
 * re-run, and an empty result is reported at once.
 *
 * <p>{@link CodingAgentJob} and {@link CodingAgentJobFactory} both expose this
 * configuration by delegating to an instance of this class; see
 * {@link WireConfig} for how the two serialize it.
 * {@link #applySetting(String, String)} is the decode direction for both and
 * assigns without publishing.</p>
 *
 * @author Michael Murray
 * @see EnforceChangesRule
 */
class EnforceChangesConfig extends WireConfig {

    /** Wire key of the {@code enforceChanges} flag. */
    private static final String ENABLED_KEY = "enforceChanges";

    /** Wire key of the re-run cap. */
    private static final String MAX_RETRIES_KEY = "maxEnforceRetries";

    /** Whether the job must produce at least one staged file change. */
    private boolean enabled;

    /** Whole-job cap on {@link EnforceChangesRule} re-runs; 0 means never. */
    private int maxRetries = CodingAgentJob.DEFAULT_MAX_RULE_RETRIES;

    /** Constructs a configuration whose state is not published as properties. */
    EnforceChangesConfig() {
        this(null);
    }

    /**
     * Constructs a configuration that publishes its wire properties as they
     * change.
     *
     * @param propertySink receives {@code (key, value)} for each wire property
     *                     a mutation changed, or {@code null} for none
     */
    EnforceChangesConfig(BiConsumer<String, String> propertySink) {
        super(propertySink);
    }

    /**
     * Returns whether the job must produce a change.
     *
     * @return {@code true} when {@code enforceChanges} is on
     */
    boolean isEnabled() { return enabled; }

    /**
     * Sets whether the job must produce a change.
     *
     * @param enabled {@code true} to require a change
     */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        publish(ENABLED_KEY, String.valueOf(enabled));
    }

    /**
     * Returns the whole-job cap on re-runs when a run left no change.
     *
     * @return the cap; defaults to {@link CodingAgentJob#DEFAULT_MAX_RULE_RETRIES}
     */
    int getMaxRetries() { return maxRetries; }

    /**
     * Sets the whole-job cap on re-runs when a run left no change.
     *
     * @param maxRetries the cap; 0 means report an empty result without re-running
     * @throws IllegalArgumentException if the value is negative
     */
    void setMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException(
                    "maxEnforceChangesRetries must not be negative, got: " + maxRetries);
        }
        this.maxRetries = maxRetries;
        publish(MAX_RETRIES_KEY, String.valueOf(maxRetries));
    }

    /**
     * Creates the rule that enforces this requirement, with this cap as its
     * whole-job budget.
     *
     * @return a new {@link EnforceChangesRule}
     */
    EnforceChangesRule createRule() { return new EnforceChangesRule(maxRetries); }

    /**
     * Copies this configuration onto {@code target}.
     *
     * @param target the configuration to populate, typically a new job's
     */
    void applyTo(EnforceChangesConfig target) {
        target.setEnabled(enabled);
        target.setMaxRetries(maxRetries);
    }

    /**
     * Hands this configuration's wire properties to {@code sink}. The flag
     * always has a value; the cap is {@code null} while it is at its default.
     *
     * @param sink receives {@code (key, value)} for each wire property
     */
    @Override
    protected void writeWireProperties(BiConsumer<String, String> sink) {
        sink.accept(ENABLED_KEY, String.valueOf(enabled));
        sink.accept(MAX_RETRIES_KEY, maxRetries != CodingAgentJob.DEFAULT_MAX_RULE_RETRIES
                ? String.valueOf(maxRetries) : null);
    }

    /**
     * Applies one wire-format key/value pair, assigning without publishing. An
     * absent, invalid or negative cap restores the default.
     *
     * @param key   the wire-format key (without the leading {@code ::})
     * @param value the raw wire value
     * @return {@code true} if {@code key} belongs to this configuration and was consumed
     */
    boolean applySetting(String key, String value) {
        switch (key) {
            case ENABLED_KEY:
                this.enabled = Boolean.parseBoolean(value);
                return true;
            case MAX_RETRIES_KEY:
                this.maxRetries = parseAtLeastOrDefault(
                        value, 0, CodingAgentJob.DEFAULT_MAX_RULE_RETRIES);
                return true;
            default:
                return false;
        }
    }
}
