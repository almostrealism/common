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

package io.flowtree.slack;

import io.flowtree.workstream.Workstream;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The Slack channel name a workstream is given when its registration does not
 * name one, derived from the workstream's branch.
 *
 * <p>Created with {@link #fromBranch(String)}, which applies the naming rule,
 * and made unique against the workstreams already registered with
 * {@link #uniqueAmong(Collection)}. {@link #toString()} is the channel name
 * itself, without a leading {@code #}. Existing channels are never renamed:
 * the name is derived once, at registration.</p>
 */
public final class WorkstreamChannelName {

    /** Slack's limit on the length of a channel name. */
    public static final int MAX_LENGTH = 80;

    /**
     * Fewest characters the branch segments in a name must hold before no
     * further segment is added in front. Counts each segment's own
     * characters, not the hyphens that join them.
     */
    public static final int MIN_SEGMENT_CHARACTERS = 4;

    /** Prefix that marks a channel as a workstream's. */
    private static final String PREFIX = "w-";

    /** Highest collision suffix {@link #uniqueAmong(Collection)} tries. */
    private static final int MAX_COLLISION_SUFFIX = 99;

    /** The channel name, without a leading {@code #}. */
    private final String name;

    /**
     * Wraps an already-derived channel name.
     *
     * @param name the channel name
     */
    private WorkstreamChannelName(String name) {
        this.name = name;
    }

    /**
     * Derives a channel name from a git branch name.
     *
     * <p>Each {@code /}-separated segment is sanitized: lower-cased, every
     * character other than a lowercase letter, digit or hyphen replaced with a
     * hyphen, runs of hyphens collapsed, and leading/trailing hyphens trimmed.
     * The name is built from the end of the branch: it starts with the last
     * segment, and while the segments taken so far hold fewer than
     * {@link #MIN_SEGMENT_CHARACTERS} characters the previous segment is added
     * in front, joined with {@code -}. A segment that sanitizes to nothing is
     * skipped. So {@code feature/my-thing-phases/0} becomes
     * {@code w-my-thing-phases-0} rather than {@code w-0}, and
     * {@code feature/my-phase/2/0/1} becomes {@code w-my-phase-2-0-1}, while
     * {@code feature/my-xyz} is {@code w-my-xyz} as before.</p>
     *
     * <p>The leading segment is usually a type ({@code feature}, {@code ci},
     * {@code fix}, ...). Being first, it is reached only when everything after
     * it is still too short, and it is then used like any other segment:
     * {@code ci/abc} becomes {@code w-ci-abc}, so a short name still says what
     * kind of branch it came from instead of being three bare characters. Once
     * every segment has been used the name is kept however short it is.</p>
     *
     * <p>{@code "w-"} is then prepended and the result truncated to
     * {@link #MAX_LENGTH}, so a long joined name is cut after joining.</p>
     *
     * @param branch the git branch name (e.g. {@code "feature/my-xyz"})
     * @return the derived name (e.g. {@code w-my-xyz}), or {@code null} when
     *         the branch is {@code null} or empty, or its last segment is
     *         empty after sanitizing
     */
    public static WorkstreamChannelName fromBranch(String branch) {
        if (branch == null || branch.isEmpty()) {
            return null;
        }
        String[] segments = branch.split("/", -1);
        String last = sanitize(segments[segments.length - 1]);
        if (last.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder(last);
        int characters = last.length();
        for (int i = segments.length - 2; i >= 0 && characters < MIN_SEGMENT_CHARACTERS; i--) {
            String segment = sanitize(segments[i]);
            if (!segment.isEmpty()) {
                joined.insert(0, segment + "-");
                characters += segment.length();
            }
        }
        String name = PREFIX + joined;
        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return new WorkstreamChannelName(name);
    }

    /**
     * Returns this name, or the first variant of it no registered workstream
     * already uses, made by appending {@code -2}, {@code -3}, ... up to
     * {@code -99}. A suffix that would pass {@link #MAX_LENGTH} replaces the
     * end of the name instead.
     *
     * @param existing the workstreams currently registered; their channel
     *                 names are compared with any leading {@code #} removed
     * @return a name no workstream in {@code existing} uses, or {@code null}
     *         when every suffix is taken
     */
    public WorkstreamChannelName uniqueAmong(Collection<Workstream> existing) {
        Set<String> taken = new HashSet<>();
        for (Workstream ws : existing) {
            String cn = ws.getChannelName();
            if (cn != null) {
                taken.add(cn.startsWith("#") ? cn.substring(1) : cn);
            }
        }
        if (!taken.contains(name)) {
            return this;
        }
        for (int i = 2; i <= MAX_COLLISION_SUFFIX; i++) {
            String suffix = "-" + i;
            String candidate = name.length() + suffix.length() <= MAX_LENGTH
                    ? name + suffix
                    : name.substring(0, MAX_LENGTH - suffix.length()) + suffix;
            if (!taken.contains(candidate)) {
                return new WorkstreamChannelName(candidate);
            }
        }
        return null;
    }

    /**
     * Sanitizes one branch segment for use in a channel name.
     *
     * @param segment one {@code /}-separated part of a branch name
     * @return the segment lower-cased, with characters other than
     *         {@code [a-z0-9-]} replaced by hyphens, hyphen runs collapsed and
     *         edge hyphens trimmed; empty when nothing usable remains
     */
    private static String sanitize(String segment) {
        return segment.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
    }

    /**
     * Returns the channel name, without a leading {@code #}.
     *
     * @return the channel name
     */
    @Override
    public String toString() {
        return name;
    }

    /**
     * Compares channel names.
     *
     * @param other the object to compare with
     * @return whether {@code other} is a channel name with the same text
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof WorkstreamChannelName
                && name.equals(((WorkstreamChannelName) other).name);
    }

    /**
     * Hashes the channel name's text.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
