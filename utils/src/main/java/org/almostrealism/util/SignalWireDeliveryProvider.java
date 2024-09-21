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
import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

public class SignalWireDeliveryProvider implements AlertDeliveryProvider, ConsoleFeatures {

	private static SignalWireDeliveryProvider defaultProvider;

	private String space;
	private String projectId;
	private String token;
	private String fromNumber, toNumber;

	public SignalWireDeliveryProvider(String space, String projectId, String apiToken,
									  String fromNumber, String toNumber) {
		this.space = space;
		this.projectId = projectId;
		this.token = apiToken;
		this.fromNumber = fromNumber;
		this.toNumber = toNumber;
	}

	@Override
	public void sendAlert(Alert alert) {
		try {
			int responseCode = sendSms("https://" + space +
							".signalwire.com/api/laml/2010-04-01/Accounts/" +
							projectId + "/Messages",
					projectId + ":" + token,
					fromNumber, toNumber,
					alert.getMessage());

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

	public static void attachDefault() {
		attachDefault("signalwire.properties");
	}

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

			Console.root().addAlertDeliveryProvider(defaultProvider);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

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

