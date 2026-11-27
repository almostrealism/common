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

package io.flowtree.node;

import org.almostrealism.io.ConsoleFeatures;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The production {@link ContainerLauncher}: it drives the {@code docker} command-line
 * interface, as the compose and fleet scripts already do, rather than opening the
 * Docker Engine socket. This keeps the host Server free of a client-library
 * dependency and of raw root-equivalent socket access.
 *
 * <p>{@link #launch(ContainerRequest)} runs {@code docker run --rm -i}, feeds the
 * encoded job to the container's standard input, and streams its combined output.
 * The in-container classpath is a property of the worker image (it exposes the
 * application classes to {@code java}); this launcher only names the main class to
 * run, so the image and this command are finalized together in the host-install
 * stage.</p>
 *
 * @author  Michael Murray
 * @see DockerNode
 */
public class DockerCliContainerLauncher implements ContainerLauncher, ConsoleFeatures {

	/** The {@code docker} executable name, resolved from the runtime's PATH. */
	private static final String DOCKER = "docker";

	@Override
	public ContainerSession launch(ContainerRequest request) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(DOCKER);
		command.add("run");
		command.add("--rm");
		command.add("-i");
		command.add("--name");
		command.add(request.name());

		if (request.cpus() != null && !request.cpus().isEmpty()) {
			command.add("--cpus");
			command.add(request.cpus());
		}
		if (request.memory() != null && !request.memory().isEmpty()) {
			command.add("--memory");
			command.add(request.memory());
		}

		for (Map.Entry<String, String> e : request.env().entrySet()) {
			command.add("-e");
			command.add(e.getKey() + "=" + e.getValue());
		}
		for (Map.Entry<String, String> m : request.readOnlyMounts().entrySet()) {
			command.add("-v");
			command.add(m.getKey() + ":" + m.getValue() + ":ro");
		}
		if (request.transcriptHostDir() != null) {
			command.add("-v");
			command.add(request.transcriptHostDir() + ":" + request.transcriptMountPath());
		}

		command.add(request.image());
		command.addAll(request.command());

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		Process process = builder.start();

		try (OutputStream stdin = process.getOutputStream()) {
			stdin.write(request.encodedJob().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			warn("Could not feed the encoded job to container " + request.name(), e);
		}

		return new ProcessContainerSession(request.name(), process);
	}

	@Override
	public void kill(String containerName) {
		try {
			Process process = new ProcessBuilder(DOCKER, "rm", "-f", containerName)
					.redirectErrorStream(true).start();
			process.waitFor();
		} catch (IOException e) {
			warn("Could not remove container " + containerName, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * A {@link ContainerSession} backed by the {@code docker run} {@link Process}.
	 */
	private static final class ProcessContainerSession implements ContainerSession {

		/** The container name, for cancellation. */
		private final String name;

		/** The {@code docker run} process. */
		private final Process process;

		/** The process's combined output, read line by line. */
		private final BufferedReader output;

		/**
		 * @param name     the container name, for cancellation.
		 * @param process  the running {@code docker run} process.
		 */
		private ProcessContainerSession(String name, Process process) {
			this.name = name;
			this.process = process;
			this.output = new BufferedReader(new InputStreamReader(
					process.getInputStream(), StandardCharsets.UTF_8));
		}

		@Override
		public BufferedReader output() {
			return output;
		}

		@Override
		public int awaitExit() throws InterruptedException {
			return process.waitFor();
		}

		@Override
		public String name() {
			return name;
		}
	}
}
