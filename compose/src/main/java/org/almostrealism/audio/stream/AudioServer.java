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

package org.almostrealism.audio.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.filter.AudioProcessor;
import org.almostrealism.io.Console;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Base64;

public class AudioServer implements HttpHandler, CodeFeatures {
	private final FrequencyCache<String, HttpAudioHandler> handlers;

	private final HttpServer server;

	public AudioServer(int port) throws IOException {
		handlers = new FrequencyCache<>(100, 0.6);
		handlers.setEvictionListener((key, value) -> {
			value.destroy();
		});

		server = HttpServer.create(new InetSocketAddress(port), 20);
	}

	public void start() throws IOException {
		server.createContext("/", this);
		server.start();
	}

	public int getPort() { return server.getAddress().getPort();}

	public String addStream(String key, WaveData data) {
		key = Base64.getEncoder().encodeToString(key.getBytes());

		if (containsStream(key)) {
			return key;
		}

		addStream(key, AudioProcessor.fromWave(data, 0),
				data.getFrameCount(), data.getSampleRate());
		return key;
	}

	public void addStream(String channel, AudioProcessor source,
						  	int totalFrames, int sampleRate) {
		addStream(channel, new AudioStreamHandler(source, totalFrames, sampleRate));
	}

	public void addStream(String channel, HttpAudioHandler handler) {
		if (containsStream(channel)) {
			throw new IllegalArgumentException("Stream already exists");
		}

		handlers.put(channel, handler);
	}

	public boolean containsStream(String channel) {
		return handlers.containsKey(channel);
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			String path = exchange.getRequestURI().getPath();
			String channel = path.substring(1);
			HttpHandler handler = handlers.get(channel);
			if (handler == null) {
				exchange.sendResponseHeaders(404, 0);
				exchange.getResponseBody().close();
				return;
			}

			handler.handle(exchange);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public Console console() {
		return AudioScene.console;
	}
}

