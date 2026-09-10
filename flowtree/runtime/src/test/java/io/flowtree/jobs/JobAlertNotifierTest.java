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

import io.flowtree.workstream.Workstream;
import org.almostrealism.io.Alert;
import org.almostrealism.io.Console;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link JobAlertNotifier}, the completion listener that publishes
 * job outcomes on the console alert bus.
 */
public class JobAlertNotifierTest extends TestSuiteBase {

	/** Known workstreams, keyed by ID, resolved by the notifier under test. */
	private final Map<String, Workstream> workstreams = new HashMap<>();

	/**
	 * Returns a notifier resolving workstreams from {@link #workstreams}.
	 *
	 * @return the notifier under test
	 */
	protected JobAlertNotifier notifier() {
		return new JobAlertNotifier(workstreams::get);
	}

	/**
	 * Registers a workstream the notifier can resolve.
	 *
	 * @param workstreamId the workstream identifier
	 * @param channelName  the channel name to report, or {@code null}
	 */
	protected void withWorkstream(String workstreamId, String channelName) {
		workstreams.put(workstreamId,
				new Workstream(workstreamId, "C123", channelName));
	}

	/** A completed job produces an alert naming the outcome and the channel. */
	@Test(timeout = 10000)
	public void testCompletionSummaryNamesOutcomeAndChannel() {
		withWorkstream("ws-1", "feature-alerts");

		String summary = notifier().summarize("ws-1",
				JobCompletionEvent.success("job-1", "Add alert delivery"));

		assertEquals("Job success (feature-alerts): Add alert delivery", summary);
	}

	/** An unknown workstream still yields an alert, without a channel name. */
	@Test(timeout = 10000)
	public void testUnknownWorkstreamOmitsChannelName() {
		String summary = notifier().summarize("ws-missing",
				JobCompletionEvent.success("job-1", "Add alert delivery"));

		assertEquals("Job success: Add alert delivery", summary);
	}

	/** A long description is shortened so the alert fits a length-capped channel. */
	@Test(timeout = 10000)
	public void testLongDescriptionIsShortened() {
		StringBuilder description = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			description.append("long ");
		}

		String summary = notifier().summarize("ws-1",
				JobCompletionEvent.success("job-1", description.toString()));

		assertTrue(summary.endsWith("..."));
		assertTrue(summary.length() < description.length());
	}

	/** A pull request URL is carried in the alert when the event has one. */
	@Test(timeout = 10000)
	public void testPullRequestUrlIncluded() {
		String summary = notifier().summarize("ws-1",
				JobCompletionEvent.success("job-1", "Add alert delivery")
						.withPullRequestUrl("https://example.invalid/pr/1"));

		assertTrue(summary.contains("| PR: https://example.invalid/pr/1"));
	}

	/** Completion reaches a registered delivery provider; submission does not. */
	@Test(timeout = 10000)
	public void testOnlyCompletionReachesDeliveryProvider() {
		List<Alert> delivered = new ArrayList<>();
		// A child console keeps the provider out of the root console's
		// permanent registration list, which has no removal operation.
		Console child = Console.root().child();
		child.addAlertDeliveryProvider(delivered::add);

		JobAlertNotifier notifier = new JobAlertNotifier(workstreams::get) {
			@Override
			public Console console() {
				return child;
			}
		};

		JobCompletionEvent event =
				JobCompletionEvent.success("job-1", "Add alert delivery");

		notifier.onJobSubmitted("ws-1", event);
		notifier.onJobStarted("ws-1", event);
		assertTrue(delivered.isEmpty());

		notifier.onJobCompleted("ws-1", event);
		assertEquals(1, delivered.size());
		assertEquals(Alert.Severity.INFO, delivered.get(0).getSeverity());
		assertTrue(delivered.get(0).getMessage().contains("Add alert delivery"));
	}

	/** A failed job reports the failure outcome rather than success. */
	@Test(timeout = 10000)
	public void testFailedJobReportsFailure() {
		String summary = notifier().summarize("ws-1",
				JobCompletionEvent.failed("job-1", "Add alert delivery", "boom", null));

		assertTrue(summary.startsWith("Job "));
		assertFalse(summary.contains("success"));
	}
}
