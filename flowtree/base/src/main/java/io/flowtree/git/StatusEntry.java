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

package io.flowtree.git;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of {@code git status --porcelain -z} output: the two-character
 * {@code XY} status code (index column, then working-tree column) and the
 * raw, unquoted path. For a rename or copy the path is the destination.
 *
 * <p>Holds the behaviour that reads a status code — whether the entry is a
 * {@linkplain #isRenameOrCopy() rename or copy} or an
 * {@linkplain #isUnmerged() unmerged} path — on the type that carries that
 * code, so callers act on the kind of change without re-deriving it from the
 * raw string. {@link GitOperations#parseChangedFiles(String)} maps these
 * entries to their paths.</p>
 *
 * @param code the two-character status code, such as {@code " M"} or {@code "UU"}
 * @param path the repository-relative path, verbatim
 *
 * @author  Michael Murray
 */
public record StatusEntry(String code, String path) {
    /**
     * Parses the output of {@code git status --porcelain -z} into its
     * entries, for callers that act on the kind of change as well as on
     * the path (such as a merge-conflict scan selecting
     * {@linkplain #isUnmerged() unmerged} entries). The field layout and
     * filtering are exactly those described on
     * {@link GitOperations#parseChangedFiles(String)}, which maps these
     * entries to their paths.
     *
     * @param statusOutput the raw output of a
     *                     {@code git status --porcelain -z} invocation
     * @return the status entries, in the order git reported them
     */
    public static List<StatusEntry> parseAll(String statusOutput) {
        List<StatusEntry> entries = new ArrayList<>();
        String[] fields = statusOutput.split("\0", -1);
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            if (field.length() <= 3) {
                continue;
            }
            StatusEntry entry = new StatusEntry(field.substring(0, 2), field.substring(3));
            // A rename or copy carries its source path as the next NUL
            // field; consume and discard it so only the destination is kept.
            if (entry.isRenameOrCopy()) {
                i++;
            }
            entries.add(entry);
        }
        return entries;
    }

    /**
     * Returns the paths of {@code entries}, in order.
     *
     * @param entries the status entries
     * @return each entry's path, in the order given
     */
    public static List<String> paths(List<StatusEntry> entries) {
        List<String> files = new ArrayList<>();
        for (StatusEntry entry : entries) {
            files.add(entry.path());
        }
        return files;
    }

    /**
     * Returns whether this entry is a rename or copy ({@code R} or
     * {@code C} in either column), whose source path git emits as the
     * following NUL-terminated field.
     *
     * @return {@code true} for a rename or copy entry
     */
    public boolean isRenameOrCopy() {
        return code.indexOf('R') >= 0 || code.indexOf('C') >= 0;
    }

    /**
     * Returns whether this entry is an unmerged (conflicted) path: one of
     * the codes {@code DD}, {@code AU}, {@code UD}, {@code UA},
     * {@code DU}, {@code AA} or {@code UU} that {@code git status} uses
     * while a merge has unresolved conflicts.
     *
     * @return {@code true} for an unmerged entry
     */
    public boolean isUnmerged() {
        return code.indexOf('U') >= 0 || "AA".equals(code) || "DD".equals(code);
    }
}
