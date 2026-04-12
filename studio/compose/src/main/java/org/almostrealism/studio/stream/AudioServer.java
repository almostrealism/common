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

package org.almostrealism.studio.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.CodeFeatures;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.filter.AudioProcessor;
import org.almostrealism.io.Console;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Base64;

/**
 * Lightweight HTTP server that routes audio stream requests to registered
 * {@link HttpAudioHandler} instances keyed by channel name.
 * Handlers are stored in a frequency cache and evicted when the cache is full.
 */
public class AudioServer implements HttpHandler, CodeFeatures {
	/** Frequency-based cache of registered audio handlers, keyed by channel name. */
	private final FrequencyCache<String, HttpAudioHandler> handlers;

	/** The underlying HTTP server. */
	private final HttpServer server;

	/**
	 * Creates an audio server that will listen on the given port.
	 *
	 * @param port the TCP port to listen on
	 * @throws IOException if the server cannot bind to the port
	 */
	public AudioServer(int port) throws IOException {
		handlers = new FrequencyCache<>(100, 0.6);
		handlers.setEvictionListener((key, value) -> {
			value.destroy();
		});

		server = HttpServer.create(new InetSocketAddress(port), 20);
	}

	/**
	 * Starts the HTTP server and registers the root context handler.
	 *
	 * @throws IOException if the server cannot start
	 */
	public void start() throws IOException {
		server.createContext("/", this);
		server.start();
	}

	/** Returns the TCP port this server is listening on. */
	public int getPort() { return server.getAddress().getPort();}

	/**
	 * Registers a wave data stream under the Base64-encoded form of the given key.
	 *
	 * @param key  the logical stream identifier (will be Base64-encoded)
	 * @param data the wave data to serve
	 * @return the Base64-encoded key under which the stream was registered
	 */
	public String addStream(String key, WaveData data) {
		key = Base64.getEncoder().encodeToString(key.getBytes());

		if (containsStream(key)) {
			return key;
		}

		addStream(key, AudioProcessor.fromWave(data, 0),
				data.getFrameCount(), data.getSampleRate());
		return key;
	}

	/**
	 * Registers an audio processor as a stream handler for the given channel.
	 *
	 * @param channel     the channel name
	 * @param source      the audio processor that renders each stream request
	 * @param totalFrames total number of frames to render per request
	 * @param sampleRate  audio sample rate
	 */
	public void addStream(String channel, AudioProcessor source,
						  	int totalFrames, int sampleRate) {
		addStream(channel, new AudioStreamHandler(source, totalFrames, sampleRate));
	}

	/**
	 * Registers the given handler for the named channel.
	 *
	 * @param channel the channel name
	 * @param handler the handler to register
	 * @throws IllegalArgumentException if a handler is already registered for the channel
	 */
	public void addStream(String channel, HttpAudioHandler handler) {
		if (containsStream(channel)) {
			throw new IllegalArgumentException("Stream already exists");
		}

		handlers.put(channel, handler);
	}

	/**
	 * Returns {@code true} if a handler is registered for the given channel.
	 *
	 * @param channel the channel name to check
	 * @return {@code true} if the channel exists
	 */
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
			System.err.println("AudioServer: " + e.getMessage());
		}
	}

	@Override
	public Console console() {
		return AudioScene.console;
	}
}

