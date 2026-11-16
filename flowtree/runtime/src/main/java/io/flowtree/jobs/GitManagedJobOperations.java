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

import io.flowtree.git.GitOperations;

import java.io.IOException;

/**
 * Views a {@link GitManagedJob}'s working-directory {@link GitOperations}
 * through the {@link FileStager.GitOperations} abstraction, so
 * {@link FileStager#evaluateFiles} and the {@link TestMethodProtection}
 * analysis it delegates to run under the job's working directory and identity.
 *
 * <p>Every operation delegates to one {@link GitManagedJob#gitOperations}
 * instance, which is what lets {@link #executeForStdout} read standard output
 * alone: a {@code -z} (NUL-delimited) listing must not have a stderr warning
 * fuse with its first field, and {@link GitOperations#executeForStdout} keeps
 * the two streams apart. The former anonymous implementation inside
 * {@link GitManagedJob} could not reach that method and fell back to the
 * stderr-merged {@link GitManagedJob#executeGitWithOutput}; this named
 * collaborator exists so the override has a home.</p>
 *
 * @author Michael Murray
 */
class GitManagedJobOperations implements FileStager.GitOperations {

    /** The job whose working directory and identity git runs under. */
    private final GitManagedJob job;

    /**
     * Creates an adapter bound to {@code job}.
     *
     * @param job the job whose working directory and identity git runs under
     */
    GitManagedJobOperations(GitManagedJob job) {
        this.job = job;
    }

    /**
     * Returns the job's {@link GitOperations} for its current working
     * directory, built fresh so it reflects the job's live identity.
     *
     * @return the git operations this adapter delegates to
     */
    private GitOperations git() {
        return job.gitOperations(job.getWorkingDirectory());
    }

    @Override
    public int execute(String... args) throws IOException, InterruptedException {
        return git().execute(args);
    }

    @Override
    public String executeWithOutput(String... args) throws IOException, InterruptedException {
        return git().executeWithOutput(args);
    }

    @Override
    public String executeForStdout(String... args) throws IOException, InterruptedException {
        return git().executeForStdout(args).output();
    }
}
