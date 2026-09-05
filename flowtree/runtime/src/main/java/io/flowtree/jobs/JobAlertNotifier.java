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
import org.almostrealism.io.AlertDeliveryProvider;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.Function;

/**
 * Publishes a job completion as an {@link Alert} on the console alert bus,
 * where any registered {@link AlertDeliveryProvider} (SMS, e-mail, webhook)
 * picks it up.
 *
 * <p>Alerting is a delivery channel in its own right, independent of any
 * chat integration: a deployment with no Slack connection still wants to be
 * told when a job finishes, and a deployment with Slack may want alerts to
 * reach a different audience than the channel post does. This listener
 * therefore stands beside the chat notifier rather than inside it, and is
 * registered through the same {@link JobCompletionListener} mechanism as any
 * other consumer of job events.</p>
 *
 * <p>Delivery is entirely the alert bus's concern. When no provider is
 * registered the alert is inert, so an unconfigured deployment pays nothing
 * for this listener being wired.</p>
 *
 * @author Michael Murray
 * @see JobCompletionListener
 * @see Alert
 */
public class JobAlertNotifier implements JobCompletionListener, ConsoleFeatures {

	/** Maximum number of description characters carried in an alert message. */
	private static final int descriptionLength = 80;

	/**
	 * Resolves a workstream ID to the workstream itself, so the alert can
	 * name the workstream an operator recognizes. May return {@code null}
	 * for an unknown ID, which simply omits the name from the alert.
	 */
	private final Function<String, Workstream> workstreams;

	/**
	 * Creates a notifier that resolves workstreams through the given lookup.
	 *
	 * @param workstreams resolves a workstream ID to its {@link Workstream},
	 *                    returning {@code null} when the ID is unknown
	 */
	public JobAlertNotifier(Function<String, Workstream> workstreams) {
		this.workstreams = workstreams;
	}

	@Override
	public void onJobCompleted(String workstreamId, JobCompletionEvent event) {
		alert(summarize(workstreamId, event));
	}

	/**
	 * Builds the one-line alert body for a completed job. Alerts are
	 * delivered over channels that charge per message and truncate without
	 * warning (SMS being the motivating case), so the summary stays short
	 * and leads with the outcome.
	 *
	 * @param workstreamId the workstream that owns the job
	 * @param event        the completion event
	 * @return the alert message text
	 */
	protected String summarize(String workstreamId, JobCompletionEvent event) {
		Workstream workstream = workstreams == null ?
				null : workstreams.apply(workstreamId);

		StringBuilder sb = new StringBuilder();
		sb.append("Job ").append(event.getStatus().name().toLowerCase());

		if (workstream != null && workstream.getChannelName() != null) {
			sb.append(" (").append(workstream.getChannelName()).append(")");
		}

		sb.append(": ").append(event.shortDescription(descriptionLength));

		if (event.getPullRequestUrl() != null) {
			sb.append(" | PR: ").append(event.getPullRequestUrl());
		}

		if (event.getCostUsd() > 0) {
			sb.append(String.format(" | $%.2f", event.getCostUsd()));
		}

		return sb.toString();
	}
}
