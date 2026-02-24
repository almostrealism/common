/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.stream;

import com.sun.net.httpserver.HttpExchange;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.filter.AudioProcessor;
import org.almostrealism.collect.PackedCollection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public class AudioStreamHandler implements HttpAudioHandler, CodeFeatures {
	public static boolean enableByteCache = false;
	public static double bufferDuration = 1.0;

	private final PackedCollection buffer;
	private final int totalFrames;
	private final int sampleRate;

	private final AudioProcessor processor;
	private Runnable update;

	private byte[] data;

	public AudioStreamHandler(AudioProcessor audioProcessor,
							  int totalFrames, int sampleRate) {
		this.totalFrames = totalFrames;
		this.sampleRate = sampleRate;
		this.buffer = new PackedCollection((int) (sampleRate * bufferDuration));
		this.processor = audioProcessor;
	}

	public synchronized void load() {
		if (data != null) {
			return;
		}

		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			write(out);
			out.flush();

			data = out.toByteArray();
		} catch (IOException e) {
			warn(e.getMessage());
		}
	}

	protected void write(OutputStream out) throws IOException {
		int written = 0;

		if (update == null) {
			update = processor.process(cp(buffer), null).get();
		}

		try (WavFile wav = WavFile.newWavFile(out, 2, totalFrames, 24, sampleRate)) {
			for (int pos = 0; pos < totalFrames; pos += buffer.getMemLength()) {
				update.run();

				for (int i = 0; (pos + i) < totalFrames && i < buffer.getMemLength(); i++) {
					double value = buffer.toArray(i, 1)[0];
					wav.writeFrames(new double[][]{{value}, {value}}, 1);
					written++;
				}
			}
		} finally {
			log("Wrote " + written + " frames");
			processor.reset();
		}
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "audio/wav");

		if (!Objects.equals("GET", exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(200, -1);
		} else if (enableByteCache) {
			if (data == null) {
				load();
			}

			exchange.sendResponseHeaders(200, 0);
			log("Sent headers");

			try (OutputStream out = exchange.getResponseBody()) {
				for (int i = 0; i < data.length; i += 1024) {
					out.write(data, i, Math.min(1024, data.length - i));
				}

				log("Flushing stream...");
				out.flush();
			} catch (IOException e) {
				warn(e.getMessage());
			}
		} else {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				write(out);
				out.flush();
			} catch (IOException e) {
				warn(e.getMessage());
			}
		}
	}

	@Override
	public void destroy() {
		HttpAudioHandler.super.destroy();
		data = null;
		buffer.destroy();
		if (processor instanceof Destroyable)
			((Destroyable) processor).destroy();
	}
}
