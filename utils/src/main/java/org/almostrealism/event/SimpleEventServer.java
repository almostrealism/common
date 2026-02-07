/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.event;

import com.sun.net.httpserver.HttpServer;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class SimpleEventServer implements ConsoleFeatures {
	public static final String POST = "POST";

	public void start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

		server.createContext("/test", exchange -> {
			if (POST.equals(exchange.getRequestMethod())) {
				InputStream inputStream = exchange.getRequestBody();
				String requestBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
				log("Received \"" + requestBody + "\"");

				String response = "{\"status\": \"ok\"}";
				exchange.sendResponseHeaders(200, response.getBytes().length);
				OutputStream outputStream = exchange.getResponseBody();
				outputStream.write(response.getBytes());
				outputStream.close();
			} else {
				// Method Not Allowed
				exchange.sendResponseHeaders(405, -1);
			}
		});

		// Start the server
		server.setExecutor(null);
		server.start();
		System.out.println("Server started on http://localhost:8080/test");
	}
}
