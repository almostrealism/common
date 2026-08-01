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

/**
 * Slack integration for the FlowTree controller.
 *
 * <p>This package contains <em>only</em> the Slack-specific glue used to
 * submit jobs from Slack messages and post job status back to Slack:</p>
 * <ul>
 *   <li>{@link io.flowtree.slack.SlackListener} &mdash; parses Slack events
 *       and dispatches job submissions and slash commands.</li>
 *   <li>{@link io.flowtree.slack.SlackNotifier} &mdash; posts status messages
 *       to Slack channels.</li>
 *   <li>{@link io.flowtree.slack.SlackTokens} &mdash; OAuth token holder.</li>
 *   <li>{@link io.flowtree.slack.NotifierRegistry} &mdash; per-workspace
 *       {@link io.flowtree.slack.SlackNotifier} registry consulted by the
 *       HTTP API.</li>
 *   <li>{@link io.flowtree.slack.SlackSubmissionConfig} &mdash; wires the
 *       shared {@link io.flowtree.submission.SubmissionConfigResolver} into
 *       the Slack submission flow and translates resolver errors into a
 *       chat message.</li>
 * </ul>
 *
 * <p>Everything that is not Slack-specific has been moved out of this
 * package:</p>
 * <ul>
 *   <li>{@link io.flowtree.controller.FlowTreeController} &mdash; the
 *       top-level controller.</li>
 *   <li>{@link io.flowtree.api.FlowTreeApiEndpoint} and per-resource handlers
 *       &mdash; the HTTP API.</li>
 *   <li>{@link io.flowtree.workstream.Workstream} and
 *       {@link io.flowtree.workstream.WorkstreamConfig} &mdash; workstream
 *       model and persistence.</li>
 *   <li>{@code io.flowtree.submission.*} &mdash; submission-time runner and
 *       Phase config resolvers.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>Environment variables for Slack integration:</p>
 * <ul>
 *   <li>{@code SLACK_BOT_TOKEN} &mdash; Bot User OAuth Token ({@code xoxb-...}).</li>
 *   <li>{@code SLACK_APP_TOKEN} &mdash; App-level token for Socket Mode ({@code xapp-...}).</li>
 * </ul>
 *
 * @author Michael Murray
 */
package io.flowtree.slack;
