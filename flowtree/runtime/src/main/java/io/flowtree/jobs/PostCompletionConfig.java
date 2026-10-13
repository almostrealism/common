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
 * configuration by delegating to an instance of this class, so the two agree on
 * defaults, validation, and wire encoding by construction. Like
 * {@link PhaseRunnerConfig}, a factory supplies a <em>property sink</em> so that
 * every mutation is mirrored into its serialized property store; the job
 * supplies none and is serialized through {@link #appendEncoded(StringBuilder)}.
 * {@link #applySetting(String, String)} is the decode direction for both and
 * assigns without publishing.</p>
 *
 * @author Michael Murray
 * @see PostCompletionCommandRule
 */
class PostCompletionConfig {

    /**
     * Receives {@code (key, value)} for each wire property a mutation changed,
     * or {@code null} when this configuration is not serialized as properties.
     */
    private final BiConsumer<String, String> propertySink;

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
        this.propertySink = propertySink;
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
            publish("postCmd", GitManagedJob.base64Encode(command));
        } else {
            this.workingDir = null;
            this.timeoutSeconds = PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS;
            publish("postCmd", null);
            publish("postCmdDir", null);
            publish("postCmdTimeout", null);
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
        publish("postCmdDir", workingDir == null ? null : GitManagedJob.base64Encode(workingDir));
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
        publish("postCmdTimeout", String.valueOf(timeoutSeconds));
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
        publish("maxPostCmdPasses", String.valueOf(maxPasses));
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
     * Appends this configuration in the {@code ::key:=value} wire format. Nothing
     * is written when no command is configured, and the dependent values are
     * written only when they differ from their defaults.
     *
     * @param sb the buffer to append into
     */
    void appendEncoded(StringBuilder sb) {
        if (!isEnabled()) {
            return;
        }
        sb.append("::postCmd:=").append(GitManagedJob.base64Encode(command));
        if (workingDir != null) {
            sb.append("::postCmdDir:=").append(GitManagedJob.base64Encode(workingDir));
        }
        if (timeoutSeconds != PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS) {
            sb.append("::postCmdTimeout:=").append(timeoutSeconds);
        }
        if (maxPasses != PostCompletionCommandRule.DEFAULT_MAX_PASSES) {
            sb.append("::maxPostCmdPasses:=").append(maxPasses);
        }
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
            case "postCmd":
                this.command = GitManagedJob.base64Decode(value);
                return true;
            case "postCmdDir":
                this.workingDir = GitManagedJob.base64Decode(value);
                return true;
            case "postCmdTimeout":
                this.timeoutSeconds = value == null || value.isEmpty()
                        ? PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS
                        : Integer.parseInt(value);
                return true;
            case "maxPostCmdPasses":
                this.maxPasses = CodingAgentJobCodec.parsePositiveOrDefault(
                        value, PostCompletionCommandRule.DEFAULT_MAX_PASSES);
                return true;
            default:
                return false;
        }
    }

    /**
     * Forwards a changed wire property to the property sink, if any.
     *
     * @param key   the wire key
     * @param value the encoded value, or {@code null} to remove the key
     */
    private void publish(String key, String value) {
        if (propertySink != null) {
            propertySink.accept(key, value);
        }
    }
}
