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

import java.util.Set;

/**
 * Central registry and commit-time gate for files that a {@link GitManagedJob}
 * process writes into the repository working tree — the harness itself and every
 * phase (falsification, retrospective, commit-message, &hellip;).
 *
 * <p>Every such asset belongs under a single dedicated directory,
 * {@link #DIRECTORY}, at the working-tree root. The commit phase
 * ({@link GitCommitHandler}) refuses to stage <em>anything</em> under that
 * directory unless the file's repository-relative path appears on
 * {@link #COMMIT_WHITELIST}. The whitelist is intentionally <b>EMPTY</b>: today
 * nothing under {@code .flowtree/} is ever committed. It is the forward-looking
 * escape hatch for any future asset that we deliberately decide should be
 * committed — adding its path to the whitelist is the single, explicit action
 * required.</p>
 *
 * <p>Routing every artifact here and excluding the directory from commits makes
 * the no-leak guarantee <em>structural</em> rather than per-file: a new phase
 * that drops a scratch file under {@code .flowtree/} cannot leak it into a
 * commit, and no new {@code .gitignore} entry is required to keep it out. This
 * is the design response to a phase result file
 * ({@code falsification-results.json}) once leaking into the working tree
 * because it had no gitignore protection.</p>
 *
 * @author Michael Murray
 * @see GitCommitHandler
 */
final class FlowtreeArtifacts {

    /** Repository-relative directory that holds every job/phase/harness working-tree artifact. */
    static final String DIRECTORY = ".flowtree";

    /**
     * Directory under {@link #DIRECTORY} into which the harness writes raw agent
     * output captures (one JSON file per session).
     */
    static final String OUTPUT_CAPTURE_DIRECTORY = DIRECTORY + "/claude-output";

    /**
     * Repository-relative paths under {@link #DIRECTORY} that the commit phase is
     * permitted to stage and commit. <b>EMPTY by design</b> — see the class
     * javadoc. A real gate, not a comment: {@link #isExcludedFromCommit} consults
     * this set, so adding a normalized path here is sufficient (and necessary) to
     * let that one file be committed.
     */
    static final Set<String> COMMIT_WHITELIST = Set.of();

    /** Prevents instantiation; this class only exposes static helpers and constants. */
    private FlowtreeArtifacts() {
    }

    /**
     * Returns {@code name} resolved under {@link #DIRECTORY}. For example,
     * {@code "falsification-results.json"} becomes
     * {@code ".flowtree/falsification-results.json"}.
     *
     * @param name the bare artifact file name
     * @return the repository-relative path of the artifact under {@link #DIRECTORY}
     */
    static String inDirectory(String name) {
        return DIRECTORY + "/" + name;
    }

    /**
     * Normalizes a repository-relative path for comparison: backslashes become
     * forward slashes, surrounding whitespace is trimmed, and any leading
     * {@code ./} or {@code /} segments are stripped.
     *
     * @param path the path to normalize, possibly {@code null}
     * @return the normalized path, or the empty string when {@code path} is null
     */
    static String normalize(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    /**
     * Returns whether {@code repoRelativePath} lies within {@link #DIRECTORY}
     * (either the directory itself or any path beneath it).
     *
     * @param repoRelativePath the candidate path, relative to the working tree
     * @return {@code true} if the path is the artifact directory or contained in it
     */
    static boolean isUnderDirectory(String repoRelativePath) {
        String p = normalize(repoRelativePath);
        return p.equals(DIRECTORY) || p.startsWith(DIRECTORY + "/");
    }

    /**
     * Returns whether the commit phase must exclude {@code repoRelativePath} from
     * staging and committing.
     *
     * <p>A path <em>outside</em> {@link #DIRECTORY} is never excluded by this gate
     * — only the agent's own working-tree changes flow through here untouched. A
     * path <em>under</em> the directory is excluded unless its normalized form is
     * present in {@code whitelist}.</p>
     *
     * @param repoRelativePath the candidate path, relative to the working tree
     * @param whitelist        the permitted paths under {@link #DIRECTORY};
     *                         {@code null} is treated as {@link #COMMIT_WHITELIST}
     * @return {@code true} if the path must not be staged or committed
     */
    static boolean isExcludedFromCommit(String repoRelativePath, Set<String> whitelist) {
        if (!isUnderDirectory(repoRelativePath)) {
            return false;
        }
        Set<String> effective = whitelist == null ? COMMIT_WHITELIST : whitelist;
        // TODO(review): whitelist entries are NOT normalized here; only the incoming path is.
        // When the whitelist becomes non-empty, entries must be pre-normalized (forward slashes,
        // no leading "./" or "/") or this contains() check may silently fail.
        return !effective.contains(normalize(repoRelativePath));
    }
}
