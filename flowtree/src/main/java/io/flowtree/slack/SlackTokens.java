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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * Holds Slack API tokens (bot token and app token) and provides
 * multiple resolution strategies for loading them.
 *
 * <p>Tokens can be supplied via a JSON file or environment variables.
 * The {@link #resolve(File)} method implements the following resolution
 * order (first match wins):</p>
 * <ol>
 *   <li>Explicit file path passed as argument</li>
 *   <li>{@code slack-tokens.json} in the current working directory</li>
 *   <li>{@code SLACK_BOT_TOKEN} / {@code SLACK_APP_TOKEN} environment variables</li>
 * </ol>
 *
 * <p>Expected JSON format:</p>
 * <pre>{@code
 * {
 *   "botToken": "xoxb-your-bot-token",
 *   "appToken": "xapp-your-app-token"
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see FlowTreeController
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SlackTokens {

	/** Default file name looked up in the current working directory. */
	public static final String DEFAULT_FILENAME = "slack-tokens.json";

	/** The Slack Bot User OAuth Token used for posting messages (xoxb-...). */
	private String botToken;
	/** The Slack App-level token used for Socket Mode event delivery (xapp-...). */
	private String appToken;

	/** No-arg constructor for Jackson deserialization. */
	public SlackTokens() { }

	/**
	 * Creates a new instance with the specified tokens.
	 *
	 * @param botToken the Slack Bot User OAuth Token (xoxb-...)
	 * @param appToken the Slack App-level token for Socket Mode (xapp-...)
	 */
	public SlackTokens(String botToken, String appToken) {
		this.botToken = botToken;
		this.appToken = appToken;
	}

	/** Returns the Bot User OAuth Token. */
	public String getBotToken() { return botToken; }

	/** Sets the Bot User OAuth Token. */
	public void setBotToken(String botToken) { this.botToken = botToken; }

	/** Returns the App-level token for Socket Mode. */
	public String getAppToken() { return appToken; }

	/** Sets the App-level token for Socket Mode. */
	public void setAppToken(String appToken) { this.appToken = appToken; }

	/**
	 * Creates a {@link SlackTokens} instance from a workspace configuration entry.
	 *
	 * <p>Resolution order within the entry (first match wins):</p>
	 * <ol>
	 *   <li>{@code tokensFile} — path to a JSON file containing
	 *       {@code botToken} and {@code appToken}</li>
	 *   <li>Inline {@code botToken} / {@code appToken} fields on the entry</li>
	 * </ol>
	 *
	 * @param entry the workspace configuration entry
	 * @return a {@link SlackTokens} instance
	 * @throws IOException if {@code tokensFile} is set but cannot be read
	 */
	public static SlackTokens from(WorkstreamConfig.SlackWorkspaceEntry entry) throws IOException {
		if (entry.getTokensFile() != null && !entry.getTokensFile().isEmpty()) {
			return loadFromFile(new File(entry.getTokensFile()));
		}
		return new SlackTokens(entry.getBotToken(), entry.getAppToken());
	}

	/**
	 * Loads tokens from a JSON file.
	 *
	 * @param file the JSON file to read
	 * @return the parsed tokens
	 * @throws IOException if the file cannot be read or parsed
	 */
	public static SlackTokens loadFromFile(File file) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		return mapper.readValue(file, SlackTokens.class);
	}

	/**
	 * Resolves tokens using the standard resolution order.
	 *
	 * <p>Resolution order (first match wins):</p>
	 * <ol>
	 *   <li>{@code explicitPath} argument, if non-null and the file exists</li>
	 *   <li>{@code slack-tokens.json} in the current working directory</li>
	 *   <li>{@code SLACK_BOT_TOKEN} / {@code SLACK_APP_TOKEN} environment variables</li>
	 * </ol>
	 *
	 * <p>If a JSON file is found, its values are used. Otherwise the
	 * environment variables are read (either or both may be {@code null}).</p>
	 *
	 * @param explicitPath an explicit path to a token file, or {@code null}
	 * @return a {@link SlackTokens} instance (never {@code null})
	 * @throws IOException if an explicit path is given but cannot be read
	 */
	public static SlackTokens resolve(File explicitPath) throws IOException {
		// 1. Explicit path
		if (explicitPath != null) {
			return loadFromFile(explicitPath);
		}

		// 2. Convention: slack-tokens.json in the current working directory
		File conventionFile = new File(DEFAULT_FILENAME);
		if (conventionFile.isFile()) {
			return loadFromFile(conventionFile);
		}

		// 3. Environment variables
		return new SlackTokens(
			System.getenv("SLACK_BOT_TOKEN"),
			System.getenv("SLACK_APP_TOKEN")
		);
	}
}
