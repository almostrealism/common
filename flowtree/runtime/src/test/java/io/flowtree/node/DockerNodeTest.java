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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Exercises {@link DockerNode} with a stub {@link ContainerLauncher}, so the Node's
 * contracts are proven without a real container runtime: the dequeued job is encoded
 * once and handed to the launcher, a per-job transcript directory keeps containers
 * isolated, a non-zero container exit is mapped onto the job's failure path, the
 * worker's output is forwarded to the job's own consumer, and a cancellation kills the
 * running task's container.
 *
 * @author  Michael Murray
 */
public class DockerNodeTest extends TestSuiteBase {

	/** A configuration entry for a Docker Node carrying an image and resource limits. */
	private static NodeConfig dockerConfig() {
		return new NodeConfig(0, NodeKind.DOCKER, Map.of("platform", "linux"),
				"flowtree/agent:test", "4", "4g");
	}

	/** The job encoded for the launcher matches the dequeued job, exactly once. */
	@Test(timeout = 10000)
	public void encodesJobOnceIntoTheRequest() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 0, 4, 4, dockerConfig(), launcher);
		StubJob job = new StubJob("task-encode");

		node.launch(job);

		Assert.assertEquals("the job is launched exactly once", 1, launcher.requests.size());
		ContainerRequest request = launcher.requests.get(0);
		Assert.assertEquals("the request carries the encoded job", job.toWire(),
				request.encodedJob());
		Assert.assertEquals("the worker image comes from the config", "flowtree/agent:test",
				request.image());
		Assert.assertTrue("the container runs the worker main",
				request.command().contains(CodingAgentJobWorker.class.getName()));
		Assert.assertEquals("the container-local root is passed to the worker",
				CodingAgentJobWorker.DEFAULT_CONTAINER_ROOT,
				request.env().get(CodingAgentJobWorker.CONTAINER_ROOT_ENV));
	}

	/** Each job gets a distinct container name and a distinct writable transcript dir. */
	@Test(timeout = 10000)
	public void eachJobGetsAnIsolatedTranscriptDirectory() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 0, 4, 4, dockerConfig(), launcher);

		node.launch(new StubJob("task-a"));
		node.launch(new StubJob("task-b"));

		ContainerRequest a = launcher.requests.get(0);
		ContainerRequest b = launcher.requests.get(1);

		Assert.assertNotEquals("distinct container names", a.name(), b.name());
		Assert.assertNotEquals("distinct transcript directories",
				a.transcriptHostDir(), b.transcriptHostDir());
		Assert.assertEquals("transcripts mount at the sanctioned path",
				DockerNode.TRANSCRIPT_MOUNT, a.transcriptMountPath());
		Assert.assertTrue("no shared writable mount beyond the transcript directory",
				a.readOnlyMounts().isEmpty());
	}

	/** A non-zero container exit is thrown, so the worker loop records the job as failed. */
	@Test(timeout = 10000)
	public void nonZeroContainerExitFailsTheJob() {
		StubLauncher launcher = new StubLauncher(StubSession.completing(3, null));
		DockerNode node = new DockerNode(null, 0, 4, 4, dockerConfig(), launcher);

		try {
			node.launch(new StubJob("task-fail"));
			Assert.fail("a non-zero container exit must propagate as a failure");
		} catch (Exception expected) {
			Assert.assertTrue("the failure names the exit status",
					expected.getMessage().contains("3"));
		}
	}

	/** A zero exit does not fail the job, and the worker output reaches the job's consumer. */
	@Test(timeout = 10000)
	public void forwardsWorkerOutputToTheJobConsumer() throws Exception {
		ContainerWorkerResult result = new ContainerWorkerResult(0, "session-1", "the agent output");
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, result.encodeLine()));
		DockerNode node = new DockerNode(null, 0, 4, 4, dockerConfig(), launcher);

		StubJob job = new StubJob("task-ok");
		AtomicReference<JobOutput> received = new AtomicReference<>();
		job.setOutputConsumer(received::set);

		node.launch(job);

		Assert.assertNotNull("the job's output consumer is fired", received.get());
		Assert.assertEquals("the forwarded output carries the agent text",
				"the agent output", received.get().getOutput());
		Assert.assertEquals("the forwarded output is tagged with the task id",
				"task-ok", received.get().getTaskId());
	}

	/** Cancelling a task kills the container that is still running it. */
	@Test(timeout = 10000)
	public void sendKillKillsTheRunningContainer() throws Exception {
		StubSession session = StubSession.blockingExit(0, null);
		StubLauncher launcher = new StubLauncher(session);
		DockerNode node = new DockerNode(null, 0, 4, 4, dockerConfig(), launcher);
		StubJob job = new StubJob("task-cancel");

		Thread runner = new Thread(() -> {
			try {
				node.launch(job);
			} catch (Exception e) {
				// The launch completes normally once the exit gate is released.
			}
		});
		runner.start();

		Assert.assertTrue("the launch reaches the container wait",
				session.reachedExit.await(5, TimeUnit.SECONDS));

		node.sendKill("task-cancel", 0);

		Assert.assertTrue("the running task's container is killed",
				launcher.killed.contains(session.name()));

		session.exitGate.countDown();
		runner.join(5000);
	}

	/** A missing image is reported at construction rather than failing opaquely later. */
	@Test(timeout = 10000)
	public void requiresAnImage() {
		NodeConfig noImage = new NodeConfig(0, NodeKind.DOCKER, Map.of(), null, null, null);
		try {
			new DockerNode(null, 0, 4, 4, noImage, new StubLauncher(StubSession.completing(0, null)));
			Assert.fail("a docker node without an image must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue("the message names the missing image key",
					expected.getMessage().contains("image"));
		}
	}

	// ── Stubs ────────────────────────────────────────────────────────────────

	/**
	 * A {@link ContainerLauncher} that records each request and kill, and returns a
	 * configured {@link StubSession} without touching any real runtime.
	 */
	private static final class StubLauncher implements ContainerLauncher {

		/** The requests passed to {@link #launch(ContainerRequest)}, in order. */
		private final List<ContainerRequest> requests = new ArrayList<>();

		/** The container names passed to {@link #kill(String)}. */
		private final List<String> killed = Collections.synchronizedList(new ArrayList<>());

		/** The session returned for every launch. */
		private final StubSession session;

		/**
		 * @param session  the session every launch returns.
		 */
		private StubLauncher(StubSession session) {
			this.session = session;
		}

		@Override
		public ContainerSession launch(ContainerRequest request) {
			requests.add(request);
			return session.named(request.name());
		}

		@Override
		public void kill(String containerName) {
			killed.add(containerName);
		}
	}

	/**
	 * A {@link ContainerSession} over a canned output string and a configured exit code,
	 * optionally blocking the exit so a cancellation can reach it mid-run.
	 */
	private static final class StubSession implements ContainerSession {

		/** The canned combined output the container "emitted". */
		private final String output;

		/** The exit status returned by {@link #awaitExit()}. */
		private final int exitCode;

		/** Released to let {@link #awaitExit()} return; pre-released unless blocking. */
		private final CountDownLatch exitGate;

		/** Counted down when {@link #awaitExit()} is entered, so a test can synchronize. */
		private final CountDownLatch reachedExit = new CountDownLatch(1);

		/** The container name, assigned when the launcher starts this session. */
		private String name = "unnamed";

		/**
		 * @param output    the canned output, or {@code null} for none.
		 * @param exitCode  the exit status to report.
		 * @param exitGate  the gate controlling when {@link #awaitExit()} returns.
		 */
		private StubSession(String output, int exitCode, CountDownLatch exitGate) {
			this.output = output == null ? "" : output;
			this.exitCode = exitCode;
			this.exitGate = exitGate;
		}

		/**
		 * @param exitCode  the exit status to report.
		 * @param output    the canned output line, or {@code null} for none.
		 * @return  a session whose exit returns immediately.
		 */
		private static StubSession completing(int exitCode, String output) {
			CountDownLatch open = new CountDownLatch(0);
			return new StubSession(output, exitCode, open);
		}

		/**
		 * @param exitCode  the exit status to report once released.
		 * @param output    the canned output line, or {@code null} for none.
		 * @return  a session whose exit blocks until its gate is counted down.
		 */
		private static StubSession blockingExit(int exitCode, String output) {
			return new StubSession(output, exitCode, new CountDownLatch(1));
		}

		/**
		 * @param name  the container name assigned by the launcher.
		 * @return  this session.
		 */
		private StubSession named(String name) {
			this.name = name;
			return this;
		}

		@Override
		public BufferedReader output() {
			return new BufferedReader(new StringReader(output));
		}

		@Override
		public int awaitExit() throws InterruptedException {
			reachedExit.countDown();
			exitGate.await();
			return exitCode;
		}

		@Override
		public String name() {
			return name;
		}
	}

	/**
	 * A minimal {@link Job} that records an output consumer, so the Node's forwarding of
	 * container output can be observed.
	 */
	private static final class StubJob implements Job {

		/** The task identifier. */
		private final String taskId;

		/** Completed by {@link #run()}. */
		private final CompletableFuture<Void> future = new CompletableFuture<>();

		/** The registered output consumer, or {@code null}. */
		private Consumer<JobOutput> outputConsumer;

		/**
		 * @param taskId  the task identifier.
		 */
		private StubJob(String taskId) {
			this.taskId = taskId;
		}

		@Override
		public void run() {
			future.complete(null);
		}

		@Override
		public void set(String key, String value) {
		}

		@Override
		public String encode() {
			return StubJob.class.getName() + "::id:=" + taskId;
		}

		@Override
		public String getTaskId() {
			return taskId;
		}

		@Override
		public String getTaskString() {
			return "StubJob[" + taskId + "]";
		}

		@Override
		public Map<String, String> getRequiredLabels() {
			return Collections.emptyMap();
		}

		@Override
		public void setOutputConsumer(Consumer<JobOutput> outputConsumer) {
			this.outputConsumer = outputConsumer;
		}

		@Override
		public Consumer<JobOutput> getOutputConsumer() {
			return outputConsumer;
		}

		@Override
		public CompletableFuture<Void> getCompletableFuture() {
			return future;
		}
	}
}
