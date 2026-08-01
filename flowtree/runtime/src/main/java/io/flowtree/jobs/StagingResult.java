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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of a file staging evaluation by {@link FileStager}.
 *
 * <p>Contains two lists: files that passed all guardrails and are eligible
 * for staging, and files that were skipped along with human-readable reasons
 * in the format {@code "filename (reason)"}.</p>
 *
 * <p>Both lists are stored as unmodifiable copies to guarantee immutability
 * after construction.</p>
 *
 * @author Michael Murray
 * @see FileStager
 * @see FileStagingConfig
 */
public final class StagingResult {

    /** Files that passed all guardrails, eligible for {@code git add}. */
    private final List<String> stagedFiles;

    /**
     * Files that were blocked, each with an appended reason in the format
     * {@code "filename (reason)"}.
     */
    private final List<String> skippedFiles;

    /**
     * Creates a new staging result.
     *
     * @param stagedFiles  files that passed all guardrails
     * @param skippedFiles files that were skipped, with reasons in format
     *                     {@code "filename (reason)"}
     */
    public StagingResult(List<String> stagedFiles, List<String> skippedFiles) {
        this.stagedFiles = Collections.unmodifiableList(new ArrayList<>(stagedFiles));
        this.skippedFiles = Collections.unmodifiableList(new ArrayList<>(skippedFiles));
    }

    /**
     * Returns the files that passed all guardrails and are eligible for staging.
     *
     * @return an unmodifiable list of staged file paths
     */
    public List<String> getStagedFiles() {
        return stagedFiles;
    }

    /**
     * Returns the files that were skipped, each with a reason in the format
     * {@code "filename (reason)"}.
     *
     * @return an unmodifiable list of skipped file descriptions
     */
    public List<String> getSkippedFiles() {
        return skippedFiles;
    }

    /**
     * Returns a string representation showing staged and skipped counts
     * as well as the full file lists.
     *
     * @return a summary string
     */
    @Override
    public String toString() {
        return "StagingResult{" +
            "staged=" + stagedFiles.size() +
            ", skipped=" + skippedFiles.size() +
            ", stagedFiles=" + stagedFiles +
            ", skippedFiles=" + skippedFiles +
            '}';
    }
}
