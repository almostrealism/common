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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * and a distinct per-job transcript directory, with no shared writable mount. Both
 * carry a per-launch nonce besides the task id, because a task id is not a per-job
 * identifier — a factory hands the same task id to every job it creates, and those
 * jobs may run concurrently on several Docker Nodes of the same host.</p>
 *
 * <p>The container runs the worker in place of the image's own entrypoint (which
 * starts a Server), so the in-image startup checks do not run; the per-job request
 * built here is what keeps the only writable mount the per-job transcript directory.
 * The controller's address ({@code FLOWTREE_ROOT_HOST}, {@code FLOWTREE_ROOT_PORT}) is
 * forwarded from the host Server's environment, so the job can resolve the
 * {@code 0.0.0.0} placeholder in its workstream and tool URLs the same way it does
 * in-process. That address must be reachable from inside the container.</p>
 *
 * <p>Three contracts that a Server normally supplies are met here:</p>
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
 *   <li><b>Cancellation.</b> {@link #sendKill(String, int)} additionally kills every
 *       container running the task, so a cancellation reaches a containerized agent
 *       that the base kill (which only drops queued jobs and broadcasts a message)
 *       cannot. A container is registered before it is started and a cancellation is
 *       sticky, so a kill that arrives while the runtime is still starting the
 *       container is re-applied until the container's output ends.</li>
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

	/**
	 * The classpath of the worker inside the agent image, where the image places the
	 * runtime jar and its dependencies.
	 */
	static final String WORKER_CLASSPATH = "/app/lib/*";

	/**
	 * Host environment variables forwarded into every container: the controller's
	 * address, which the job needs to resolve its workstream and tool URLs.
	 */
	static final List<String> FORWARDED_ENV = List.of("FLOWTREE_ROOT_HOST", "FLOWTREE_ROOT_PORT");

	/**
	 * Environment variable naming the host transcript root the per-job dir sits under.
	 * This is the same variable the agent compose file and start scripts already use to
	 * place transcripts, so a host Server launching containers reads the root the
	 * deployment already configures rather than one nothing sets.
	 */
	private static final String TRANSCRIPT_ROOT_ENV = "TRANSCRIPT_DIR_HOST";

	/**
	 * The host transcript root used when the environment names none: a user-writable
	 * location under the Server user's home directory. A normal non-root host process
	 * cannot create a directory directly under the filesystem root, so a bare
	 * {@code /agent-transcripts} default would make every Docker job fail before
	 * {@code docker run} unless the operator had pre-created that path. A deployment that
	 * wants a durable shared root sets {@link #TRANSCRIPT_ROOT_ENV}.
	 */
	private static final String DEFAULT_TRANSCRIPT_ROOT =
			System.getProperty("user.home", System.getProperty("java.io.tmpdir"))
					+ "/.flowtree/agent-transcripts";

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

	/** The host environment values forwarded into every container, by variable name. */
	private final Map<String, String> forwardedEnv;

	/** The outer silence-guard window in milliseconds. */
	private final long outerSilenceGuardMillis;

	/**
	 * The containers registered for each task id, so a kill can reach every container
	 * running the task, including one that is still being started.
	 */
	private final Map<String, Set<RunningContainer>> runningContainers = new ConcurrentHashMap<>();

	/**
	 * The task ids a kill has been delivered for, so a launch that registers its
	 * container after {@link #sendKill(String, int)} has already scanned the task is
	 * still marked cancelled rather than slipping through the scan and proceeding. An
	 * entry is kept for the life of the Node because a task id names one logical task
	 * for one factory, so a job dequeued later under a cancelled task id must also be
	 * cancelled.
	 */
	private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

	/**
	 * Constructs a Docker Node that drives the real {@code docker} CLI. The node id is
	 * the configuration's {@link NodeConfig#index() index}.
	 *
	 * @param parent    the owning group; {@code null} starts no worker thread.
	 * @param maxJobs   maximum queued jobs before shedding to peers.
	 * @param maxPeers  maximum peer connections.
	 * @param config    the typed configuration supplying the id, image and resource limits.
	 */
	public DockerNode(NodeGroup parent, int maxJobs, int maxPeers, NodeConfig config) {
		this(parent, maxJobs, maxPeers, config, new DockerCliContainerLauncher());
	}

	/**
	 * Constructs a Docker Node with an explicit {@link ContainerLauncher}, so a test can
	 * substitute a stub that records requests and returns canned output.
	 *
	 * @param parent    the owning group; {@code null} starts no worker thread.
	 * @param maxJobs   maximum queued jobs before shedding to peers.
	 * @param maxPeers  maximum peer connections.
	 * @param config    the typed configuration supplying the id, image and resource limits.
	 * @param launcher  the launcher that starts and cancels containers.
	 */
	DockerNode(NodeGroup parent, int maxJobs, int maxPeers, NodeConfig config,
			ContainerLauncher launcher) {
		this(parent, maxJobs, maxPeers, config, launcher, System.getenv());
	}

	/**
	 * Constructs a Docker Node with an explicit {@link ContainerLauncher} and host
	 * environment, so a test can control both the runtime and the values forwarded into
	 * each container.
	 *
	 * @param parent       the owning group; {@code null} starts no worker thread.
	 * @param maxJobs      maximum queued jobs before shedding to peers.
	 * @param maxPeers     maximum peer connections.
	 * @param config       the typed configuration supplying the id, image and resource limits.
	 * @param launcher     the launcher that starts and cancels containers.
	 * @param environment  the host environment the transcript root and forwarded
	 *                     controller address are read from.
	 */
	DockerNode(NodeGroup parent, int maxJobs, int maxPeers, NodeConfig config,
			ContainerLauncher launcher, Map<String, String> environment) {
		super(parent, config.index(), maxJobs, maxPeers);

		if (config.image() == null || config.image().isEmpty()) {
			throw new IllegalArgumentException("A docker node requires a nodes." + config.index()
					+ ".image; none was configured");
		}

		this.launcher = launcher;
		this.image = config.image();
		this.cpus = config.cpus();
		this.memory = config.memory();
		this.containerRoot = CodingAgentJobWorker.DEFAULT_CONTAINER_ROOT;
		this.transcriptRoot = resolveTranscriptRoot(environment);
		this.forwardedEnv = forwardedEnv(environment);
		this.outerSilenceGuardMillis = DEFAULT_OUTER_SILENCE_GUARD_MILLIS;
	}

	/**
	 * Runs the dequeued job in a fresh container instead of in-process. Encodes the
	 * job, starts the worker container, streams its output under the outer silence
	 * guard, forwards the job's output to its consumer, and maps a non-zero exit onto
	 * the job's failure path by throwing.
	 *
	 * <p>The job's {@link Job#getCompletableFuture() completion future} is completed
	 * once the container has finished, in a {@code finally} so it is completed whether
	 * the run succeeded or failed. This matches the native path, where
	 * {@code GitManagedJob.run()} always completes the future when the run ends and
	 * reports success or failure separately: here the separate signal is this method
	 * throwing on a non-zero exit. The container executes the decoded copy of the job,
	 * so the host-side job's own {@code run()} is never called and this is the only
	 * place its future is completed. The job is registered before the request is built,
	 * and the request is built inside the guard, so a cancellation delivered while the
	 * job is still being encoded is honoured and a failure to encode the job still
	 * completes the future rather than leaving a caller waiting forever.</p>
	 *
	 * <p>A cancellation is terminal, not a failure: a launch the task cancelled returns
	 * normally rather than throwing, so the inherited worker loop does not route it
	 * through {@code addFailedJob}. This matches the native path, where a killed job's
	 * {@code run()} returns rather than throwing, and it is what keeps a cancelled task
	 * from being requeued and re-cancelled forever when failed-job retention is enabled —
	 * the sticky {@link #cancelledTasks cancellation marker} would cancel every retry.
	 * That includes a launch, input delivery or output read that fails because the kill
	 * removed the container: a failure under cancellation is reported, not thrown.</p>
	 *
	 * <p>A failure of the job's own output consumer is not a container failure either:
	 * by then the container has finished the job and pushed its work, so retrying it
	 * would repeat completed work. It is logged rather than thrown, as the native path
	 * absorbs it inside the job's own run.</p>
	 *
	 * @param j  the job to run; never {@code null}.
	 * @throws Exception  if the container fails to run the job to completion.
	 */
	@Override
	protected void launch(Job j) throws Exception {
		String taskId = j.getTaskId();
		String launchId = launchId(taskId);
		String containerName = CONTAINER_NAME_PREFIX + launchId;

		RunningContainer container = register(taskId, containerName);
		try {
			if (container.isCancelled()) {
				displayMessage("Task " + taskId + " was cancelled before container "
						+ containerName + " started");
				return;
			}

			ContainerRequest request = buildRequest(j, containerName, launchId);

			ContainerSession session = launcher.launch(request);
			if (container.isCancelled()) launcher.kill(containerName);

			ContainerWorkerResult result = consumeOutput(session, container);
			int exit = session.awaitExit();

			if (result != null) forwardOutput(j, result);

			if (container.isCancelled()) {
				displayMessage("Container " + containerName + " for job " + taskId
						+ " was killed because the task was cancelled");
				return;
			}
			if (exit != 0) {
				throw new IOException("Container " + containerName + " for job " + taskId
						+ " exited with status " + exit);
			}
		} catch (IOException e) {
			if (!container.isCancelled()) throw e;
			displayMessage("Container " + containerName + " for job " + taskId
					+ " stopped because the task was cancelled: " + e.getMessage());
		} finally {
			unregister(taskId, container);
			j.getCompletableFuture().complete(null);
		}
	}

	/**
	 * Additionally kills every container registered for the task, so a cancellation
	 * reaches a containerized agent. The base behaviour (dropping queued jobs and
	 * broadcasting the kill) still runs first. Each container is marked cancelled
	 * before it is killed, so a container the runtime had not yet created when this
	 * kill ran is killed once it starts. The task is also recorded as cancelled before
	 * its containers are scanned, so a launch that registers concurrently — after the
	 * scan but for the same task — still sees the cancellation when it registers.
	 *
	 * @param task   the task id of the jobs to cancel.
	 * @param relay  the number of additional hops to relay the kill signal.
	 */
	@Override
	public void sendKill(String task, int relay) {
		super.sendKill(task, relay);

		cancelledTasks.add(task);

		Set<RunningContainer> containers = runningContainers.get(task);
		if (containers == null) return;

		for (RunningContainer container : containers) {
			container.cancel();
			displayMessage("Killing container " + container.name()
					+ " for cancelled task " + task);
			launcher.kill(container.name());
		}
	}

	/**
	 * Registers a container for a task before it is started, so a cancellation that
	 * arrives during startup can find it. If a kill has already been delivered for the
	 * task, the new container is marked cancelled at once, closing the race where it
	 * registers after {@link #sendKill(String, int)} has finished scanning the task.
	 *
	 * @param taskId         the task the container runs.
	 * @param containerName  the container's unique name.
	 * @return  the registered container.
	 */
	private RunningContainer register(String taskId, String containerName) {
		RunningContainer container = new RunningContainer(containerName);
		runningContainers.compute(taskId, (key, containers) -> {
			Set<RunningContainer> set = containers == null
					? ConcurrentHashMap.newKeySet() : containers;
			set.add(container);
			return set;
		});
		if (cancelledTasks.contains(taskId)) container.cancel();
		return container;
	}

	/**
	 * Removes a finished container from its task's registrations, dropping the task
	 * entry once no container runs it.
	 *
	 * @param taskId     the task the container ran.
	 * @param container  the finished container.
	 */
	private void unregister(String taskId, RunningContainer container) {
		runningContainers.computeIfPresent(taskId, (key, containers) -> {
			containers.remove(container);
			return containers.isEmpty() ? null : containers;
		});
	}

	/**
	 * Builds the request describing the container for one job: the image, resource
	 * limits, the container-local workspace root, the per-job transcript mount, the
	 * worker command, and the encoded job fed to the container's standard input.
	 *
	 * @param j              the job to run.
	 * @param containerName  the unique container name.
	 * @param launchId       the per-launch identifier naming the transcript directory.
	 * @return  the container request.
	 */
	private ContainerRequest buildRequest(Job j, String containerName, String launchId) {
		ContainerRequest.Builder builder = new ContainerRequest.Builder(containerName, image)
				.cpus(cpus)
				.memory(memory)
				.env(CodingAgentJobWorker.CONTAINER_ROOT_ENV, containerRoot);
		forwardedEnv.forEach(builder::env);
		return builder
				.transcript(transcriptRoot + "/" + launchId, TRANSCRIPT_MOUNT)
				.command(List.of("java", "--enable-native-access=ALL-UNNAMED",
						"-cp", WORKER_CLASSPATH, CodingAgentJobWorker.class.getName()))
				.encodedJob(j.toWire())
				.build();
	}

	/**
	 * Reads the container's output line by line until it ends, resetting the outer
	 * silence guard on each line, ignoring heartbeats, and capturing the worker's
	 * framed result line. The output reader is closed once consumed, so the process's
	 * output pipe is not held open across repeated launches.
	 *
	 * @param session    the running container session.
	 * @param container  the registration whose cancellation the guard re-applies.
	 * @return  the parsed worker result, or {@code null} if the worker reported none.
	 * @throws IOException  if the guard killed the container for silence, or the stream
	 *                      could not be read.
	 */
	private ContainerWorkerResult consumeOutput(ContainerSession session,
			RunningContainer container) throws IOException {
		BufferedReader reader = session.output();
		AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
		AtomicBoolean killed = new AtomicBoolean(false);
		Thread guard = startSilenceGuard(session, container, lastActivity, killed);

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
			reader.close();
		}

		if (killed.get()) {
			throw new IOException("Container " + session.name() + " emitted no output for "
					+ outerSilenceGuardMillis + "ms and was killed");
		}
		return result;
	}

	/**
	 * Starts a daemon thread that kills the container if it emits nothing — not even a
	 * worker heartbeat — for longer than the outer silence guard window, and that
	 * re-applies a cancellation on every poll so a kill issued before the runtime had
	 * created the container still takes effect.
	 *
	 * @param session       the container to watch and, if silent, kill.
	 * @param container     the registration whose cancellation is re-applied.
	 * @param lastActivity  the timestamp of the last line seen, updated by the reader.
	 * @param killed        set {@code true} when the guard kills the container.
	 * @return  the started guard thread.
	 */
	private Thread startSilenceGuard(ContainerSession session, RunningContainer container,
			AtomicLong lastActivity, AtomicBoolean killed) {
		Thread guard = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				if (container.isCancelled()) launcher.kill(session.name());

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
	 * Forwards the worker's output to the job's own output consumer as the job output
	 * the worker captured — with its prompt, session id and agent exit code — so a
	 * containerized run records the same output at the same host sink an in-process
	 * run would. A failure inside the consumer is logged and not propagated, so a host
	 * sink failure is never mistaken for a container failure and retried.
	 *
	 * @param j       the job the output belongs to.
	 * @param result  the worker's parsed result.
	 */
	private void forwardOutput(Job j, ContainerWorkerResult result) {
		Consumer<JobOutput> consumer = j.getOutputConsumer();
		if (consumer == null) return;

		try {
			consumer.accept(result.toJobOutput(j.getTaskId()));
		} catch (RuntimeException e) {
			warn("Could not deliver the output of job " + j.getTaskId()
					+ " to its output consumer", e);
		}
	}

	/**
	 * Returns the identifier of one launch: the task id, sanitized to the characters a
	 * container runtime accepts in a name, followed by a random nonce. The nonce makes
	 * the container name and transcript directory unique even when several jobs share
	 * a task id or two task ids sanitize to the same text.
	 *
	 * @param taskId  the job's task id.
	 * @return  the per-launch identifier.
	 */
	static String launchId(String taskId) {
		String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		return sanitize(taskId) + "-" + nonce;
	}

	/**
	 * Replaces every character a container runtime does not accept in a name with a
	 * dash.
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
	 * @param environment  the host environment.
	 * @return  the configured root, or {@link #DEFAULT_TRANSCRIPT_ROOT}.
	 */
	private static String resolveTranscriptRoot(Map<String, String> environment) {
		String root = environment.get(TRANSCRIPT_ROOT_ENV);
		return root == null || root.isEmpty() ? DEFAULT_TRANSCRIPT_ROOT : root;
	}

	/**
	 * Selects the {@link #FORWARDED_ENV} values that are set in the host environment.
	 *
	 * @param environment  the host environment.
	 * @return  the values to forward into every container, by variable name.
	 */
	private static Map<String, String> forwardedEnv(Map<String, String> environment) {
		Map<String, String> forwarded = new LinkedHashMap<>();
		for (String name : FORWARDED_ENV) {
			String value = environment.get(name);
			if (value != null && !value.isEmpty()) forwarded.put(name, value);
		}
		return forwarded;
	}

	/**
	 * One container registered for a task, with a sticky cancellation flag that a kill
	 * sets and the launching thread and silence guard re-check.
	 */
	private static final class RunningContainer {

		/** The container's unique name. */
		private final String name;

		/** Set once the task is cancelled; never cleared. */
		private final AtomicBoolean cancelled = new AtomicBoolean(false);

		/**
		 * @param name  the container's unique name.
		 */
		private RunningContainer(String name) {
			this.name = name;
		}

		/**
		 * Returns the container's unique name.
		 *
		 * @return  the container name.
		 */
		String name() {
			return name;
		}

		/** Marks the container's task as cancelled. */
		void cancel() {
			cancelled.set(true);
		}

		/**
		 * Returns whether the container's task has been cancelled.
		 *
		 * @return  {@code true} once {@link #cancel()} has been called.
		 */
		boolean isCancelled() {
			return cancelled.get();
		}
	}
}
