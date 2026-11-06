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
import java.util.Set;

/**
 * What each side of an in-progress merge changed: the paths {@code HEAD} (the
 * target branch) and the merged commit each changed since their merge-base.
 *
 * <p>These two sets decide which version of a path a clean merge would have
 * produced, and therefore which version the harness may put in place of a
 * resolution it cannot commit. A path only one side changed has that side's
 * version; a path both sides changed has no version the harness can choose on
 * anyone's behalf.</p>
 *
 * @author Michael Murray
 * @see GitCommitHandler
 * @see WorkingTreeRescue
 */
final class MergeSides {

    /** The commit being merged into {@code HEAD}. */
    private final String mergedCommit;

    /** Paths {@code HEAD} changed since the merge-base. */
    private final Set<String> branchChanges;

    /** Paths {@link #mergedCommit} changed since the merge-base. */
    private final Set<String> baseChanges;

    /**
     * Creates the record of a merge's two sides.
     *
     * @param mergedCommit  the commit being merged
     * @param branchChanges the paths {@code HEAD} changed since the merge-base
     * @param baseChanges   the paths the merged commit changed since the merge-base
     */
    private MergeSides(String mergedCommit, Set<String> branchChanges, Set<String> baseChanges) {
        this.mergedCommit = mergedCommit;
        this.branchChanges = branchChanges;
        this.baseChanges = baseChanges;
    }

    /**
     * Reads what each side of the merge of {@code mergedCommit} into
     * {@code HEAD} changed.
     *
     * @param gitOps       git operations for the working tree
     * @param mergedCommit the commit being merged
     * @return the two sides' changes
     * @throws IOException if the merge-base or either side's changes cannot be listed
     * @throws InterruptedException if a git command is interrupted
     */
    static MergeSides of(FileStager.GitOperations gitOps, String mergedCommit)
            throws IOException, InterruptedException {
        String mergeBase = gitOps.executeOrNull("merge-base", "HEAD", mergedCommit);
        if (mergeBase == null || mergeBase.trim().isEmpty()) {
            throw new IOException("Could not find the merge-base of HEAD and " + mergedCommit);
        }
        return new MergeSides(mergedCommit,
                gitOps.changedPaths(mergeBase.trim(), "HEAD"),
                gitOps.changedPaths(mergeBase.trim(), mergedCommit));
    }

    /**
     * Returns the commit being merged.
     *
     * @return the merged commit
     */
    String mergedCommit() {
        return mergedCommit;
    }

    /**
     * Returns whether the merged commit changed {@code path}.
     *
     * @param path a repository-relative path
     * @return {@code true} when the base side changed it
     */
    boolean baseChanged(String path) {
        return baseChanges.contains(path);
    }

    /**
     * Returns whether {@code HEAD} changed {@code path}.
     *
     * @param path a repository-relative path
     * @return {@code true} when the target branch changed it
     */
    boolean branchChanged(String path) {
        return branchChanges.contains(path);
    }

    /**
     * Returns the commit whose version of {@code path} a clean merge would
     * produce, when the harness may choose it: {@code HEAD} for a path the
     * merged commit did not change, the merged commit for a path only it
     * changed (and only while it is trusted, see
     * {@link FileStager#trustedMergeParent}), and {@code null} otherwise.
     *
     * @param path    a repository-relative path
     * @param trusted whether the merged commit is the merge parent the harness recorded
     * @return {@code "HEAD"}, the merged commit, or {@code null} when no side can be chosen
     */
    String cleanSource(String path, boolean trusted) {
        if (!baseChanged(path)) return "HEAD";
        return trusted && !branchChanged(path) ? mergedCommit : null;
    }
}
