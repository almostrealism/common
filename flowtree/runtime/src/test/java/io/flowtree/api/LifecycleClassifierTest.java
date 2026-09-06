/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies {@link LifecycleClassifier}'s precedence rules, in particular the
 * idle-window comparison against a supplied {@code lastJobAt} and the
 * {@code null} (repo unresolved) versus empty-list (repo resolved, no PRs
 * for this branch) distinction in {@code branchPrs}.
 */
public class LifecycleClassifierTest extends TestSuiteBase {

	/** Builds a minimal workstream fixture with an optional explicit {@code kind}. */
	private Workstream workstream(String kind) {
		Workstream ws = new Workstream("ws-1", "C1", "#test");
		ws.setDefaultBranch("feature/x");
		if (kind != null) ws.setKind(kind);
		return ws;
	}

	/** Builds a minimal GitHub pull-request fixture as {@code classify} expects it. */
	private JsonNode pr(String state, String mergedAt, int number) {
		ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("state", state);
		node.put("number", number);
		if (mergedAt != null) {
			node.put("merged_at", mergedAt);
		} else {
			node.putNull("merged_at");
		}
		return node;
	}

	/** A standing/orchestrator {@code kind} always wins, regardless of PR or job data. */
	@Test(timeout = 10000)
	public void standingKindShortCircuitsRegardlessOfPrOrJobData() {
		LifecycleClassifier.Classification cls = LifecycleClassifier.classify(
				workstream("standing"), null, 14, Instant.now());
		assertEquals("standing", cls.label);
	}

	/** An open PR is the strongest activity signal. */
	@Test(timeout = 10000)
	public void openPrIsActive() {
		List<JsonNode> prs = Arrays.asList(pr("open", null, 7));
		LifecycleClassifier.Classification cls =
				LifecycleClassifier.classify(workstream(null), prs, 14, null);
		assertEquals("active", cls.label);
		assertTrue("reason should cite the open PR's number", cls.reason.contains("#7"));
	}

	/** A job within the idle window is active even without any PR. */
	@Test(timeout = 10000)
	public void recentJobIsActiveEvenWithNoPr() {
		Instant recentJob = Instant.now().minus(1, ChronoUnit.DAYS);
		LifecycleClassifier.Classification cls = LifecycleClassifier.classify(
				workstream(null), Collections.emptyList(), 14, recentJob);
		assertEquals("active", cls.label);
	}

	/** A merged PR with no job inside the idle window is merged. */
	@Test(timeout = 10000)
	public void mergedPrWithNoRecentJobIsMerged() {
		List<JsonNode> prs = Arrays.asList(pr("closed", "2026-08-01T00:00:00Z", 11));
		Instant staleJob = Instant.now().minus(30, ChronoUnit.DAYS);
		LifecycleClassifier.Classification cls =
				LifecycleClassifier.classify(workstream(null), prs, 14, staleJob);
		assertEquals("merged", cls.label);
		assertTrue(cls.reason.contains("#11"));
	}

	/** A job inside the idle window overrides an old merged PR: still active. */
	@Test(timeout = 10000)
	public void mergedPrWithRecentJobIsActiveNotMerged() {
		List<JsonNode> prs = Arrays.asList(pr("closed", "2026-08-01T00:00:00Z", 11));
		Instant recentJob = Instant.now().minus(1, ChronoUnit.DAYS);
		LifecycleClassifier.Classification cls =
				LifecycleClassifier.classify(workstream(null), prs, 14, recentJob);
		assertEquals("active", cls.label);
	}

	/** A closed, unmerged PR is abandoned. */
	@Test(timeout = 10000)
	public void closedUnmergedPrIsAbandoned() {
		List<JsonNode> prs = Arrays.asList(pr("closed", null, 5));
		LifecycleClassifier.Classification cls =
				LifecycleClassifier.classify(workstream(null), prs, 14, null);
		assertEquals("abandoned", cls.label);
	}

	/** An empty (but resolved) PR list is idle, not unknown. */
	@Test(timeout = 10000)
	public void resolvedRepoWithNoPrsForBranchIsIdleNotUnknown() {
		LifecycleClassifier.Classification cls = LifecycleClassifier.classify(
				workstream(null), Collections.emptyList(), 14, null);
		assertEquals("idle", cls.label);
	}

	/** A {@code null} PR list means the repository could not be resolved. */
	@Test(timeout = 10000)
	public void unresolvedRepoIsUnknown() {
		LifecycleClassifier.Classification cls =
				LifecycleClassifier.classify(workstream(null), null, 14, null);
		assertEquals("unknown", cls.label);
	}

	/**
	 * A branch with PRs in more than one state must cite the number belonging
	 * to the category actually returned, not whichever PR was encountered first.
	 */
	@Test(timeout = 10000)
	public void eachPrCategoryTracksItsOwnNumber() {
		List<JsonNode> prs = Arrays.asList(
				pr("closed", null, 3),
				pr("closed", "2026-08-01T00:00:00Z", 9));
		LifecycleClassifier.Classification cls =
				LifecycleClassifier.classify(workstream(null), prs, 14, null);
		assertEquals("merged", cls.label);
		assertTrue("reason must cite the merged PR's number, not the unrelated closed PR",
				cls.reason.contains("#9"));
	}
}
