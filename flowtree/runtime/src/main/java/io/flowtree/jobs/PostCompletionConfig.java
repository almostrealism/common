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
 * The post-completion verification command of a {@link CodingAgentJob}: the
 * shell command run after the agent's primary work, its working directory, its
 * timeout, and the cap on correction sessions {@link PostCompletionCommandRule}
 * may launch when it fails.
 *
 * <p>{@link CodingAgentJob} and {@link CodingAgentJobFactory} both expose this
 * configuration by delegating to an instance of this class; see
 * {@link WireConfig} for how the two serialize it.
 * {@link #applySetting(String, String)} is the decode direction for both and
 * assigns without publishing.</p>
 *
 * @author Michael Murray
 * @see PostCompletionCommandRule
 */
class PostCompletionConfig extends WireConfig {

    /** Wire key of {@link #command}, Base64-encoded. */
    private static final String COMMAND_KEY = "postCmd";

    /** Wire key of {@link #workingDir}, Base64-encoded. */
    private static final String WORKING_DIR_KEY = "postCmdDir";

    /** Wire key of {@link #timeoutSeconds}. */
    private static final String TIMEOUT_KEY = "postCmdTimeout";

    /** Wire key of {@link #maxPasses}. */
    private static final String MAX_PASSES_KEY = "maxPostCmdPasses";

    /** Shell command run after the agent completes; non-empty activates {@link PostCompletionCommandRule}. */
    private String command;

    /** Working directory for {@link #command}; {@code null} uses the job's working directory. */
    private String workingDir;

    /** Timeout in seconds for {@link #command}. */
    private int timeoutSeconds = PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS;

    /** Cap on post-completion correction sessions per job. */
    private int maxPasses = PostCompletionCommandRule.DEFAULT_MAX_PASSES;

    /** Constructs a configuration whose state is not published as properties. */
    PostCompletionConfig() {
        this(null);
    }

    /**
     * Constructs a configuration that publishes its wire properties as they
     * change.
     *
     * @param propertySink receives {@code (key, value)} for each wire property
     *                     a mutation changed, or {@code null} for none
     */
    PostCompletionConfig(BiConsumer<String, String> propertySink) {
        super(propertySink);
    }

    /**
     * Returns whether a command is configured, i.e. whether
     * {@link PostCompletionCommandRule} is active.
     *
     * @return {@code true} when the command is non-empty
     */
    boolean isEnabled() {
        return command != null && !command.isEmpty();
    }

    /**
     * Returns the post-completion command.
     *
     * @return the command, or {@code null}/empty when disabled
     */
    String getCommand() { return command; }

    /**
     * Sets the post-completion command. Clearing it (to {@code null} or empty)
     * also resets the working directory and timeout, so a serialization
     * round-trip cannot silently re-enable the check with stale dependent values.
     *
     * @param command the shell command, or {@code null}/empty to disable
     */
    void setCommand(String command) {
        this.command = command;
        if (isEnabled()) {
            publish(COMMAND_KEY, GitManagedJob.base64Encode(command));
        } else {
            this.workingDir = null;
            this.timeoutSeconds = PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS;
            publish(COMMAND_KEY, null);
            publish(WORKING_DIR_KEY, null);
            publish(TIMEOUT_KEY, null);
        }
    }

    /**
     * Returns the working directory for the command.
     *
     * @return the directory, or {@code null} to use the job's working directory
     */
    String getWorkingDir() { return workingDir; }

    /**
     * Sets the working directory for the command.
     *
     * @param workingDir the directory, or {@code null} to use the job's working directory
     */
    void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
        publish(WORKING_DIR_KEY, workingDir == null ? null : GitManagedJob.base64Encode(workingDir));
    }

    /**
     * Returns the command timeout in seconds.
     *
     * @return the timeout; defaults to {@link PostCompletionCommandRule#DEFAULT_TIMEOUT_SECONDS}
     */
    int getTimeoutSeconds() { return timeoutSeconds; }

    /**
     * Sets the command timeout in seconds.
     *
     * @param timeoutSeconds the timeout in seconds
     */
    void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        publish(TIMEOUT_KEY, String.valueOf(timeoutSeconds));
    }

    /**
     * Returns the cap on post-completion correction sessions.
     *
     * @return the pass cap; defaults to {@link PostCompletionCommandRule#DEFAULT_MAX_PASSES}
     */
    int getMaxPasses() { return maxPasses; }

    /**
     * Sets the cap on post-completion correction sessions.
     *
     * @param maxPasses the pass cap; must be positive
     * @throws IllegalArgumentException if the value is not positive
     */
    void setMaxPasses(int maxPasses) {
        if (maxPasses <= 0) {
            throw new IllegalArgumentException("maxPostCompletionPasses must be positive, got: " + maxPasses);
        }
        this.maxPasses = maxPasses;
        publish(MAX_PASSES_KEY, String.valueOf(maxPasses));
    }

    /**
     * Copies this configuration onto {@code target} when a command is
     * configured; a disabled configuration leaves {@code target} untouched.
     *
     * @param target the configuration to populate, typically a new job's
     */
    void applyTo(PostCompletionConfig target) {
        if (!isEnabled()) {
            return;
        }
        target.setCommand(command);
        target.setWorkingDir(workingDir);
        target.setTimeoutSeconds(timeoutSeconds);
        target.setMaxPasses(maxPasses);
    }

    /**
     * Hands this configuration's wire properties to {@code sink}. Every value
     * is {@code null} when no command is configured, and the dependent values
     * are {@code null} while they are at their defaults.
     *
     * @param sink receives {@code (key, value)} for each wire property
     */
    @Override
    protected void writeWireProperties(BiConsumer<String, String> sink) {
        boolean enabled = isEnabled();
        sink.accept(COMMAND_KEY, enabled ? GitManagedJob.base64Encode(command) : null);
        sink.accept(WORKING_DIR_KEY, enabled && workingDir != null
                ? GitManagedJob.base64Encode(workingDir) : null);
        sink.accept(TIMEOUT_KEY,
                enabled && timeoutSeconds != PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS
                        ? String.valueOf(timeoutSeconds) : null);
        sink.accept(MAX_PASSES_KEY,
                enabled && maxPasses != PostCompletionCommandRule.DEFAULT_MAX_PASSES
                        ? String.valueOf(maxPasses) : null);
    }

    /**
     * Applies one wire-format key/value pair, assigning without publishing.
     *
     * @param key   the wire-format key (without the leading {@code ::})
     * @param value the raw wire value; {@code null} restores the default
     * @return {@code true} if {@code key} belongs to this configuration and was consumed
     */
    boolean applySetting(String key, String value) {
        switch (key) {
            case COMMAND_KEY:
                this.command = GitManagedJob.base64Decode(value);
                return true;
            case WORKING_DIR_KEY:
                this.workingDir = GitManagedJob.base64Decode(value);
                return true;
            case TIMEOUT_KEY:
                this.timeoutSeconds = value == null || value.isEmpty()
                        ? PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS
                        : Integer.parseInt(value);
                return true;
            case MAX_PASSES_KEY:
                this.maxPasses = parsePositiveOrDefault(
                        value, PostCompletionCommandRule.DEFAULT_MAX_PASSES);
                return true;
            default:
                return false;
        }
    }
}
