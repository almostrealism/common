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

package org.almostrealism.audio.stream;

import com.sun.net.httpserver.HttpExchange;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BufferedOutputControl implements HttpAudioHandler, ConsoleFeatures {
	private final ExecutorService executor;
	private final BufferedOutputScheduler scheduler;
	private final int sampleRate;

	public BufferedOutputControl(BufferedOutputScheduler scheduler) {
		this.executor = Executors.newSingleThreadExecutor();
		this.scheduler = scheduler;
		this.sampleRate = this.scheduler.getBuffer().getDetails().getSampleRate();
		BufferDefaults.logBufferInfo(sampleRate, scheduler.getOutputLine().getBufferSize(), this::log);
	}

	public BufferedOutputScheduler getScheduler() { return scheduler; }

	protected void submitResume() {
		this.executor.submit(() -> {
			try {
				this.scheduler.resume();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			submitResume();

			exchange.sendResponseHeaders(200, 0);
			OutputStream responseBody = exchange.getResponseBody();
			responseBody.close();
		}
	}

	@Override
	public Console console() {
		return AudioScene.console;
	}
}
