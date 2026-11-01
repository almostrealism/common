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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
 * <h2>Build-output exception</h2>
 * <p>A Maven module's {@code target/} directory — a directory named
 * {@value #BUILD_OUTPUT_DIR} whose parent holds a {@value #BUILD_DESCRIPTOR} —
 * is build output: created by the build, wiped by {@code mvn clean}, and
 * where tests that generate binary fixtures write them precisely so that
 * nothing reaches source control. It is skipped entirely. This is the only
 * directory the scan skips besides {@code .git}; a {@code target} directory
 * with no {@code pom.xml} beside it is an ordinary directory and is
 * scanned.</p>
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

    /** Name of a Maven module's build-output directory. */
    static final String BUILD_OUTPUT_DIR = "target";

    /** The build descriptor whose presence marks a sibling {@link #BUILD_OUTPUT_DIR} as build output. */
    static final String BUILD_DESCRIPTOR = "pom.xml";

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
     * <p>The scan never throws for an I/O problem in the tree. A litter scan is
     * run late, when the job's work is already finished, so a failure to
     * traverse must not discard that work: a vanished entry is treated as gone,
     * and a scan that cannot complete is reported as a warning (see
     * {@link #scanRepo(Repo)}) rather than propagated.</p>
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
     *
     * <p>Walks with {@link Files#walkFileTree} so the git metadata directory and
     * build-output directories are never entered: another job on the same node
     * creates and deletes git lock files under {@code .git} constantly, and a
     * {@link Files#walk} stream that descended into it would throw
     * {@link NoSuchFileException} the moment such a lock vanished between being
     * listed and being stat'd. An entry that vanishes mid-walk anywhere is
     * treated as gone, and a walk that aborts outright is reported as a warning;
     * neither propagates, so a finished job is never discarded over a litter
     * scan.</p>
     */
    private List<String> scanRepo(Repo repo) {
        if (repo.root == null || !Files.isDirectory(repo.root)) {
            return Collections.emptyList();
        }

        Set<String> baseBranchLitter = baseBranchAllowlist(repo.root);
        LitterVisitor visitor = new LitterVisitor(repo.root, baseBranchLitter);
        try {
            Files.walkFileTree(repo.root, visitor);
        } catch (IOException e) {
            warn("Invalid-file scan of " + repo.root + " did not complete, "
                    + "reporting only what was found: " + e);
        }
        for (String failure : visitor.failures) {
            warn("Invalid-file scan skipped an unreadable entry: " + failure);
        }

        List<String> reported = new ArrayList<>();
        for (Path relative : visitor.found) {
            reported.add(repo.label == null
                    ? relative.toString() : repo.label + File.separator + relative);
        }
        return reported;
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
     * Scans {@code job}'s working trees and fails it when invalid files remain.
     *
     * <p>Gives the job one chance to clean up through
     * {@link GitManagedJob#onInvalidFilesDetected(List)} and re-scans; litter
     * that survives that throws, so the job is marked failed and no git
     * operations run. Lives here rather than on the job because every part of
     * the decision — what counts as litter, whether any remains, how to
     * describe it — is this detector's.</p>
     *
     * @param job                 the job whose trees to scan and fail
     * @param dependentRepoPaths  dependent repository paths to scan alongside
     *                            the primary working directory
     * @throws IllegalStateException if invalid files remain after correction
     */
    static void enforceNone(GitManagedJob job, List<String> dependentRepoPaths) {
        InvalidFileDetector detector = new InvalidFileDetector(job, dependentRepoPaths);
        detector.detect();
        if (!detector.isDetected()) return;

        job.warn("Invalid files detected in working tree: " + detector.getDescription());
        if (job.onInvalidFilesDetected(detector.getInvalidFiles())) {
            detector.detect();
        }

        if (detector.isDetected()) {
            throw new IllegalStateException(
                "Job failed: invalid files left in the repository working tree: "
                    + detector.getDescription()
                    + ". Binary (.bin) files must never be left behind — remove them "
                    + "or generate them outside the repository.");
        }
    }

    /**
     * A {@link SimpleFileVisitor} that collects {@code .bin} litter under a
     * repository root while refusing to enter the git metadata directory or
     * build-output directories, and tolerating entries that vanish mid-walk.
     *
     * <p>Skipping {@code .git} at {@link #preVisitDirectory} — rather than
     * walking into it and filtering afterwards — is what makes the scan safe on
     * a node shared by several jobs: a lock file under {@code .git} that another
     * job's git operation deletes between being listed and being stat'd would
     * otherwise abort the whole walk. A worktree's {@code .git} is a regular
     * file pointing at a git directory outside the working tree, so there is no
     * subtree to enter for it and it is simply never flagged as litter.</p>
     */
    private static final class LitterVisitor extends SimpleFileVisitor<Path> {

        /** Working-tree root the scan is relative to. */
        private final Path root;

        /** {@code .bin} paths, in git form, present on the base branch (not litter). */
        private final Set<String> baseBranchLitter;

        /** Working-tree-relative paths of the litter found so far. */
        private final List<Path> found = new ArrayList<>();

        /** Descriptions of entries the walk could not read (excluding vanished ones). */
        private final List<String> failures = new ArrayList<>();

        /**
         * Creates a visitor for one repository.
         *
         * @param root             working-tree root being scanned
         * @param baseBranchLitter {@code .bin} paths (git form) exempt as pre-existing
         */
        private LitterVisitor(Path root, Set<String> baseBranchLitter) {
            this.root = root;
            this.baseBranchLitter = baseBranchLitter;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!dir.equals(root) && isSkippedDirectory(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()
                    && file.getFileName().toString().endsWith(INVALID_EXTENSION)) {
                Path relative = root.relativize(file);
                if (!baseBranchLitter.contains(toGitPath(relative))) {
                    found.add(relative);
                }
            }
            return FileVisitResult.CONTINUE;
        }

        /**
         * Records a failure to read an entry, unless the entry has simply
         * vanished. On a shared node another job deletes files — above all git
         * lock files — constantly, so a path listed a moment ago may be absent
         * by the time it is visited; a {@link NoSuchFileException} is therefore
         * "gone", not an error. Any other I/O failure is recorded so the scan
         * can warn about it afterwards. Either way the walk continues: one bad
         * entry must not abort the scan.
         */
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            if (!(exc instanceof NoSuchFileException)) {
                failures.add(file + ": " + exc);
            }
            return FileVisitResult.CONTINUE;
        }

        /**
         * Returns whether the given directory is excluded from the scan: the git
         * metadata directory ({@link InvalidFileDetector#GIT_DIR}), or a Maven
         * module's build-output directory
         * ({@link InvalidFileDetector#BUILD_OUTPUT_DIR} with a sibling
         * {@link InvalidFileDetector#BUILD_DESCRIPTOR}).
         */
        private boolean isSkippedDirectory(Path dir) {
            Path name = dir.getFileName();
            if (name == null) {
                return false;
            }
            if (GIT_DIR.equals(name.toString())) {
                return true;
            }
            return BUILD_OUTPUT_DIR.equals(name.toString())
                    && Files.isRegularFile(dir.resolveSibling(BUILD_DESCRIPTOR));
        }
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
