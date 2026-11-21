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
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Durable record of which production classes a {@link ObjectOrientedDesignRule} review has already
 * judged, keyed by class path and the git blob id of the exact content that was judged.
 *
 * <p>A branch is worked by many jobs over its life, and every job sees the whole branch diff, so
 * without a memory the review re-judges a class each job even though its content has not changed
 * since the last clean verdict — most of the review's historical cost was exactly that repetition.
 * This ledger is that memory. A class whose current blob id already carries a {@code CLEAN} or
 * {@code FIXED} verdict is skipped; any edit to the class changes its blob id, so a changed class
 * is reviewed again automatically.</p>
 *
 * <p>The ledger file lives <em>outside</em> the git working tree — alongside the workspace lock
 * under the workspace parent — because the between-job repository preparation runs
 * {@code git stash push --include-untracked}, which would hide an in-tree scratch file from the
 * next job. Keeping it beside the lock makes it survive the stash and be shared by sibling
 * containers that bind-mount the same repository. The file is a plain tab-separated list, one
 * verdict per line: {@code <blobId>\t<verdict>\t<classPath>}.</p>
 *
 * <p>The same mechanism records the verdicts of the new-type audit ({@link NewTypeAudit}), in a
 * ledger of its own with its own settled verdicts: there the key is a digest of the type's source
 * and its justification, and the path is the type's fully-qualified name.</p>
 *
 * @see ObjectOrientedDesignRule
 * @see NewTypeAudit
 * @see WorkspaceLock
 */
class ObjectOrientedReviewLedger {

    /** Separator between the two keys and the verdict on a ledger line. */
    private static final String SEPARATOR = "\t";

    /** The verdicts the design review records: {@code CLEAN} and {@code FIXED}. */
    static final Set<String> REVIEW_VERDICTS = Set.of("CLEAN", "FIXED");

    /** Directory, under the workspace parent, that holds every branch ledger. */
    static final String LEDGER_DIR = ".flowtree-locks/oop-verdicts";

    /** The backing file for this branch's verdicts. */
    private final Path file;

    /** The verdicts worth remembering; any other value is never recorded. */
    private final Set<String> settled;

    /** Recorded verdicts, keyed by {@code blobId + SEPARATOR + classPath}; loaded lazily. */
    private Map<String, String> verdicts;

    /**
     * Creates a ledger backed by the given file. The file is read on first use and need not exist.
     *
     * @param file the per-branch ledger file
     */
    ObjectOrientedReviewLedger(Path file) {
        this(file, REVIEW_VERDICTS);
    }

    /**
     * Creates a ledger backed by the given file that remembers the given verdicts.
     *
     * @param file    the per-branch ledger file
     * @param settled the verdicts worth remembering
     */
    ObjectOrientedReviewLedger(Path file, Set<String> settled) {
        this.file = file;
        this.settled = Set.copyOf(settled);
    }

    /**
     * Returns the ledger for the branch {@code job} works on -- its target branch, or its base branch
     * when it has no target -- backed by
     * {@code <parent>/.flowtree-locks/oop-verdicts/<repo>/<branch><suffix>} beside the job's
     * checkout. A job without a working directory or branch gets a ledger over no file, which never
     * skips or records.
     *
     * @param job     the job whose branch the ledger is for
     * @param suffix  the file name suffix that tells the ledgers of one branch apart
     * @param settled the verdicts worth remembering
     * @return the ledger
     */
    static ObjectOrientedReviewLedger forBranch(GitManagedJob job, String suffix, Set<String> settled) {
        String branch = job.getTargetBranch() != null ? job.getTargetBranch() : job.getBaseBranch();
        return new ObjectOrientedReviewLedger(branchFile(job.getWorkingDirectory(), branch, suffix), settled);
    }

    /**
     * Returns the ledger file for a branch of the repository checked out at {@code workingDirectory}.
     *
     * @param workingDirectory the repository checkout, or {@code null}
     * @param branch           the branch, or {@code null}
     * @param suffix           the file name suffix that tells the ledgers of one branch apart
     * @return the file, or {@code null} when it cannot be resolved
     */
    private static Path branchFile(String workingDirectory, String branch, String suffix) {
        if (workingDirectory == null || branch == null || branch.isEmpty()) return null;
        Path repoRoot = Path.of(workingDirectory);
        Path parent = repoRoot.getParent();
        if (parent == null) return null;
        String repoName = repoRoot.getFileName() != null ? repoRoot.getFileName().toString() : "repo";
        return parent.resolve(LEDGER_DIR).resolve(repoName)
                .resolve(branch.replaceAll("[^A-Za-z0-9._-]", "-") + suffix);
    }

    /**
     * Reports whether the given class content has already been judged by a review.
     *
     * @param classPath the repository-relative path of the class
     * @param blobId    the git blob id of the content to check
     * @return whether a settled verdict is recorded for exactly this content
     */
    boolean isReviewed(String classPath, String blobId) {
        return verdict(classPath, blobId) != null;
    }

    /**
     * Returns the verdict recorded for the given content.
     *
     * @param classPath the repository-relative path of the class
     * @param blobId    the git blob id of the content to check
     * @return the settled verdict recorded for exactly this content, or {@code null}
     */
    String verdict(String classPath, String blobId) {
        if (blobId == null || classPath == null) return null;
        return load().get(key(blobId, classPath));
    }

    /**
     * Records a verdict for a class's content, so that a later job skips the class while its
     * content is unchanged. Only settled verdicts are retained ({@code CLEAN} and {@code FIXED} by
     * default); any other value leaves the ledger unchanged, so an unreviewed or failed class is
     * judged again.
     *
     * @param classPath the repository-relative path of the class
     * @param blobId    the git blob id of the judged content
     * @param verdict   the verdict; one of the settled verdicts
     */
    void record(String classPath, String blobId, String verdict) {
        if (blobId == null || classPath == null || !isSettled(verdict)) return;
        String entryKey = key(blobId, classPath);
        if (verdict.equals(load().get(entryKey))) return;
        verdicts.put(entryKey, verdict);
        persist(entryKey, verdict);
    }

    /**
     * @param verdict a verdict string, possibly {@code null}
     * @return whether it is a settled verdict worth remembering
     */
    private boolean isSettled(String verdict) {
        return verdict != null && settled.contains(verdict);
    }

    /**
     * @param blobId    the git blob id
     * @param classPath the class path
     * @return the map key combining both
     */
    private static String key(String blobId, String classPath) {
        return blobId + SEPARATOR + classPath;
    }

    /**
     * Returns the loaded verdict map, reading the backing file on first call. A missing or
     * unreadable file yields an empty map, so a ledger that cannot be read simply reviews
     * everything rather than failing the job.
     *
     * @return the verdict map
     */
    private Map<String, String> load() {
        if (verdicts != null) return verdicts;
        verdicts = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) return verdicts;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split(SEPARATOR, 3);
                if (parts.length == 3 && isSettled(parts[1])) {
                    verdicts.put(key(parts[0], parts[2]), parts[1]);
                }
            }
        } catch (IOException e) {
            verdicts.clear();
        }
        return verdicts;
    }

    /**
     * Appends one verdict line to the backing file, creating it and its parent directory when
     * needed. A write failure is swallowed: the in-memory record still prevents a repeat within the
     * current job, and the only cost of a lost line is one redundant review in a later job.
     *
     * @param entryKey the combined key, {@code blobId + SEPARATOR + classPath}
     * @param verdict  the verdict to record
     */
    private void persist(String entryKey, String verdict) {
        if (file == null) return;
        String[] parts = entryKey.split(SEPARATOR, 2);
        String line = parts[0] + SEPARATOR + verdict + SEPARATOR + parts[1] + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Keep the in-memory record; a later job re-reviews at worst.
        }
    }
}
