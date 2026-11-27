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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link WorkstreamChannelName}: how a branch becomes a channel name, and how
 * a name is made unique among the workstreams already registered.
 */
public class WorkstreamChannelNameTest extends TestSuiteBase {

    /**
     * Derives the channel name for a branch, as text. Shared with the registration tests in
     * this package, which check the same derivation through the API.
     *
     * @param branch the branch name
     * @return the derived name, or {@code null} when none can be derived
     */
    static String name(String branch) {
        WorkstreamChannelName name = WorkstreamChannelName.fromBranch(branch);
        return name == null ? null : name.toString();
    }

    /** A phase number alone is too short, so the segment before it is added. */
    @Test(timeout = 10000)
    public void phasedBranchKeepsTheSegmentBeforeThePhase() {
        assertEquals("w-my-thing-phases-0", name("feature/my-thing-phases/0"));
    }

    /**
     * Segments are added one at a time until their own characters reach four: {@code 2},
     * {@code 0} and {@code 1} hold three, so {@code my-phase} is added as well. The hyphens
     * that join segments do not count, or {@code 2-0-1} would have stopped at five.
     */
    @Test(timeout = 10000)
    public void severalShortSegmentsAreJoinedUntilTheyHoldFourCharacters() {
        assertEquals("w-my-phase-2-0-1", name("feature/my-phase/2/0/1"));
    }

    /** A last segment that is already long enough is the whole name, as before. */
    @Test(timeout = 10000)
    public void longEnoughLastSegmentIsUnchanged() {
        assertEquals("w-my-xyz", name("feature/my-xyz"));
        assertEquals("w-auto-gen", name("feature/auto-gen"));
    }

    /**
     * The type segment is added only when everything after it is still too short, and then
     * like any other segment.
     */
    @Test(timeout = 10000)
    public void typeSegmentIsTheLastResort() {
        assertEquals("w-ci-abc", name("ci/abc"));
        assertEquals("w-fix-ab", name("fix/ab"));
    }

    /** A branch with a single short segment keeps it, however short: there is nothing to add. */
    @Test(timeout = 10000)
    public void singleShortSegmentIsKept() {
        assertEquals("w-ab", name("ab"));
    }

    /** Segments that sanitize to nothing are skipped rather than joined as empty text. */
    @Test(timeout = 10000)
    public void emptySegmentsAreSkipped() {
        assertEquals("w-feature-x", name("feature//x"));
        assertEquals("w-feature-x", name("feature/__/x"));
    }

    /** Each segment is sanitized on its own before the segments are joined. */
    @Test(timeout = 10000)
    public void segmentsAreSanitizedBeforeJoining() {
        assertEquals("w-my-thing-v1", name("Feature/My_Thing!/v1"));
    }

    /** A branch whose last segment is empty, or empty once sanitized, has no channel name. */
    @Test(timeout = 10000)
    public void emptyLastSegmentHasNoName() {
        assertNull(name("feature/"));
        assertNull(name("feature/___"));
        assertNull(name(""));
        assertNull(name(null));
    }

    /** The 80-character limit is applied to the joined name, after the prefix. */
    @Test(timeout = 10000)
    public void lengthLimitAppliesAfterJoining() {
        String longSegment = "a".repeat(100);
        String derived = name("feature/" + longSegment + "/0");
        assertEquals(WorkstreamChannelName.MAX_LENGTH, derived.length());
        assertTrue(derived.startsWith("w-aaaa"));
    }

    /** A cut that lands on a joining hyphen does not leave the name ending in one. */
    @Test(timeout = 10000)
    public void truncationDoesNotEndOnAHyphen() {
        String segment = "b".repeat(WorkstreamChannelName.MAX_LENGTH - 3);
        String derived = name("feature/" + segment + "/1");
        assertEquals("w-" + segment, derived);
    }

    /** A name no workstream uses is returned unchanged. */
    @Test(timeout = 10000)
    public void unusedNameIsUnique() {
        WorkstreamChannelName derived = WorkstreamChannelName.fromBranch("feature/my-xyz");
        assertSame(derived, derived.uniqueAmong(Collections.emptyList()));
    }

    /** A taken name gets the first free numeric suffix; a leading {@code #} is ignored. */
    @Test(timeout = 10000)
    public void takenNameGetsTheFirstFreeSuffix() {
        List<Workstream> existing = Arrays.asList(
                new Workstream("C1", "#w-my-xyz"),
                new Workstream("C2", "w-my-xyz-2"));
        WorkstreamChannelName unique =
                WorkstreamChannelName.fromBranch("feature/my-xyz").uniqueAmong(existing);
        assertEquals("w-my-xyz-3", unique.toString());
    }

    /** A collision suffix that would pass the length limit replaces the end of the name. */
    @Test(timeout = 10000)
    public void collisionSuffixThatWouldExceedTheLimitReplacesTheEnd() {
        String segment = "a".repeat(WorkstreamChannelName.MAX_LENGTH);
        WorkstreamChannelName derived = WorkstreamChannelName.fromBranch("feature/" + segment);
        assertEquals(WorkstreamChannelName.MAX_LENGTH, derived.toString().length());

        List<Workstream> existing = Collections.singletonList(
                new Workstream("C1", derived.toString()));
        WorkstreamChannelName unique = derived.uniqueAmong(existing);
        assertEquals(WorkstreamChannelName.MAX_LENGTH, unique.toString().length());
        assertTrue(unique.toString().endsWith("-2"));
    }

    /** When every numeric suffix is already taken, no unique name can be made. */
    @Test(timeout = 10000)
    public void exhaustedSuffixesYieldNoName() {
        WorkstreamChannelName derived = WorkstreamChannelName.fromBranch("feature/my-xyz");
        List<Workstream> existing = new ArrayList<>();
        existing.add(new Workstream("C1", "w-my-xyz"));
        for (int i = 2; i <= 99; i++) {
            existing.add(new Workstream("C" + i, "w-my-xyz-" + i));
        }
        assertNull(derived.uniqueAmong(existing));
    }

    /** Names are equal, and hash alike, exactly when their text matches. */
    @Test(timeout = 10000)
    public void equalsAndHashCodeFollowTheText() {
        WorkstreamChannelName a = WorkstreamChannelName.fromBranch("feature/my-xyz");
        WorkstreamChannelName b = WorkstreamChannelName.fromBranch("feature/my-xyz");
        WorkstreamChannelName c = WorkstreamChannelName.fromBranch("feature/other");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "w-my-xyz");
        assertNotEquals(a, null);
    }
}
