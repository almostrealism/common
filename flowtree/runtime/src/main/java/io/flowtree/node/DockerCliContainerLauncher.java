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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The production {@link ContainerLauncher}: it drives the {@code docker} command-line
 * interface, as the compose and fleet scripts already do, rather than opening the
 * Docker Engine socket. This keeps the host Server free of a client-library
 * dependency and of raw root-equivalent socket access.
 *
 * <p>{@link #launch(ContainerRequest)} runs {@code docker run --rm -i}, feeds the
 * encoded job to the container's standard input on a separate thread, and streams its
 * combined output.
 * The first element of {@link ContainerRequest#command()} is passed as
 * {@code --entrypoint} and the rest as its arguments, so the request's command runs
 * in place of the image's own entrypoint — the agent image's entrypoint starts a
 * Server, which a container Node must never run.</p>
 *
 * @author  Michael Murray
 * @see DockerNode
 */
public class DockerCliContainerLauncher implements ContainerLauncher, ConsoleFeatures {

	/** The {@code docker} executable name, resolved from the runtime's PATH. */
	private static final String DOCKER = "docker";

	@Override
	public ContainerSession launch(ContainerRequest request) throws IOException {
		prepareTranscriptDirectory(request);

		ProcessBuilder builder = new ProcessBuilder(runCommand(request));
		builder.redirectErrorStream(true);
		Process process = builder.start();

		CompletableFuture<Void> delivered = feedInput(request, process.getOutputStream(),
				() -> kill(request.name()));
		return new ProcessContainerSession(request.name(), process, delivered);
	}

	/**
	 * Writes the encoded job to the container's standard input on a thread of its own and
	 * then closes the stream, so {@link #launch(ContainerRequest)} returns at once and
	 * the caller's silence guard covers the input phase too. Writing synchronously would
	 * let a container that never reads its input — or a payload larger than the pipe
	 * while the runtime is busy writing pull progress nobody is yet reading — block the
	 * launch before any guard is running.
	 *
	 * <p>A failure to deliver the whole payload means the container cannot be running
	 * the job it was given, so the container is removed through {@code onFailure} and
	 * the returned future completes exceptionally; the session reports that from
	 * {@link ContainerSession#awaitExit()} rather than an exit status that could be
	 * zero.</p>
	 *
	 * @param request    the container being started, whose encoded job is written.
	 * @param stdin      the container process's standard input.
	 * @param onFailure  run when the payload could not be delivered, to remove the container.
	 * @return  a future completed once the payload is written and the stream closed.
	 */
	CompletableFuture<Void> feedInput(ContainerRequest request, OutputStream stdin,
			Runnable onFailure) {
		CompletableFuture<Void> delivered = new CompletableFuture<>();
		byte[] payload = request.encodedJob().getBytes(StandardCharsets.UTF_8);

		Thread feeder = new Thread(() -> {
			try (OutputStream out = stdin) {
				out.write(payload);
			} catch (IOException e) {
				warn("Could not feed the encoded job to container " + request.name(), e);
				onFailure.run();
				delivered.completeExceptionally(e);
				return;
			}
			delivered.complete(null);
		}, "DockerCli Input " + request.name());
		feeder.setDaemon(true);
		feeder.start();
		return delivered;
	}

	/**
	 * Returns the {@code docker run} invocation for a request: the container name,
	 * resource limits, environment, read-only mounts and the single writable transcript
	 * mount, then the request's command with its first element as the entrypoint.
	 * It is an instance method because the invocation is this launcher's translation
	 * of a runtime-neutral request into its own CLI.
	 *
	 * @param request  the container to start.
	 * @return  the full command line, starting with the {@code docker} executable.
	 */
	List<String> runCommand(ContainerRequest request) {
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

		List<String> containerCommand = request.command();
		if (!containerCommand.isEmpty()) {
			command.add("--entrypoint");
			command.add(containerCommand.get(0));
		}

		command.add(request.image());
		if (containerCommand.size() > 1) {
			command.addAll(containerCommand.subList(1, containerCommand.size()));
		}
		return command;
	}

	/**
	 * Creates the per-launch transcript directory on the host before the container is
	 * started. Docker would otherwise create the missing bind-mount source itself, as
	 * {@code root}, leaving it unwritable by the image's non-root {@code agent} user and
	 * losing the transcript. Creating it here — as the host Server's own user, matching
	 * what the agent start scripts do — keeps it writable by the worker.
	 *
	 * @param request  the container to start.
	 * @throws IOException  if the directory cannot be created.
	 */
	void prepareTranscriptDirectory(ContainerRequest request) throws IOException {
		if (request.transcriptHostDir() != null) {
			Files.createDirectories(Path.of(request.transcriptHostDir()));
		}
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

		/** Completed once the encoded job has been written to the container's input. */
		private final CompletableFuture<Void> delivered;

		/**
		 * @param name       the container name, for cancellation.
		 * @param process    the running {@code docker run} process.
		 * @param delivered  completed once the encoded job reached the container's input.
		 */
		private ProcessContainerSession(String name, Process process,
				CompletableFuture<Void> delivered) {
			this.name = name;
			this.process = process;
			this.delivered = delivered;
			this.output = new BufferedReader(new InputStreamReader(
					process.getInputStream(), StandardCharsets.UTF_8));
		}

		@Override
		public BufferedReader output() {
			return output;
		}

		@Override
		public int awaitExit() throws InterruptedException, IOException {
			int exit = process.waitFor();
			try {
				delivered.get();
			} catch (ExecutionException e) {
				throw new IOException("The encoded job was not delivered to container "
						+ name, e.getCause());
			}
			return exit;
		}

		@Override
		public String name() {
			return name;
		}
	}
}
