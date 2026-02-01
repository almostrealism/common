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
 * Slack integration for the Flowtree Claude Code agent system.
 *
 * <p>This package provides components for controlling Claude Code agents via Slack:</p>
 * <ul>
 *   <li>{@link io.flowtree.slack.SlackBotController} - Main entry point and lifecycle manager</li>
 *   <li>{@link io.flowtree.slack.SlackListener} - Parses Slack messages and creates jobs</li>
 *   <li>{@link io.flowtree.slack.SlackNotifier} - Posts status updates to Slack channels</li>
 *   <li>{@link io.flowtree.slack.SlackWorkstream} - Configuration for channel-to-agent mapping</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Slack Channel          SlackBotController           ClaudeCodeAgent
 *       |                       |                           |
 *       | @agent "Fix bug"      |                           |
 *       |----------------------&gt;|                           |
 *       |                       |   SlackListener           |
 *       |                       |--------------&gt;            |
 *       |                       |   creates job             |
 *       |                       |                    +------+
 *       |                       |                    | job  |
 *       |                       |                    | runs |
 *       |                       |                    +------+
 *       |                       |   SlackNotifier           |
 *       |                       |&lt;--------------            |
 *       |  "Work complete"      |   job completed           |
 *       |&lt;----------------------|                           |
 * </pre>
 *
 * <h2>Configuration</h2>
 * <p>Required environment variables:</p>
 * <ul>
 *   <li>{@code SLACK_BOT_TOKEN} - Bot User OAuth Token (xoxb-...)</li>
 *   <li>{@code SLACK_APP_TOKEN} - App-level token for Socket Mode (xapp-...)</li>
 * </ul>
 *
 * <h2>Future: MCP Memory Integration</h2>
 * <p>The workstream ID ({@link io.flowtree.slack.SlackWorkstream#getWorkstreamId()})
 * is designed to serve as a memory namespace for future MCP tool integration,
 * allowing both agents and operators to share persistent context.</p>
 *
 * @author Michael Murray
 */
package io.flowtree.slack;
