/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.util;

import org.almostrealism.io.Alert;
import org.almostrealism.io.AlertDeliveryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.RateLimit;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

/**
 * Alert delivery provider that sends SMS notifications via the SignalWire API.
 *
 * <p>This class implements {@link AlertDeliveryProvider} to send SMS alerts through
 * SignalWire's REST API. It includes rate limiting to prevent excessive SMS charges.</p>
 *
 * <h2>Configuration</h2>
 * <p>Configuration can be provided via constructor or loaded from a properties file:</p>
 * <pre>
 * # signalwire.properties
 * sw.space=your-space-name
 * sw.project=your-project-id
 * sw.token=your-api-token
 * sw.from_number=+15551234567
 * sw.to_number=+15559876543
 * alert.message_prefix=[MyApp]
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Attach default provider from properties file
 * SignalWireDeliveryProvider.attachDefault();
 *
 * // Or create manually
 * SignalWireDeliveryProvider provider = new SignalWireDeliveryProvider(
 *     "my-space", "project-id", "api-token", "+15551234567", "+15559876543");
 * Console.root().addAlertDeliveryProvider(provider);
 *
 * // Alerts are now sent via SMS
 * Console.root().alert(new Alert("System alert message"));
 * }</pre>
 *
 * <h2>Rate Limiting</h2>
 * <p>A maximum of 30 SMS messages can be sent per session to prevent
 * runaway alerts from incurring excessive costs.</p>
 *
 * @author Michael Murray
 * @see AlertDeliveryProvider
 */
public class SignalWireDeliveryProvider implements AlertDeliveryProvider, ConsoleFeatures {
	/**
	 * Default ceiling on SMS messages sent within {@link #limitWindow()}.
	 * Overridden by {@code alert.max_messages} in the configuration file.
	 *
	 * <p>The limit exists to stay inside the carrier's spam protections
	 * rather than to budget spend, so it is deliberately low and is shared
	 * by every provider instance: the account is what gets blocked, and the
	 * account is account-wide.</p>
	 */
	private static final int defaultMaxMessages = 30;

	/**
	 * Default width of the rate-limit window in minutes. Overridden by
	 * {@code alert.window_minutes} in the configuration file.
	 */
	private static final int defaultWindowMinutes = 60;

	/**
	 * The send budget, shared by every provider instance and sliding rather
	 * than cumulative: a long-running process that reaches the ceiling
	 * recovers as the window advances, where the per-JVM-lifetime counter
	 * this replaces went permanently silent.
	 */
	private static RateLimit sendLimit =
			new RateLimit(defaultMaxMessages, Duration.ofMinutes(defaultWindowMinutes));

	/** The singleton default provider loaded from the properties file, or {@code null} if not loaded. */
	private static SignalWireDeliveryProvider defaultProvider;

	/** The SignalWire space name (subdomain of {@code signalwire.com}). */
	private String space;

	/** The SignalWire project ID used for API authentication. */
	private String projectId;

	/** The SignalWire API token used for authentication. */
	private String token;

	/** The sender phone number in E.164 format. */
	private String fromNumber;

	/** The recipient phone number in E.164 format. */
	private String toNumber;

	/** Optional prefix prepended to all outgoing alert messages. */
	private String alertPrefix;

	/**
	 * Creates a new SignalWire delivery provider with the specified configuration.
	 *
	 * @param space      the SignalWire space name
	 * @param projectId  the SignalWire project ID
	 * @param apiToken   the SignalWire API token
	 * @param fromNumber the phone number to send from (E.164 format)
	 * @param toNumber   the phone number to send to (E.164 format)
	 */
	public SignalWireDeliveryProvider(String space, String projectId, String apiToken,
									  String fromNumber, String toNumber) {
		this.space = space;
		this.projectId = projectId;
		this.token = apiToken;
		this.fromNumber = fromNumber;
		this.toNumber = toNumber;
	}

	/**
	 * Returns the prefix prepended to all alert messages.
	 *
	 * @return the alert message prefix, or null if none is set
	 */
	public String getAlertPrefix() {
		return alertPrefix;
	}

	/**
	 * Sets a prefix to prepend to all alert messages.
	 * Useful for identifying the source application.
	 *
	 * @param alertPrefix the prefix to prepend (e.g., "[MyApp] ")
	 */
	public void setAlertPrefix(String alertPrefix) {
		this.alertPrefix = alertPrefix;
	}

	@Override
	public void sendAlert(Alert alert) {
		if (!sendLimit().reserve()) {
			warn("SMS rate limit reached (" + messageLimit() + " per "
					+ limitWindow().toMinutes() + " minutes); alert not sent");
			return;
		}

		try {
			String message = alert.getMessage();
			if (alertPrefix != null) message = alertPrefix + message;

			int responseCode = sendSms("https://" + space +
							".signalwire.com/api/laml/2010-04-01/Accounts/" +
							projectId + "/Messages",
					projectId + ":" + token,
					fromNumber, toNumber,
					message);

			if (responseCode == HttpURLConnection.HTTP_OK ||
					responseCode == HttpURLConnection.HTTP_CREATED) {
				log("Alert sent via SMS");
			} else {
				warn("Failed to send SMS (HTTP " + responseCode + ")");
			}
		} catch (Exception e) {
			warn("Failed to send SMS", e);
		}
	}

	/**
	 * Returns the account-wide send budget.
	 *
	 * <p>The budget is shared by every provider instance because the account
	 * is what a carrier blocks; a per-recipient budget would multiply the
	 * account's exposure by the number of recipients configured.</p>
	 *
	 * @return the shared rate limit
	 */
	public static synchronized RateLimit sendLimit() {
		return sendLimit;
	}

	/**
	 * Returns the ceiling on messages sent within {@link #limitWindow()}.
	 *
	 * @return the maximum number of messages per window
	 */
	public static int messageLimit() {
		return sendLimit().getMax();
	}

	/**
	 * Returns the width of the rate-limit window.
	 *
	 * @return the window duration
	 */
	public static Duration limitWindow() {
		return sendLimit().getWindow();
	}

	/**
	 * Returns the number of sends still available in the current window.
	 *
	 * @return the remaining send budget, never negative
	 */
	public static int remainingSends() {
		return sendLimit().remaining();
	}

	/**
	 * Returns a provider that reaches a different recipient through the same
	 * SignalWire account as this one.
	 *
	 * <p>The account credentials, sending number and message prefix are
	 * shared; only the destination differs. This is how a directory of named
	 * recipients is built from a single configured account, and it is why the
	 * rate-limit window is shared rather than per-instance.</p>
	 *
	 * @param recipientNumber the destination number in E.164 format
	 * @return a provider addressed to that number
	 */
	public SignalWireDeliveryProvider withRecipient(String recipientNumber) {
		SignalWireDeliveryProvider provider = new SignalWireDeliveryProvider(
				space, projectId, token, fromNumber, recipientNumber);
		provider.setAlertPrefix(alertPrefix);
		return provider;
	}

	/**
	 * Returns the provider configured by {@link #attachDefault()}, from which
	 * recipient-specific providers can be derived with
	 * {@link #withRecipient(String)}.
	 *
	 * @return the default provider, or {@code null} when no configuration
	 *         file has been loaded
	 */
	public static SignalWireDeliveryProvider getDefaultProvider() {
		return defaultProvider;
	}

	/**
	 * Attaches the default provider using {@code signalwire.properties} in the current directory.
	 * If the file doesn't exist or a provider is already attached, this method does nothing.
	 */
	public static void attachDefault() {
		attachDefault("signalwire.properties");
	}

	/**
	 * Attaches the default provider using a specified configuration file.
	 * If the file doesn't exist or a provider is already attached, this method does nothing.
	 *
	 * @param configFile the path to the properties file
	 */
	public static void attachDefault(String configFile) {
		if (defaultProvider != null) return;

		File config = new File(configFile);
		if (!config.exists()) return;

		Properties properties = new Properties();

		try {
			properties.load(config.toURI().toURL().openStream());

			defaultProvider = new SignalWireDeliveryProvider(
					properties.getProperty("sw.space"),
					properties.getProperty("sw.project"),
					properties.getProperty("sw.token"),
					properties.getProperty("sw.from_number"),
					properties.getProperty("sw.to_number"));
			defaultProvider.setAlertPrefix(properties.getProperty("alert.message_prefix"));
			configureLimit(properties);

			Console.root().addAlertDeliveryProvider(defaultProvider);
		} catch (IOException e) {
			Console.root().warn("Failed to initialize SignalWire delivery provider: " + e.getMessage(), e);
		}
	}

	/**
	 * Applies the optional rate-limit settings from a configuration file.
	 * Absent, unparseable or non-positive values leave the defaults in place,
	 * so a malformed entry cannot disable the protection it configures.
	 *
	 * @param properties the loaded configuration
	 */
	protected static synchronized void configureLimit(Properties properties) {
		sendLimit = new RateLimit(
				positiveInt(properties.getProperty("alert.max_messages"),
						defaultMaxMessages),
				Duration.ofMinutes(positiveInt(
						properties.getProperty("alert.window_minutes"),
						defaultWindowMinutes)));
	}

	/**
	 * Parses a positive integer, falling back to the given default.
	 *
	 * @param value        the configured text, or {@code null}
	 * @param defaultValue the value to use when absent or invalid
	 * @return the parsed value when positive, otherwise {@code defaultValue}
	 */
	protected static int positiveInt(String value, int defaultValue) {
		if (value == null) return defaultValue;

		try {
			int parsed = Integer.parseInt(value.trim());
			return parsed > 0 ? parsed : defaultValue;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * Sends an SMS message via HTTP POST to the specified SignalWire URL.
	 *
	 * @param url  the SignalWire API endpoint URL
	 * @param auth the authentication string in "projectId:apiToken" format
	 * @param from the sender phone number
	 * @param to   the recipient phone number
	 * @param body the message content
	 * @return the HTTP response code
	 * @throws IOException if the HTTP request fails
	 */
	protected static int sendSms(
							String url, String auth,
						    String from, String to,
						    String body) throws IOException {
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
		con.setRequestProperty("Authorization", "Basic " + encodedAuth);
		con.setRequestMethod("POST");
		con.setDoOutput(true);

		String urlParameters =
				"From=" + URLEncoder.encode(from, StandardCharsets.UTF_8) +
				"&To=" + URLEncoder.encode(to, StandardCharsets.UTF_8) +
				"&Body=" + URLEncoder.encode(body, StandardCharsets.UTF_8);

		// Send the POST request
		try (OutputStream os = con.getOutputStream()) {
			byte[] input = urlParameters.getBytes(StandardCharsets.UTF_8);
			os.write(input, 0, input.length);
		}

		return con.getResponseCode();
	}
}

