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
import org.almostrealism.io.JobOutput;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Exercises the {@link CodingAgentJobWorker} execution boundary with an in-memory
 * encoded job and output stream: the worker decodes the payload exactly as written,
 * installs its own output consumer, applies the container-local working directory,
 * waits for the job's future, and writes the framed result line, and it reports a
 * worker failure when it cannot decode or finish the job.
 *
 * @author  Michael Murray
 */
public class CodingAgentJobWorkerTest extends TestSuiteBase {

	/** The working-directory property value before the test, restored afterwards. */
	private String previousWorkingDirectory;

	/** Records the working-directory property so the worker's change can be undone. */
	@Before
	public void saveWorkingDirectory() {
		previousWorkingDirectory = System.getProperty(GitManagedJob.WORKING_DIRECTORY_PROPERTY);
	}

	/** Restores the working-directory property the worker sets. */
	@After
	public void restoreWorkingDirectory() {
		if (previousWorkingDirectory == null) {
			System.clearProperty(GitManagedJob.WORKING_DIRECTORY_PROPERTY);
		} else {
			System.setProperty(GitManagedJob.WORKING_DIRECTORY_PROPERTY, previousWorkingDirectory);
		}
	}

	/**
	 * A job that emits output is run to completion and its output reaches the host as
	 * one framed result line, with the job running under the container-local root.
	 */
	@Test(timeout = 30000)
	public void runsTheJobAndEmitsItsResultFrame() {
		ProbeJob.reset();
		Worker run = Worker.execute(ProbeJob.wire("task-ok", "the agent output"));

		Assert.assertEquals("a completed job exits with the completed status",
				CodingAgentJobWorker.EXIT_COMPLETED, run.status);
		List<ContainerWorkerResult> results = run.results();
		Assert.assertEquals("exactly one result frame is written", 1, results.size());
		Assert.assertEquals("the frame carries the job's output", "the agent output",
				results.get(0).output());
		Assert.assertEquals("the frame carries the agent session", "session-task-ok",
				results.get(0).sessionId());
		Assert.assertEquals("the frame carries the prompt", "prompt-task-ok",
				results.get(0).prompt());
		Assert.assertEquals("the job ran under the container-local root",
				expectedContainerRoot(), ProbeJob.workingDirectorySeen);
	}

	/**
	 * The payload is decoded exactly as written: a value ending in whitespace reaches
	 * the job intact rather than being shortened before decoding.
	 */
	@Test(timeout = 30000)
	public void decodesThePayloadVerbatim() {
		ProbeJob.reset();
		Worker run = Worker.execute(ProbeJob.wire("task-space", "output with trailing space  \n"));

		Assert.assertEquals(CodingAgentJobWorker.EXIT_COMPLETED, run.status);
		Assert.assertEquals("trailing whitespace in the last value survives decoding",
				"output with trailing space  \n", run.results().get(0).output());
	}

	/** A job that emits no output produces no result frame, as a native run fires none. */
	@Test(timeout = 30000)
	public void emitsNoResultFrameWhenTheJobEmitsNoOutput() {
		ProbeJob.reset();
		Worker run = Worker.execute(ProbeJob.class.getName() + "::id:=task-silent");

		Assert.assertEquals(CodingAgentJobWorker.EXIT_COMPLETED, run.status);
		Assert.assertTrue("no result frame is written", run.results().isEmpty());
		Assert.assertTrue("the job still ran", ProbeJob.ran);
	}

	/** A payload that does not decode to a job is a worker failure. */
	@Test(timeout = 30000)
	public void reportsAWorkerFailureForAnUndecodablePayload() {
		Worker run = Worker.execute("not a job payload");

		Assert.assertEquals(CodingAgentJobWorker.EXIT_WORKER_FAILURE, run.status);
		Assert.assertTrue("no result frame is written", run.results().isEmpty());
	}

	/** An empty input is a worker failure rather than an empty job. */
	@Test(timeout = 30000)
	public void reportsAWorkerFailureForAnEmptyPayload() {
		Worker run = Worker.execute("");

		Assert.assertEquals(CodingAgentJobWorker.EXIT_WORKER_FAILURE, run.status);
	}

	/** A job whose completion future fails did not run to completion: a worker failure. */
	@Test(timeout = 30000)
	public void reportsAWorkerFailureWhenTheJobFutureFails() {
		ProbeJob.reset();
		Worker run = Worker.execute(ProbeJob.wire("task-broken", "partial") + "::fail:=true");

		Assert.assertEquals(CodingAgentJobWorker.EXIT_WORKER_FAILURE, run.status);
		Assert.assertTrue("no result frame is written for an unfinished job",
				run.results().isEmpty());
		Assert.assertTrue("the failure is reported on the output stream",
				run.text.contains("did not run to completion"));
	}

	/**
	 * @return  the container-local root the worker is expected to apply.
	 */
	private static String expectedContainerRoot() {
		String root = System.getenv(CodingAgentJobWorker.CONTAINER_ROOT_ENV);
		return root == null || root.isEmpty() ? CodingAgentJobWorker.DEFAULT_CONTAINER_ROOT : root;
	}

	/**
	 * One in-memory run of the worker: its exit status and everything it wrote.
	 */
	private static final class Worker {

		/** The worker's exit status. */
		private final int status;

		/** Everything the worker wrote to its output stream. */
		private final String text;

		/**
		 * @param status  the worker's exit status.
		 * @param text    the worker's output.
		 */
		private Worker(int status, String text) {
			this.status = status;
			this.text = text;
		}

		/**
		 * Runs the worker over an in-memory payload.
		 *
		 * @param payload  the bytes fed to the worker's input.
		 * @return  the run's status and output.
		 */
		private static Worker execute(String payload) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
			int status = CodingAgentJobWorker.execute(
					new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), out);
			out.flush();
			return new Worker(status, bytes.toString(StandardCharsets.UTF_8));
		}

		/**
		 * @return  every framed result line the worker wrote, parsed.
		 */
		private List<ContainerWorkerResult> results() {
			List<ContainerWorkerResult> results = new ArrayList<>();
			for (String line : text.split("\n")) {
				ContainerWorkerResult parsed = ContainerWorkerResult.parseLine(line);
				if (parsed != null) results.add(parsed);
			}
			return results;
		}
	}

	/**
	 * A job decoded by the worker from the string wire form. It records what it saw
	 * while running and emits a {@link CodingAgentJobOutput} when given an output value.
	 * It must be public with a public no-argument constructor so the codec can
	 * instantiate it.
	 */
	public static final class ProbeJob implements Job {

		/** Set once a probe job has run. */
		private static volatile boolean ran;

		/** The working-directory property value the last probe job saw. */
		private static volatile String workingDirectorySeen;

		/** The task identifier. */
		private String taskId;

		/** The output to emit, or {@code null} for none. */
		private String output;

		/** Whether to fail the completion future instead of completing it. */
		private boolean fail;

		/** The output consumer the worker installs. */
		private Consumer<JobOutput> outputConsumer;

		/** The job's completion future. */
		private final CompletableFuture<Void> future = new CompletableFuture<>();

		/** Required by the codec. */
		public ProbeJob() {
		}

		/** Clears what earlier probe runs recorded. */
		private static void reset() {
			ran = false;
			workingDirectorySeen = null;
		}

		/**
		 * @param taskId  the task identifier.
		 * @param output  the output the job emits, as the last wire value.
		 * @return  the job's string wire form.
		 */
		private static String wire(String taskId, String output) {
			return ProbeJob.class.getName() + "::id:=" + taskId + "::output:=" + output;
		}

		@Override
		public void run() {
			ran = true;
			workingDirectorySeen = System.getProperty(GitManagedJob.WORKING_DIRECTORY_PROPERTY);
			if (output != null && outputConsumer != null) {
				outputConsumer.accept(new CodingAgentJobOutput(taskId, "prompt-" + taskId,
						output, "session-" + taskId, 0));
			}
			if (fail) {
				future.completeExceptionally(new IllegalStateException("job broke"));
			} else {
				future.complete(null);
			}
		}

		@Override
		public void set(String key, String value) {
			if ("id".equals(key)) taskId = value;
			if ("output".equals(key)) output = value;
			if ("fail".equals(key)) fail = Boolean.parseBoolean(value);
		}

		@Override
		public String encode() {
			return wire(taskId, output);
		}

		@Override
		public String getTaskId() {
			return taskId;
		}

		@Override
		public String getTaskString() {
			return "ProbeJob[" + taskId + "]";
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
