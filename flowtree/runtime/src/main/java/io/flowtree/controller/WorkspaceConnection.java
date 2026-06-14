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

import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import io.flowtree.slack.SlackNotifier;

/**
 * Runtime state for a single Slack workspace connection.
 *
 * <p>In single-workspace mode only {@link FlowTreeController#defaultConnection}
 * is populated; in multi-workspace mode
 * {@link FlowTreeController#workspaceConnections} holds one entry per workspace.</p>
 *
 * @author Michael Murray
 * @see FlowTreeController
 */
public class WorkspaceConnection {

    /** Slack team ID (T...) identifying this workspace. */
    public final String workspaceId;
    /** Bot User OAuth token for posting messages (xoxb-...). */
    public final String botToken;
    /** App-level token for Socket Mode delivery (xapp-...). */
    public final String appToken;
    /** Bolt application instance for this workspace. */
    public App app;
    /** Socket Mode connection for this workspace. */
    public SocketModeApp socketModeApp;
    /** Bot's own Slack user ID; used to suppress echoed messages. */
    public String botUserId;
    /** Notifier backed by this workspace's bot token. */
    public final SlackNotifier notifier;

    /**
     * Creates a workspace connection with the given tokens.
     *
     * @param workspaceId human-readable or team-ID label
     * @param botToken    Slack bot user token (xoxb-...), or {@code null}
     * @param appToken    Slack app-level token (xapp-...), or {@code null}
     */
    public WorkspaceConnection(String workspaceId, String botToken, String appToken) {
        this.workspaceId = workspaceId;
        this.botToken = botToken;
        this.appToken = appToken;
        this.notifier = new SlackNotifier(botToken);
    }
}
