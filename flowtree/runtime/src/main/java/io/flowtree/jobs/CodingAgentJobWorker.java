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

package io.flowtree.jobs;

import io.flowtree.job.Job;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The entry point that runs exactly one FlowTree {@link Job} inside a container, for
 * a {@code DockerNode} on the host. It is deliberately <em>not</em> a Server: it has
 * no {@code NodeGroup}, no peer connections, and no relay. It reads one encoded job
 * from its standard input, decodes it with the same codec path a native Node uses,
 * runs it, and reports the outcome back over its standard output and its process
 * exit status.
 *
 * <p>Three contracts hold here that a native Node gets from the Server it runs in,
 * and that this worker must supply itself:</p>
 * <ul>
 *   <li><b>Output transport.</b> A native Node wires the job to
 *       {@code OutputServer.getCurrentServer()}; with no Server that singleton is
 *       {@code null}. The worker installs its own {@link Consumer} instead, captures
 *       the job's output, and writes it as a single framed {@link ContainerWorkerResult}
 *       line to standard output for the host Node to forward. A job that emits no
 *       output produces no result line, so the host forwards nothing, exactly as a
 *       native run would fire no output event.</li>
 *   <li><b>Success/failure signal.</b> {@link GitManagedJob#run()} is {@code final},
 *       catches execution exceptions, and always completes its future, so the worker
 *       cannot infer the job's outcome from {@code run()} returning — and does not
 *       try to. Exactly as on a native Node (whose worker loop only treats a thrown
 *       exception as a failure), the job reports its own success or failure through
 *       its completion event; the worker's <em>process</em> exit status reflects only
 *       whether the worker itself could run the job to completion, which is what the
 *       host Node maps onto the infrastructure-failure retry path.</li>
 *   <li><b>Working-directory remap.</b> A serialized job may carry a host-absolute
 *       {@code workingDirectory} that does not exist in the container. The worker
 *       sets {@link GitManagedJob#WORKING_DIRECTORY_PROPERTY} to the container-local
 *       root, which {@code run()} already uses to null any such path and resolve every
 *       checkout under that root.</li>
 * </ul>
 *
 * <p>While the job runs, a heartbeat thread emits {@link ContainerWorkerResult#HEARTBEAT_LINE}
 * so the host's coarse outer silence guard sees the worker is alive even during a long,
 * quiet tool call; the fine-grained {@code AgentInactivityMonitor} inside the job's own
 * {@code run()} still governs the agent subprocess.</p>
 *
 * @author  Michael Murray
 * @see GitManagedJob
 * @see ContainerWorkerResult
 */
public final class CodingAgentJobWorker {

	/** Exit status when the worker ran the job's {@code run()} to completion. */
	static final int EXIT_COMPLETED = 0;

	/** Exit status when the worker could not decode or run the job at all. */
	static final int EXIT_WORKER_FAILURE = 70;

	/** Environment variable naming the container-local workspace root. */
	public static final String CONTAINER_ROOT_ENV = "FLOWTREE_WORKING_DIR";

	/** The container-local workspace root used when the environment names none. */
	public static final String DEFAULT_CONTAINER_ROOT = "/workspace/project";

	/** How often the heartbeat thread emits a liveness line. */
	private static final long HEARTBEAT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(30);

	/** Not instantiable; this class is only an entry point. */
	private CodingAgentJobWorker() {
	}

	/**
	 * Reads the encoded job from standard input, runs it, and exits with the worker's
	 * process status. Standard output is the worker's only transport back to the host
	 * Node, so it is taken as a value and written through directly, not routed through
	 * the logging framework (which would mangle the framed protocol lines).
	 *
	 * @param args  ignored.
	 */
	public static void main(String[] args) {
		PrintStream out = System.out;
		int status = execute(System.in, out);
		out.flush();
		System.exit(status);
	}

	/**
	 * Decodes and runs one job, writing the framed result line to {@code out}. The
	 * input is decoded exactly as read: the host writes the wire form with no
	 * delimiter, and the string wire format allows values that end in whitespace, so
	 * trimming would change the job the container runs.
	 *
	 * @param in   the stream carrying the encoded job.
	 * @param out  the stream carrying heartbeats and the framed result line.
	 * @return  {@link #EXIT_COMPLETED} if the job ran to completion, or
	 *          {@link #EXIT_WORKER_FAILURE} if the worker could not run it.
	 */
	static int execute(InputStream in, PrintStream out) {
		String wire;
		try {
			wire = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			out.println("CodingAgentJobWorker: could not read the encoded job: " + e.getMessage());
			return EXIT_WORKER_FAILURE;
		}

		Job job = Job.decode(wire);
		if (job == null) {
			out.println("CodingAgentJobWorker: could not decode the job from its wire form");
			return EXIT_WORKER_FAILURE;
		}

		System.setProperty(GitManagedJob.WORKING_DIRECTORY_PROPERTY, containerRoot());

		AtomicReference<CodingAgentJobOutput> captured = new AtomicReference<>();
		job.setOutputConsumer(output -> {
			if (output instanceof CodingAgentJobOutput) {
				captured.set((CodingAgentJobOutput) output);
			}
		});

		Thread heartbeat = startHeartbeat(out);
		ExecutorService pool = Executors.newCachedThreadPool();
		try {
			job.setExecutorService(pool);
			job.run();
			job.getCompletableFuture().get();
		} catch (Exception e) {
			out.println("CodingAgentJobWorker: the job did not run to completion: " + e.getMessage());
			return EXIT_WORKER_FAILURE;
		} finally {
			heartbeat.interrupt();
			pool.shutdownNow();
		}

		CodingAgentJobOutput output = captured.get();
		if (output != null) out.println(ContainerWorkerResult.of(output).encodeLine());
		return EXIT_COMPLETED;
	}

	/**
	 * Starts a daemon thread that emits a heartbeat line at a fixed interval until
	 * interrupted, proving the worker is alive independently of agent output.
	 *
	 * @param out  the stream the heartbeat is written to.
	 * @return  the started thread.
	 */
	private static Thread startHeartbeat(PrintStream out) {
		Thread thread = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				out.println(ContainerWorkerResult.HEARTBEAT_LINE);
				try {
					Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}, "CodingAgentJobWorker Heartbeat");
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/**
	 * Resolves the container-local workspace root from the environment.
	 *
	 * @return  the configured root, or {@link #DEFAULT_CONTAINER_ROOT}.
	 */
	private static String containerRoot() {
		String root = System.getenv(CONTAINER_ROOT_ENV);
		return root == null || root.isEmpty() ? DEFAULT_CONTAINER_ROOT : root;
	}
}
