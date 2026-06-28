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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a focused correction agent session for a {@link CodingAgentJob}.
 *
 * <p>A correction session temporarily swaps the job's prompt for a focused
 * correction prompt and tags the run with an activity name (the rule or phase
 * name) so {@code send_message} calls made by the agent are labelled via
 * {@code AR_AGENT_ACTIVITY}. The original prompt and activity are always
 * restored afterward.</p>
 *
 * <p>The job's {@code commit.txt} is snapshotted before the session, because
 * {@link CodingAgentJob#executeSingleRun()} deletes it at startup. If the
 * correction session does not write its own commit message, the primary
 * session's message is restored; if it does, that newer message — which
 * describes the actual changes — is kept.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob#runCorrectionSession(String, String)
 */
final class CorrectionSession {

    /** Name of the per-job file holding the pending commit message. */
    private static final String COMMIT_FILE = "commit.txt";

    /** Prevents instantiation; this class only exposes a static entry point. */
    private CorrectionSession() {
    }

    /**
     * Runs a correction session on {@code job} with the given prompt and
     * activity tag, restoring the job's prompt, activity, and (when
     * appropriate) commit message afterward.
     *
     * @param job              the job whose agent session is being corrected
     * @param correctionPrompt the focused prompt for this session
     * @param activity         the rule/phase name used as the activity tag
     */
    static void run(CodingAgentJob job, String correctionPrompt, String activity) {
        String originalPrompt = job.getPrompt();
        String previousActivity = job.getCurrentActivity();
        job.setCurrentActivity(activity);

        Path savedCommitFile = job.resolveWorkingPath(COMMIT_FILE);
        String savedCommitMessage = readCommitMessage(job, savedCommitFile);

        boolean reviewing = "review".equals(activity) && job.getActiveReviewRule() != null;
        if (reviewing) job.getActiveReviewRule().captureBefore(job);
        try {
            job.setPrompt(correctionPrompt);
            job.executeSingleRun();
            if (reviewing) job.getActiveReviewRule().recordOutcome(job);
        } finally {
            job.setPrompt(originalPrompt);
            job.setCurrentActivity(previousActivity);
            restoreCommitMessage(job, savedCommitFile, savedCommitMessage);
        }
    }

    /**
     * Reads the pending commit message, or returns {@code null} when none is
     * present or it cannot be read.
     */
    private static String readCommitMessage(CodingAgentJob job, Path commitFile) {
        if (commitFile == null || !Files.exists(commitFile)) return null;
        try {
            return Files.readString(commitFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            job.warn("Could not read commit.txt: " + e.getMessage());
            return null;
        }
    }

    /**
     * Restores the primary-session commit message only when the correction
     * session did not write its own; a message written by the correction
     * session describes the actual changes and is left in place.
     */
    private static void restoreCommitMessage(CodingAgentJob job, Path commitFile, String savedMessage) {
        boolean correctionWroteCommit = commitFile != null && Files.exists(commitFile);
        if (correctionWroteCommit || savedMessage == null || commitFile == null) {
            return;
        }
        try {
            Files.writeString(commitFile, savedMessage, StandardCharsets.UTF_8);
            job.log("Restored primary commit message from commit.txt");
        } catch (IOException e) {
            job.warn("Could not restore commit.txt: " + e.getMessage());
        }
    }
}
