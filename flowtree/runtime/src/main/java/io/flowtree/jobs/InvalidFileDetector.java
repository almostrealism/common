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

import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Detects invalid files left behind in a job's git working tree.
 *
 * <p>"Invalid" currently means any file with a {@code .bin} extension. Such
 * files are binary litter: they must never appear in the repository, whether or
 * not the job actually staged them. The presence of a single {@code .bin} file
 * anywhere under the working tree fails the job, because other components stage
 * and commit whatever is present and will inevitably push binary litter into
 * the repository history once it is left lying around.</p>
 *
 * <p>The scan walks the filesystem directly rather than asking git, so it finds
 * {@code .bin} files even though {@code .gitignore} now ignores them — an
 * ignored file is still litter, and a different component running with
 * different ignore rules (or {@code git add -f}) can still commit it.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * InvalidFileDetector detector = new InvalidFileDetector(job);
 * detector.detect();
 * if (detector.isDetected()) {
 *     // fail the job, or give the subclass a chance to clean up and retry
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see GitManagedJob
 */
class InvalidFileDetector implements ConsoleFeatures {

    /** Extension (including the leading dot) of files treated as invalid litter. */
    static final String INVALID_EXTENSION = ".bin";

    /** Directory name (the git metadata directory) excluded from the scan. */
    private static final String GIT_DIR = ".git";

    /** Working-tree root resolved from the job, or {@code null} when unavailable. */
    private final Path root;

    /** Relative paths of invalid files found by the most recent {@link #detect()}. */
    private List<String> invalidFiles = new ArrayList<>();

    /**
     * Creates a detector for the given job. The working tree is read from
     * {@link GitManagedJob#getWorkingDirectory()} at construction time.
     *
     * @param job the job whose working tree is scanned for invalid files
     */
    InvalidFileDetector(GitManagedJob job) {
        String workingDirectory = job.getWorkingDirectory();
        this.root = workingDirectory != null && !workingDirectory.isEmpty()
                ? Path.of(workingDirectory) : null;
    }

    /**
     * Walks the working tree and records every regular file whose name ends with
     * {@link #INVALID_EXTENSION}, ignoring the git metadata directory. Replaces
     * the result of any prior invocation, so the detector can be re-run after a
     * corrective cleanup to confirm the litter is gone.
     *
     * @throws UncheckedIOException if the working tree cannot be traversed
     */
    void detect() {
        if (root == null || !Files.isDirectory(root)) {
            invalidFiles = new ArrayList<>();
            return;
        }

        try (Stream<Path> walk = Files.walk(root)) {
            invalidFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isInsideGitDir(path))
                    .filter(path -> path.getFileName().toString().endsWith(INVALID_EXTENSION))
                    .map(path -> root.relativize(path).toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to scan working tree for invalid files: " + root, e);
        }
    }

    /**
     * Returns whether the most recent {@link #detect()} found any invalid file.
     *
     * @return {@code true} if at least one {@code .bin} file is present
     */
    boolean isDetected() {
        return !invalidFiles.isEmpty();
    }

    /**
     * Returns the relative paths of the invalid files found by the most recent
     * {@link #detect()}, in sorted order.
     *
     * @return an unmodifiable list of working-tree-relative paths, empty if none
     */
    List<String> getInvalidFiles() {
        return Collections.unmodifiableList(invalidFiles);
    }

    /**
     * Returns a human-readable, comma-separated summary of the invalid files,
     * suitable for log messages and agent prompts.
     *
     * @return the joined relative paths, or the empty string when none were found
     */
    String getDescription() {
        return String.join(", ", invalidFiles);
    }

    /**
     * Returns whether the given path lies within the git metadata directory,
     * which is excluded from the scan.
     */
    private boolean isInsideGitDir(Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0 && GIT_DIR.equals(relative.getName(0).toString());
    }
}
