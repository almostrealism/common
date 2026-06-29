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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Detects invalid files left behind in a job's git working tree.
 *
 * <p>"Invalid" currently means any file with a {@code .bin} extension. Such
 * files are binary litter: they must never appear in the repository, whether or
 * not the job actually staged them. The presence of a single {@code .bin} file
 * anywhere under a scanned working tree fails the job, because other components
 * stage and commit whatever is present and will inevitably push binary litter
 * into the repository history once it is left lying around.</p>
 *
 * <p>The scan walks the filesystem directly rather than asking git, so it finds
 * {@code .bin} files even though {@code .gitignore} now ignores them — an
 * ignored file is still litter, and a different component running with
 * different ignore rules (or {@code git add -f}) can still commit it.</p>
 *
 * <h2>Dependent repositories</h2>
 * <p>The same rule applies to every repository the job touches, not just the
 * primary one. Dependent repos receive the agent's commits too, so litter left
 * in a dependent repo is just as poisonous. The detector therefore scans the
 * primary working directory and every dependent repo path. Litter found in a
 * dependent repo is reported with the repo directory name as a prefix so the
 * offending repository is unambiguous.</p>
 *
 * <h2>Base-branch exception</h2>
 * <p>A {@code .bin} file that already exists on the repository's base branch
 * ({@code origin/<baseBranch>}) is <em>not</em> litter — it is pre-existing
 * content the job inherited and is not responsible for. Such files are excluded
 * from the result so the job is not failed for litter it did not create. The
 * exclusion is computed per repository against that repository's own base
 * branch.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * InvalidFileDetector detector = new InvalidFileDetector(job, dependentRepoPaths);
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

    /** Base branch name; {@code .bin} files present on it are not litter. */
    private final String baseBranch;

    /** Repositories scanned for litter: the primary working tree, then dependents. */
    private final List<Repo> repos = new ArrayList<>();

    /** Relative paths of invalid files found by the most recent {@link #detect()}. */
    private List<String> invalidFiles = new ArrayList<>();

    /**
     * Creates a detector that scans only the job's primary working tree. The
     * working tree is read from {@link GitManagedJob#getWorkingDirectory()} at
     * construction time.
     *
     * @param job the job whose working tree is scanned for invalid files
     */
    InvalidFileDetector(GitManagedJob job) {
        this(job, Collections.emptyList());
    }

    /**
     * Creates a detector that scans the job's primary working tree together with
     * the given dependent repositories. Each path is read at construction time.
     *
     * @param job                 the job whose working tree is scanned
     * @param dependentRepoPaths  filesystem paths of dependent repositories
     *                            cloned alongside the primary repo, or empty
     */
    InvalidFileDetector(GitManagedJob job, List<String> dependentRepoPaths) {
        this.baseBranch = job.getBaseBranch();

        addRepo(job.getWorkingDirectory(), null);
        if (dependentRepoPaths != null) {
            for (String dependentRepoPath : dependentRepoPaths) {
                addRepo(dependentRepoPath, true);
            }
        }
    }

    /**
     * Registers a repository to scan. The primary repo ({@code dependent} false)
     * reports paths relative to its own root with no prefix; dependent repos
     * prefix reported paths with their directory name so they are unambiguous.
     */
    private void addRepo(String path, Boolean dependent) {
        if (path == null || path.isEmpty()) {
            return;
        }
        Path root = Path.of(path);
        String label = Boolean.TRUE.equals(dependent) && root.getFileName() != null
                ? root.getFileName().toString() : null;
        repos.add(new Repo(root, label));
    }

    /**
     * Walks every scanned repository and records each regular file whose name
     * ends with {@link #INVALID_EXTENSION}, ignoring the git metadata directory
     * and any {@code .bin} file that already exists on the repository's base
     * branch. Replaces the result of any prior invocation, so the detector can
     * be re-run after a corrective cleanup to confirm the litter is gone.
     *
     * @throws UncheckedIOException if a working tree cannot be traversed
     */
    void detect() {
        List<String> found = new ArrayList<>();
        for (Repo repo : repos) {
            found.addAll(scanRepo(repo));
        }
        Collections.sort(found);
        invalidFiles = found;
    }

    /**
     * Scans a single repository for {@code .bin} litter, excluding files present
     * on its base branch. Returns the reported paths (prefixed with the repo
     * label for dependent repos), unsorted.
     */
    private List<String> scanRepo(Repo repo) {
        if (repo.root == null || !Files.isDirectory(repo.root)) {
            return Collections.emptyList();
        }

        Set<String> baseBranchLitter = baseBranchAllowlist(repo.root);

        try (Stream<Path> walk = Files.walk(repo.root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isInsideGitDir(repo.root, path))
                    .filter(path -> path.getFileName().toString().endsWith(INVALID_EXTENSION))
                    .map(repo.root::relativize)
                    .filter(relative -> !baseBranchLitter.contains(toGitPath(relative)))
                    .map(relative -> repo.label == null
                            ? relative.toString() : repo.label + "/" + relative)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to scan working tree for invalid files: " + repo.root, e);
        }
    }

    /**
     * Returns the {@code .bin} files present on the given repository's base
     * branch ({@code origin/<baseBranch>}). These are pre-existing and must not
     * be treated as litter. Returns an empty set when no base branch is
     * configured or the ref cannot be read (fails safe toward flagging litter).
     */
    private Set<String> baseBranchAllowlist(Path root) {
        if (baseBranch == null || baseBranch.isEmpty()) {
            return Collections.emptySet();
        }
        return GitOperations.listFilesOnRef(root.toString(),
                "origin/" + baseBranch, INVALID_EXTENSION, this::warn);
    }

    /** Converts a filesystem-relative path to git's forward-slash form. */
    private static String toGitPath(Path relative) {
        return relative.toString().replace(File.separatorChar, '/');
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
     * Returns whether the given path lies within the git metadata directory of
     * the given repository root, which is excluded from the scan.
     */
    private boolean isInsideGitDir(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0 && GIT_DIR.equals(relative.getName(0).toString());
    }

    /**
     * A repository scanned for litter: its working-tree root and the prefix used
     * to qualify reported paths. The primary repo has a {@code null} label
     * (paths reported relative to its root); each dependent repo's label is its
     * directory name.
     */
    private static final class Repo {

        /** Working-tree root of the repository. */
        private final Path root;

        /** Reporting prefix for dependent repos, or {@code null} for the primary. */
        private final String label;

        /**
         * Creates a scanned repository.
         *
         * @param root  working-tree root
         * @param label reporting prefix, or {@code null} for the primary repo
         */
        private Repo(Path root, String label) {
            this.root = root;
            this.label = label;
        }
    }
}
