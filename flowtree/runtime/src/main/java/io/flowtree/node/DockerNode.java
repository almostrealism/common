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

import io.flowtree.job.Job;
import io.flowtree.jobs.CodingAgentJobWorker;
import io.flowtree.jobs.ContainerWorkerResult;
import org.almostrealism.io.JobOutput;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A {@link Node} that runs each job it dequeues in a fresh, isolated container rather
 * than in-process. It overrides the {@link #launch(Job)} seam to encode the job, hand
 * it to a {@link ContainerLauncher}, stream the container's output, and map the
 * container's exit status back onto the job's completion; everything else a Node does
 * — queueing, {@link #satisfies(Map) label routing}, relay, peer connections, status —
 * is inherited unchanged, so a Docker Node takes part in routing exactly as a native
 * Node does.
 *
 * <p>The container runs {@link CodingAgentJobWorker}, not a Server. One container is
 * started per job (ephemeral), so each job gets a private overlay filesystem, Maven
 * install space and transcript directory by construction, with no reset discipline to
 * get wrong between jobs.</p>
 *
 * <p>Isolation is a property of what each launch contains: a distinct container name
 * and a distinct per-job transcript directory, with no shared writable mount. Three
 * contracts that a Server normally supplies are met here:</p>
 * <ul>
 *   <li><b>Output transport.</b> The worker writes the job's output as a framed
 *       {@link ContainerWorkerResult} line; this Node parses it and forwards the output
 *       to the job's own {@link Job#getOutputConsumer() output consumer}, delivering it
 *       to the same host sink an in-process run would.</li>
 *   <li><b>Success/failure signal.</b> A non-zero container exit — the worker failing to
 *       run the job, or the container crashing or being killed — is thrown from
 *       {@code launch}, so the inherited worker loop records the job as failed via
 *       {@code addFailedJob}. A job that merely failed its own task still exits zero and
 *       reports its own status, matching native behaviour.</li>
 *   <li><b>Cancellation.</b> {@link #sendKill(String, int)} additionally kills the
 *       running task's container, so a cancellation reaches a containerized agent that
 *       the base kill (which only drops queued jobs and broadcasts a message) cannot.</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see ContainerLauncher
 * @see CodingAgentJobWorker
 * @see <a href="../docs/node-relay.md">Node Relay and Job Routing</a>
 */
public class DockerNode extends Node {

	/** The in-container mount point for the per-job transcript directory. */
	static final String TRANSCRIPT_MOUNT = "/agent-transcripts";

	/** Prefix for the per-job container name, which also carries the task id. */
	private static final String CONTAINER_NAME_PREFIX = "flowtree-job-";

	/** Environment variable naming the host transcript root the per-job dir sits under. */
	private static final String TRANSCRIPT_ROOT_ENV = "FLOWTREE_TRANSCRIPT_ROOT";

	/** The host transcript root used when the environment names none. */
	private static final String DEFAULT_TRANSCRIPT_ROOT = "/agent-transcripts";

	/**
	 * How long the container may emit nothing at all — not even a worker heartbeat —
	 * before this Node kills it. This coarse outer guard only catches a container
	 * wedged below the worker (for example a hung clone before the agent starts); the
	 * fine-grained {@code AgentInactivityMonitor} inside the job governs the agent
	 * itself, and the worker's heartbeat keeps this guard satisfied during a long,
	 * quiet tool call.
	 */
	private static final long DEFAULT_OUTER_SILENCE_GUARD_MILLIS = TimeUnit.MINUTES.toMillis(5);

	/** How often the silence guard re-checks for stalled output. */
	private static final long GUARD_POLL_MILLIS = TimeUnit.SECONDS.toMillis(15);

	/** Starts and cancels containers; the seam that lets this Node run without Docker in tests. */
	private final ContainerLauncher launcher;

	/** The container image the worker runs from; never {@code null} or empty. */
	private final String image;

	/** The CPU allotment passed to the runtime, or {@code null} to leave it unbounded. */
	private final String cpus;

	/** The memory allotment passed to the runtime, or {@code null} to leave it unbounded. */
	private final String memory;

	/** The container-local workspace root passed to the worker. */
	private final String containerRoot;

	/** The host transcript root the per-job transcript directory sits under. */
	private final String transcriptRoot;

	/** The outer silence-guard window in milliseconds. */
	private final long outerSilenceGuardMillis;

	/** The containers currently running a job, keyed by task id, so a kill can reach them. */
	private final Map<String, String> runningContainers = new ConcurrentHashMap<>();

	/**
	 * Constructs a Docker Node that drives the real {@code docker} CLI.
	 *
	 * @param parent    the owning group; {@code null} starts no worker thread.
	 * @param id        the node id within the group.
	 * @param maxJobs   maximum queued jobs before shedding to peers.
	 * @param maxPeers  maximum peer connections.
	 * @param config    the typed configuration supplying the image and resource limits.
	 */
	public DockerNode(NodeGroup parent, int id, int maxJobs, int maxPeers, NodeConfig config) {
		this(parent, id, maxJobs, maxPeers, config, new DockerCliContainerLauncher());
	}

	/**
	 * Constructs a Docker Node with an explicit {@link ContainerLauncher}, so a test can
	 * substitute a stub that records requests and returns canned output.
	 *
	 * @param parent    the owning group; {@code null} starts no worker thread.
	 * @param id        the node id within the group.
	 * @param maxJobs   maximum queued jobs before shedding to peers.
	 * @param maxPeers  maximum peer connections.
	 * @param config    the typed configuration supplying the image and resource limits.
	 * @param launcher  the launcher that starts and cancels containers.
	 */
	DockerNode(NodeGroup parent, int id, int maxJobs, int maxPeers, NodeConfig config,
			ContainerLauncher launcher) {
		super(parent, id, maxJobs, maxPeers);

		if (config.image() == null || config.image().isEmpty()) {
			throw new IllegalArgumentException("A docker node requires a nodes." + id
					+ ".image; none was configured");
		}

		this.launcher = launcher;
		this.image = config.image();
		this.cpus = config.cpus();
		this.memory = config.memory();
		this.containerRoot = CodingAgentJobWorker.DEFAULT_CONTAINER_ROOT;
		this.transcriptRoot = resolveTranscriptRoot();
		this.outerSilenceGuardMillis = DEFAULT_OUTER_SILENCE_GUARD_MILLIS;
	}

	/**
	 * Runs the dequeued job in a fresh container instead of in-process. Encodes the
	 * job, starts the worker container, streams its output under the outer silence
	 * guard, forwards the job's output to its consumer, and maps a non-zero exit onto
	 * the job's failure path by throwing.
	 *
	 * @param j  the job to run; never {@code null}.
	 * @throws Exception  if the container fails to run the job to completion.
	 */
	@Override
	protected void launch(Job j) throws Exception {
		String taskId = j.getTaskId();
		String containerName = containerName(taskId);
		ContainerRequest request = buildRequest(j, containerName, taskId);

		ContainerSession session = launcher.launch(request);
		runningContainers.put(taskId, containerName);
		try {
			ContainerWorkerResult result = consumeOutput(session);
			int exit = session.awaitExit();

			if (result != null) forwardOutput(j, result);

			if (exit != 0) {
				throw new IOException("Container " + containerName + " for job " + taskId
						+ " exited with status " + exit);
			}
		} finally {
			runningContainers.remove(taskId);
		}
	}

	/**
	 * Additionally kills the running task's container, so a cancellation reaches a
	 * containerized agent. The base behaviour (dropping queued jobs and broadcasting
	 * the kill) still runs first.
	 *
	 * @param task   the task id of the jobs to cancel.
	 * @param relay  the number of additional hops to relay the kill signal.
	 */
	@Override
	public void sendKill(String task, int relay) {
		super.sendKill(task, relay);

		String containerName = runningContainers.get(task);
		if (containerName != null) {
			displayMessage("Killing container " + containerName
					+ " for cancelled task " + task);
			launcher.kill(containerName);
		}
	}

	/**
	 * Builds the request describing the container for one job: the image, resource
	 * limits, the container-local workspace root, the per-job transcript mount, the
	 * worker command, and the encoded job fed to the container's standard input.
	 *
	 * @param j              the job to run.
	 * @param containerName  the unique container name.
	 * @param taskId         the job's task id.
	 * @return  the container request.
	 */
	private ContainerRequest buildRequest(Job j, String containerName, String taskId) {
		return new ContainerRequest.Builder(containerName, image)
				.cpus(cpus)
				.memory(memory)
				.env(CodingAgentJobWorker.CONTAINER_ROOT_ENV, containerRoot)
				.transcript(transcriptDir(taskId), TRANSCRIPT_MOUNT)
				.command(List.of("java", CodingAgentJobWorker.class.getName()))
				.encodedJob(j.toWire())
				.build();
	}

	/**
	 * Reads the container's output line by line until it ends, resetting the outer
	 * silence guard on each line, ignoring heartbeats, and capturing the worker's
	 * framed result line.
	 *
	 * @param session  the running container session.
	 * @return  the parsed worker result, or {@code null} if the worker reported none.
	 * @throws IOException  if the guard killed the container for silence, or the stream
	 *                      could not be read.
	 */
	private ContainerWorkerResult consumeOutput(ContainerSession session) throws IOException {
		BufferedReader reader = session.output();
		AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
		AtomicBoolean killed = new AtomicBoolean(false);
		Thread guard = startSilenceGuard(session, lastActivity, killed);

		ContainerWorkerResult result = null;
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				lastActivity.set(System.currentTimeMillis());
				if (ContainerWorkerResult.isHeartbeat(line)) continue;

				ContainerWorkerResult parsed = ContainerWorkerResult.parseLine(line);
				if (parsed != null) result = parsed;
			}
		} finally {
			guard.interrupt();
		}

		if (killed.get()) {
			throw new IOException("Container " + session.name() + " emitted no output for "
					+ outerSilenceGuardMillis + "ms and was killed");
		}
		return result;
	}

	/**
	 * Starts a daemon thread that kills the container if it emits nothing — not even a
	 * worker heartbeat — for longer than the outer silence guard window.
	 *
	 * @param session       the container to watch and, if silent, kill.
	 * @param lastActivity  the timestamp of the last line seen, updated by the reader.
	 * @param killed        set {@code true} when the guard kills the container.
	 * @return  the started guard thread.
	 */
	private Thread startSilenceGuard(ContainerSession session, AtomicLong lastActivity,
			AtomicBoolean killed) {
		Thread guard = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				if (System.currentTimeMillis() - lastActivity.get() > outerSilenceGuardMillis) {
					killed.set(true);
					launcher.kill(session.name());
					return;
				}
				try {
					Thread.sleep(Math.min(outerSilenceGuardMillis, GUARD_POLL_MILLIS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "DockerNode Silence Guard " + session.name());
		guard.setDaemon(true);
		guard.start();
		return guard;
	}

	/**
	 * Forwards the worker's output to the job's own output consumer as a plain
	 * {@link JobOutput}, so a containerized run records its output at the same host
	 * sink an in-process run would.
	 *
	 * @param j       the job the output belongs to.
	 * @param result  the worker's parsed result.
	 */
	private void forwardOutput(Job j, ContainerWorkerResult result) {
		Consumer<JobOutput> consumer = j.getOutputConsumer();
		if (consumer != null) {
			consumer.accept(new JobOutput(j.getTaskId(), "", "", result.output()));
		}
	}

	/**
	 * Returns the unique container name for a task id, sanitized to the characters a
	 * container runtime accepts.
	 *
	 * @param taskId  the job's task id.
	 * @return  the container name.
	 */
	private static String containerName(String taskId) {
		return CONTAINER_NAME_PREFIX + sanitize(taskId);
	}

	/**
	 * Returns the per-job host transcript directory, a distinct subdirectory of the
	 * transcript root for each task so no two containers share a writable path.
	 *
	 * @param taskId  the job's task id.
	 * @return  the per-job transcript directory.
	 */
	private String transcriptDir(String taskId) {
		return transcriptRoot + "/" + sanitize(taskId);
	}

	/**
	 * Replaces every character a container runtime does not accept in a name with a
	 * dash, so an arbitrary task id yields a valid, unique name.
	 *
	 * @param value  the raw value, possibly {@code null}.
	 * @return  the sanitized value.
	 */
	private static String sanitize(String value) {
		if (value == null || value.isEmpty()) return "unnamed";
		return value.replaceAll("[^A-Za-z0-9_.-]", "-");
	}

	/**
	 * Resolves the host transcript root from the environment.
	 *
	 * @return  the configured root, or {@link #DEFAULT_TRANSCRIPT_ROOT}.
	 */
	private static String resolveTranscriptRoot() {
		String root = System.getenv(TRANSCRIPT_ROOT_ENV);
		return root == null || root.isEmpty() ? DEFAULT_TRANSCRIPT_ROOT : root;
	}
}
