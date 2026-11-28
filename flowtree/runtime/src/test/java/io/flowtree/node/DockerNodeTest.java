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
import io.flowtree.jobs.CodingAgentJobOutput;
import io.flowtree.jobs.CodingAgentJobWorker;
import io.flowtree.jobs.ContainerWorkerResult;
import org.almostrealism.io.JobOutput;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);
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
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

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
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

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
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

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
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);
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

	/** Two concurrent jobs sharing one task id get distinct containers and transcripts. */
	@Test(timeout = 10000)
	public void jobsSharingATaskIdGetDistinctContainers() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		node.launch(new StubJob("task-shared"));
		node.launch(new StubJob("task-shared"));

		ContainerRequest first = launcher.requests.get(0);
		ContainerRequest second = launcher.requests.get(1);
		Assert.assertNotEquals("a shared task id must not share a container name",
				first.name(), second.name());
		Assert.assertNotEquals("a shared task id must not share a transcript directory",
				first.transcriptHostDir(), second.transcriptHostDir());
	}

	/** Task ids that sanitize to the same text still get distinct containers. */
	@Test(timeout = 10000)
	public void sanitizationCollisionsGetDistinctContainers() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		node.launch(new StubJob("a/b"));
		node.launch(new StubJob("a?b"));

		ContainerRequest first = launcher.requests.get(0);
		ContainerRequest second = launcher.requests.get(1);
		Assert.assertNotEquals("distinct container names", first.name(), second.name());
		Assert.assertNotEquals("distinct transcript directories",
				first.transcriptHostDir(), second.transcriptHostDir());
	}

	/**
	 * A cancellation that arrives while the container is starting still kills it, and the
	 * launch returns normally rather than throwing — cancellation is terminal, not a
	 * retryable failure.
	 */
	@Test(timeout = 10000)
	public void sendKillDuringStartupKillsTheContainer() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);
		launcher.onLaunch = () -> node.sendKill("task-starting", 0);

		StubJob job = new StubJob("task-starting");
		node.launch(job);

		Assert.assertTrue("a cancelled launch completes its future rather than hanging",
				job.getCompletableFuture().isDone());

		String name = launcher.requests.get(0).name();
		Assert.assertTrue("the container is killed again once it has started",
				launcher.killed.stream().filter(name::equals).count() >= 2);
	}

	/** The container runs the worker in place of the image entrypoint. */
	@Test(timeout = 10000)
	public void containerCommandReplacesTheImageEntrypoint() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);
		node.launch(new StubJob("task-entrypoint"));

		List<String> run = new DockerCliContainerLauncher().runCommand(launcher.requests.get(0));
		int entrypoint = run.indexOf("--entrypoint");
		int image = run.indexOf("flowtree/agent:test");

		Assert.assertTrue("the image entrypoint is overridden", entrypoint >= 0);
		Assert.assertEquals("the entrypoint is the java launcher", "java", run.get(entrypoint + 1));
		Assert.assertTrue("the entrypoint override precedes the image", entrypoint < image);
		Assert.assertEquals("the worker main is the last argument",
				CodingAgentJobWorker.class.getName(), run.get(run.size() - 1));
		Assert.assertTrue("the worker classpath is the image's library directory",
				run.subList(image, run.size()).contains(DockerNode.WORKER_CLASSPATH));
	}

	/** The controller's address is forwarded from the host environment into the container. */
	@Test(timeout = 10000)
	public void forwardsTheControllerAddress() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		Map<String, String> environment = Map.of("FLOWTREE_ROOT_HOST", "controller.example",
				"FLOWTREE_ROOT_PORT", "7766", "UNRELATED_SECRET", "do-not-forward");
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher, environment);

		node.launch(new StubJob("task-env"));

		Map<String, String> env = launcher.requests.get(0).env();
		Assert.assertEquals("controller.example", env.get("FLOWTREE_ROOT_HOST"));
		Assert.assertEquals("7766", env.get("FLOWTREE_ROOT_PORT"));
		Assert.assertFalse("only the controller address is forwarded",
				env.containsKey("UNRELATED_SECRET"));
	}

	/** The forwarded output keeps the coding-agent metadata the worker captured. */
	@Test(timeout = 10000)
	public void forwardedOutputKeepsCodingAgentMetadata() throws Exception {
		ContainerWorkerResult result = new ContainerWorkerResult(2, "session-9",
				"the prompt", "the agent output");
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, result.encodeLine()));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		StubJob job = new StubJob("task-meta");
		AtomicReference<JobOutput> received = new AtomicReference<>();
		job.setOutputConsumer(received::set);
		node.launch(job);

		Assert.assertTrue("the forwarded output is a coding-agent output",
				received.get() instanceof CodingAgentJobOutput);
		CodingAgentJobOutput output = (CodingAgentJobOutput) received.get();
		Assert.assertEquals("task-meta", output.getTaskId());
		Assert.assertEquals("the prompt", output.getPrompt());
		Assert.assertEquals("session-9", output.getSessionId());
		Assert.assertEquals(2, output.getExitCode());
		Assert.assertEquals("the agent output", output.getOutput());
	}

	/** A successful container run completes the host-side job's completion future. */
	@Test(timeout = 10000)
	public void completesTheJobFutureOnSuccess() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		StubJob job = new StubJob("task-future-ok");
		node.launch(job);

		Assert.assertTrue("the host-side job future is completed after a successful run",
				job.getCompletableFuture().isDone());
		Assert.assertFalse("the future completes normally, not exceptionally",
				job.getCompletableFuture().isCompletedExceptionally());
	}

	/** A failed container run still completes the host-side job's completion future. */
	@Test(timeout = 10000)
	public void completesTheJobFutureOnFailure() {
		StubLauncher launcher = new StubLauncher(StubSession.completing(5, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		StubJob job = new StubJob("task-future-fail");
		try {
			node.launch(job);
			Assert.fail("a non-zero container exit must propagate as a failure");
		} catch (Exception expected) {
			// The failure is signalled by the throw; the future is still completed below.
		}

		Assert.assertTrue("the host-side job future is completed even after a failed run",
				job.getCompletableFuture().isDone());
	}

	/** A job that cannot be encoded still completes its future and never reaches the launcher. */
	@Test(timeout = 10000)
	public void completesTheJobFutureWhenEncodingFails() {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		StubJob job = new StubJob("task-encode-fail")
				.failingToEncode(new IllegalStateException("cannot encode"));
		try {
			node.launch(job);
			Assert.fail("an encoding failure must propagate");
		} catch (Exception expected) {
			// The failure propagates; the future is still completed below.
		}

		Assert.assertTrue("the host-side job future is completed even when encoding fails",
				job.getCompletableFuture().isDone());
		Assert.assertTrue("no container is started when the job cannot be encoded",
				launcher.requests.isEmpty());
	}

	/**
	 * A launch registered after a kill for its task has been delivered is cancelled: it
	 * starts no container and returns normally, so the inherited worker loop does not
	 * retry it.
	 */
	@Test(timeout = 10000)
	public void aLaunchRegisteredAfterAKillIsCancelled() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		node.sendKill("task-late", 0);

		StubJob job = new StubJob("task-late");
		node.launch(job);

		Assert.assertTrue("the cancelled launch never starts a container",
				launcher.requests.isEmpty());
		Assert.assertTrue("a cancelled launch completes its future rather than hanging",
				job.getCompletableFuture().isDone());
		Assert.assertFalse("a cancelled launch completes normally, not exceptionally",
				job.getCompletableFuture().isCompletedExceptionally());
	}

	/** The container's output reader is closed once its output has been consumed. */
	@Test(timeout = 10000)
	public void closesTheContainerOutputReader() throws Exception {
		StubSession session = StubSession.completing(0, null);
		StubLauncher launcher = new StubLauncher(session);
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);

		node.launch(new StubJob("task-reader"));

		Assert.assertTrue("the container output reader is closed after it is consumed",
				session.outputClosed.get());
	}

	/** The host transcript root is read from the deployment's transcript variable. */
	@Test(timeout = 10000)
	public void resolvesTranscriptRootFromDeploymentVariable() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		Map<String, String> environment = Map.of("TRANSCRIPT_DIR_HOST", "/host/transcripts");
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher, environment);

		node.launch(new StubJob("task-transcript"));

		Assert.assertTrue("the per-job transcript dir sits under the configured root",
				launcher.requests.get(0).transcriptHostDir().startsWith("/host/transcripts/"));
	}

	/** The real launcher pre-creates the per-launch transcript directory before docker runs. */
	@Test(timeout = 10000)
	public void launcherCreatesTheTranscriptDirectory() throws Exception {
		Path root = Files.createTempDirectory("docker-node-transcript");
		Path transcript = root.resolve("task-launch-nonce");
		Assert.assertFalse("the transcript directory does not exist yet",
				Files.exists(transcript));

		ContainerRequest request = new ContainerRequest.Builder("flowtree-job-x", "flowtree/agent:test")
				.transcript(transcript.toString(), DockerNode.TRANSCRIPT_MOUNT)
				.command(List.of("java", CodingAgentJobWorker.class.getName()))
				.encodedJob("payload")
				.build();

		new DockerCliContainerLauncher().prepareTranscriptDirectory(request);

		Assert.assertTrue("the launcher creates the transcript directory before mounting it",
				Files.isDirectory(transcript));
	}

	/**
	 * A cancelled task returns normally even though its killed container exits non-zero,
	 * so the inherited worker loop never routes it through {@code addFailedJob}. Were the
	 * cancellation thrown as a failure, failed-job retention would requeue the sticky-
	 * cancelled task and cancel it again on every retry, forever.
	 */
	@Test(timeout = 10000)
	public void cancellationIsTerminalRatherThanAFailure() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(137, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher);
		launcher.onLaunch = () -> node.sendKill("task-terminal", 0);

		StubJob job = new StubJob("task-terminal");
		node.launch(job);

		Assert.assertTrue("a cancelled launch completes its future",
				job.getCompletableFuture().isDone());
		Assert.assertFalse("a cancelled launch completes normally, not exceptionally",
				job.getCompletableFuture().isCompletedExceptionally());
	}

	/** With no transcript variable set, the per-job dir sits under a user-writable root. */
	@Test(timeout = 10000)
	public void defaultsToAUserWritableTranscriptRoot() throws Exception {
		StubLauncher launcher = new StubLauncher(StubSession.completing(0, null));
		DockerNode node = new DockerNode(null, 4, 4, dockerConfig(), launcher, Map.of());

		node.launch(new StubJob("task-default-root"));

		String transcriptDir = launcher.requests.get(0).transcriptHostDir();
		Assert.assertTrue("the default transcript root is under the user's home",
				transcriptDir.startsWith(System.getProperty("user.home")));
		Assert.assertFalse("the default transcript root is not the unwritable filesystem root",
				transcriptDir.startsWith("/agent-transcripts"));
	}

	/** A missing image is reported at construction rather than failing opaquely later. */
	@Test(timeout = 10000)
	public void requiresAnImage() {
		NodeConfig noImage = new NodeConfig(0, NodeKind.DOCKER, Map.of(), null, null, null);
		try {
			new DockerNode(null, 4, 4, noImage, new StubLauncher(StubSession.completing(0, null)));
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

		/** Run inside {@link #launch(ContainerRequest)}, or {@code null} for nothing. */
		private Runnable onLaunch;

		/**
		 * @param session  the session every launch returns.
		 */
		private StubLauncher(StubSession session) {
			this.session = session;
		}

		@Override
		public ContainerSession launch(ContainerRequest request) {
			requests.add(request);
			if (onLaunch != null) onLaunch.run();
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

		/** Set once the reader {@link #output()} handed out has been closed. */
		private final AtomicBoolean outputClosed = new AtomicBoolean(false);

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
			return new BufferedReader(new StringReader(output)) {
				@Override
				public void close() throws IOException {
					outputClosed.set(true);
					super.close();
				}
			};
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

		/** Thrown from {@link #encode()} when set, to simulate an encoding failure. */
		private RuntimeException encodeFailure;

		/**
		 * @param taskId  the task identifier.
		 */
		private StubJob(String taskId) {
			this.taskId = taskId;
		}

		/**
		 * Makes this job's wire encoding fail, so a launch can be exercised against a job
		 * that cannot be serialized.
		 *
		 * @param failure  the exception {@link #encode()} should throw.
		 * @return  this job.
		 */
		private StubJob failingToEncode(RuntimeException failure) {
			this.encodeFailure = failure;
			return this;
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
			if (encodeFailure != null) throw encodeFailure;
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
