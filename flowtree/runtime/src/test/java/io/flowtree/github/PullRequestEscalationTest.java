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

package io.flowtree.github;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests how {@link PullRequestEscalation.Finding} is derived from a pull
 * request's file patches and review comments: the added {@code TODO} lines at
 * their new-file line numbers, and a review comment's one-line detail.
 */
public class PullRequestEscalationTest extends TestSuiteBase {

    /**
     * An added line carrying a {@code TODO} marker is reported at its new-file
     * line number, tracked from the hunk header. A removed line does not advance
     * the counter; a context line does.
     */
    @Test(timeout = 10000)
    public void addedTodosCountFromTheHunkHeader() {
        String patch = "@@ -10,2 +10,4 @@\n"
                + " context line\n"
                + "-removed line\n"
                + "+// TODO(review): first\n"
                + "+plain added line\n"
                + "+// TODO: second\n";
        List<PullRequestEscalation.Finding> todos =
                PullRequestEscalation.Finding.addedTodos("A.java", patch, "https://example/A.java");
        assertEquals(2, todos.size());
        assertEquals(11, todos.get(0).line());
        assertEquals(13, todos.get(1).line());
        assertEquals("A.java", todos.get(0).path());
        assertEquals("https://example/A.java#L11", todos.get(0).url());
    }

    /**
     * A word that merely contains the letters {@code TODO} is not a {@code TODO}
     * marker, matching {@code extract_branch_todos.py}'s word-boundary match.
     */
    @Test(timeout = 10000)
    public void addedTodosRequireAWordBoundary() {
        String patch = "@@ -1,0 +1,2 @@\n"
                + "+private TODOList items;\n"
                + "+// TODO: real one\n";
        List<PullRequestEscalation.Finding> todos =
                PullRequestEscalation.Finding.addedTodos("A.java", patch, null);
        assertEquals(1, todos.size());
        assertEquals(2, todos.get(0).line());
        assertNull("no blob URL means no link", todos.get(0).url());
    }

    /**
     * A {@code "\ No newline at end of file"} marker is neither an added nor a
     * context line, so it does not advance the new-file counter.
     */
    @Test(timeout = 10000)
    public void addedTodosIgnoreTheNoNewlineMarker() {
        String patch = "@@ -1 +1,2 @@\n"
                + " context\n"
                + "\\ No newline at end of file\n"
                + "+// TODO: after the marker\n";
        List<PullRequestEscalation.Finding> todos =
                PullRequestEscalation.Finding.addedTodos("A.java", patch, null);
        assertEquals(1, todos.size());
        assertEquals(2, todos.get(0).line());
    }

    /**
     * A GitHub file-patch has no {@code +++} file header, so an added line whose
     * content itself begins with {@code ++} is a real added line and its TODO is
     * reported rather than discarded as a header.
     */
    @Test(timeout = 10000)
    public void addedTodosExamineContentStartingWithPlusPlus() {
        String patch = "@@ -1,0 +1,2 @@\n"
                + "+++counter; // TODO: fix the increment\n"
                + "+plain added line\n";
        List<PullRequestEscalation.Finding> todos =
                PullRequestEscalation.Finding.addedTodos("A.java", patch, null);
        assertEquals(1, todos.size());
        assertEquals(1, todos.get(0).line());
    }

    /** A patch with no added TODO line reports none. */
    @Test(timeout = 10000)
    public void addedTodosReportNoneWhenAbsent() {
        String patch = "@@ -1,2 +1,2 @@\n"
                + " unchanged\n"
                + "+a plain added line\n";
        assertTrue(PullRequestEscalation.Finding.addedTodos("A.java", patch, null).isEmpty());
    }

    /**
     * A review comment's detail is the first non-blank line of its body,
     * shortened with an ellipsis when it is long, and absent for a blank body.
     */
    @Test(timeout = 10000)
    public void reviewCommentDetailIsTheFirstNonBlankLine() {
        PullRequestEscalation.Finding finding = PullRequestEscalation.Finding.reviewComment(
                "A.java", 4, "https://example/c", "\n  First line  \nsecond line");
        assertEquals("First line", finding.detail());
        assertEquals(4, finding.line());

        String longLine = "x".repeat(200);
        String shortened = PullRequestEscalation.Finding.reviewComment(
                "A.java", 4, null, longLine).detail();
        assertEquals(120, shortened.length());
        assertTrue(shortened.endsWith("..."));

        assertNull(PullRequestEscalation.Finding.reviewComment("A.java", 0, null, " \n ").detail());
    }
}
