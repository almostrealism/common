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

package io.flowtree.controller;

import io.flowtree.github.PullRequestEscalation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Covers {@link RemediationEscalation}: the cap-reached ping lists the PR's
 * unresolved review threads and the TODO lines the branch added, each with a
 * link, and degrades to the plain cap message when no PR could be resolved.
 */
public class RemediationEscalationTest extends TestSuiteBase {

    /** The ping lists each unresolved thread and added TODO with its link. */
    @Test(timeout = 30000)
    public void listsThreadsAndTodosWithLinks() {
        PullRequestEscalation facts = new PullRequestEscalation(
                "https://github.com/o/r/pull/42",
                List.of(new PullRequestEscalation.Finding("src/Foo.java", 10,
                        "https://github.com/o/r/pull/42#discussion_r1", "data race on lane sum")),
                List.of(new PullRequestEscalation.Finding("src/Bar.java", 7,
                        "https://github.com/o/r/blob/abc/src/Bar.java#L7", null)));
        RemediationEscalation escalation = new RemediationEscalation((repoUrl, branch) -> facts);

        String message = escalation.compose("git@github.com:o/r.git", "feature", 8);

        assertTrue("states the cap", message.contains("cap"));
        assertTrue("links the PR", message.contains("https://github.com/o/r/pull/42"));
        assertTrue("lists the unresolved thread's file and link",
                message.contains("src/Foo.java")
                    && message.contains("https://github.com/o/r/pull/42#discussion_r1"));
        assertTrue("lists the added TODO's file and link",
                message.contains("src/Bar.java")
                    && message.contains("https://github.com/o/r/blob/abc/src/Bar.java#L7"));
    }

    /** With no unresolved threads or TODOs, each section says so explicitly. */
    @Test(timeout = 30000)
    public void saysNoneForEmptySections() {
        RemediationEscalation escalation =
                new RemediationEscalation((repoUrl, branch) -> PullRequestEscalation.NONE);

        String message = escalation.compose("git@github.com:o/r.git", "feature", 8);

        assertTrue("states the cap", message.contains("cap"));
        assertTrue("names the review-thread section", message.contains("Unresolved review threads"));
        assertTrue("names the TODO section", message.contains("TODOs added on this branch"));
        assertTrue("says none where there is nothing to list", message.contains("- none"));
    }

    /** A null lookup result degrades to the plain cap message, never a crash. */
    @Test(timeout = 30000)
    public void degradesToThePlainMessageWhenLookupReturnsNull() {
        RemediationEscalation escalation = new RemediationEscalation((repoUrl, branch) -> null);

        String message = escalation.compose(null, null, 8);

        assertTrue(message.contains(RemediationBudgetGuard.capReachedMessage(8)));
    }
}
