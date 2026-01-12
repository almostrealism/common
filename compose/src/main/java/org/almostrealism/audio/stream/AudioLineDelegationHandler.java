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

package org.almostrealism.audio.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.almostrealism.audio.StreamingAudioPlayer;
import org.almostrealism.audio.line.DelegatedAudioLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SharedMemoryAudioLine;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.KeyUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * HTTP handler for DAW client connections that want to receive audio via shared memory.
 *
 * <p>When a DAW client sends a POST request, this handler creates a {@link SharedMemoryAudioLine}
 * and either:</p>
 * <ul>
 *   <li>If a {@link StreamingAudioPlayer} is provided: calls {@code setDawConnection()} which
 *       stores the connection but only activates it if the player is in DAW mode. This ensures
 *       the UI widget remains the sole authority for which output is active.</li>
 *   <li>Otherwise: directly sets the delegate on the {@link DelegatedAudioLine} (legacy behavior)</li>
 * </ul>
 */
public class AudioLineDelegationHandler implements HttpAudioHandler, ConsoleFeatures {
	private final DelegatedAudioLine line;
	private final StreamingAudioPlayer playerConfig;

	/**
	 * Creates a handler for legacy (non-unified) player configurations.
	 * DAW connections will directly update the line's delegate.
	 *
	 * @param line the delegated audio line
	 */
	public AudioLineDelegationHandler(DelegatedAudioLine line) {
		this(line, null);
	}

	/**
	 * Creates a handler for unified player configurations.
	 * DAW connections will be registered via {@link StreamingAudioPlayer#setDawConnection}
	 * and only activated if the player is in DAW mode.
	 *
	 * @param line the delegated audio line
	 * @param playerConfig the unified player config (may be null for legacy behavior)
	 */
	public AudioLineDelegationHandler(DelegatedAudioLine line, StreamingAudioPlayer playerConfig) {
		this.line = line;
		this.playerConfig = playerConfig;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (Objects.equals("POST", exchange.getRequestMethod())) {
			exchange.getResponseHeaders().add("Content-Type", "application/json");

			try (OutputStream out = exchange.getResponseBody();
				 InputStream inputStream = exchange.getRequestBody()) {
				ObjectMapper objectMapper = new ObjectMapper();
				SharedPlayerConfig config = objectMapper.readValue(inputStream, SharedPlayerConfig.class);
				if (config.getStream() == null) {
					config.setStream(KeyUtils.generateKey());
				}

				// Set up the new shared memory line
				String location = config.getLocation() + "/" + config.getStream();
				log("Initializing shared memory @ " + location);

				SharedMemoryAudioLine sharedLine = new SharedMemoryAudioLine(location);

				if (playerConfig != null) {
					// Unified player: register connection but respect mode control
					// This will only activate if currently in DAW mode
					playerConfig.setDawConnection(sharedLine);
				} else {
					// Legacy behavior: directly set the delegate
					OutputLine last = line.getDelegate();
					line.setDelegate(sharedLine);
					if (last != null) last.destroy();
				}

				// Provide the configuration details to the client
				byte[] responseBytes = objectMapper.writeValueAsBytes(config);
				exchange.sendResponseHeaders(200, responseBytes.length);
				out.write(responseBytes);
			} catch (Exception e) {
				String errorMessage = "{\"error\": \"Could not update player\"}";
				exchange.sendResponseHeaders(400, errorMessage.getBytes().length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(errorMessage.getBytes());
				}

				warn("Could not update player", e);
			}
		} else {
			exchange.sendResponseHeaders(405, 0);
		}
	}
}
