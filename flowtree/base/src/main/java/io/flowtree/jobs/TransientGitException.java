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

import java.io.IOException;

/**
 * Signals that a git operation failed for a reason that is the
 * infrastructure's, not the agent's: a remote sync race, a lock held by a
 * concurrent process, a transient network hiccup during fetch or push, or a
 * commit that could not be written despite staged, non-empty changes.
 *
 * <p>These failures are distinct from an agent-logic failure (a rejected
 * change set, an unresolved conflict only a person can settle, invalid files
 * left behind). A round marked {@code FAILED} for a transient git hiccup
 * pollutes the signal the QA retrospective and the stuck-job scanner read, so
 * the harness classifies such a failure separately — a bounded retry with
 * backoff absorbs the common case, and a failure that outlives the retries is
 * reported as a clean, infrastructure-labelled outcome rather than as an agent
 * crash. See {@code GitRepositorySetup} (sync) and {@code GitCommitHandler}
 * (commit) for where it is raised, and {@code CodingAgentJobEvent.statusFor} /
 * {@code GitManagedJob.createEvent} for how it is reported.</p>
 *
 * <p>It extends {@link IOException} so it propagates unchanged through the
 * {@code IOException}/{@code InterruptedException} signatures the git phases
 * already declare, without a new checked type on every method between the
 * throw site and the job's run loop.</p>
 */
public class TransientGitException extends IOException {

    /** Prefix applied by {@link #describe(Throwable)} to label the status message. */
    private static final String LABEL = "Transient git infrastructure failure: ";

    /**
     * Creates a transient git failure with a diagnostic message.
     *
     * @param message what git operation failed, and after how many attempts
     */
    public TransientGitException(String message) {
        super(message);
    }

    /**
     * Returns whether {@code error} is — or was caused by — a transient git
     * failure. The cause chain is walked so a {@code TransientGitException}
     * wrapped by a higher-level exception is still recognised.
     *
     * @param error the exception that terminated a job, or {@code null}
     * @return {@code true} when the failure is a transient git one
     */
    public static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TransientGitException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a status message that labels {@code error} as a transient git
     * infrastructure failure, so the reported status reads as an infrastructure
     * hiccup rather than an agent failure.
     *
     * @param error the transient failure (or its wrapper); must satisfy
     *              {@link #isTransient(Throwable)}
     * @return the labelled message, never {@code null}
     */
    public static String describe(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TransientGitException) {
                return LABEL + t.getMessage();
            }
        }
        return LABEL + (error != null ? error.getMessage() : "");
    }
}
