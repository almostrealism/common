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

import io.flowtree.JsonFieldExtractor;
import io.flowtree.job.AbstractJobFactory;
import io.flowtree.job.Job;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Job} implementation that executes a single shell command against a
 * git repository and publishes the command output as a workstream message for
 * visibility in Slack and workstream history.
 *
 * <p>This job type is the command-execution counterpart of
 * {@link CodingAgentJob}: it reuses the {@link GitManagedJob} lifecycle to clone
 * the repository (and optionally check out a target branch), then runs a shell
 * command in the resulting working directory instead of launching a coding
 * agent. No agent phases, enforcement rules, or commit/push operations are
 * performed — {@link #validateChanges()} returns {@code false} so the job never
 * mutates the repository, making it safe for read-only commands such as running
 * tests, builds, or inspection commands.</p>
 *
 * <h2>Message Format</h2>
 * <p>On completion, a message is published with the following structure:</p>
 * <pre>{@code
 * ShellCommandJob [taskId]: Command completed with exit code N
 *
 * Command: (truncated)
 *
 * STDOUT:
 * (captured stdout)
 *
 * STDERR:
 * (captured stderr)
 * }</pre>
 *
 * <p>When output exceeds the truncation threshold it is shortened with
 * {@code " [truncated]"} appended.</p>
 *
 * @author Michael Murray
 * @see GitManagedJob
 * @see CodingAgentJob
 */
public class ShellCommandJob extends GitManagedJob {

	/** Threshold for truncating command output in the completion message. */
	private static final int OUTPUT_TRUNCATE_LENGTH = 2800;

	/** Maximum number of characters of the command echoed in the message. */
	private static final int COMMAND_TRUNCATE_LENGTH = 200;

	/** Maximum characters in a short single-line command summary. */
	private static final int COMMAND_SUMMARY_LENGTH = 60;

	/**
	 * Wall-clock limit, in seconds, before a running command is forcibly
	 * killed. Bounds the time a hung command can block the agent.
	 */
	private static final int DEFAULT_TIMEOUT_SECONDS = 1800;

	/**
	 * Maximum number of bytes read from each of the command's stdout and stderr
	 * streams. The tail is kept so failure messages near the end survive; this
	 * bounds heap usage for a command that emits very large output.
	 */
	private static final int MAX_CAPTURE_BYTES = 65536;

	/** Suffix appended to truncated strings. */
	private static final String TRUNCATION_MARKER = " [truncated]";

	/** The shell command to execute. */
	private String command;

	/** Captured standard output from the command. */
	private String stdout;

	/** Captured standard error from the command. */
	private String stderr;

	/** Exit code returned by the command, or {@code -1} if not yet executed. */
	private int exitCode = -1;

	/** Default constructor for deserialization. */
	public ShellCommandJob() {
	}

	/**
	 * Creates a new ShellCommandJob with the specified task ID.
	 *
	 * @param taskId the task identifier
	 */
	public ShellCommandJob(String taskId) {
		super(taskId);
	}

	/**
	 * Creates a new ShellCommandJob with the specified task ID and command.
	 *
	 * @param taskId  the task identifier
	 * @param command the shell command to execute
	 */
	public ShellCommandJob(String taskId, String command) {
		super(taskId);
		this.command = command;
	}

	/**
	 * Returns the shell command to be executed.
	 *
	 * @return the command string
	 */
	public String getCommand() {
		return command;
	}

	/**
	 * Sets the shell command to execute.
	 *
	 * @param command the shell command string
	 */
	public void setCommand(String command) {
		this.command = command;
	}

	/**
	 * Returns the captured standard output.
	 *
	 * @return the stdout string, or {@code null} if not yet executed
	 */
	public String getStdout() {
		return stdout;
	}

	/**
	 * Returns the captured standard error.
	 *
	 * @return the stderr string, or {@code null} if not yet executed
	 */
	public String getStderr() {
		return stderr;
	}

	/**
	 * Returns the command exit code.
	 *
	 * @return the exit code, or {@code -1} if not yet executed
	 */
	public int getExitCode() {
		return exitCode;
	}

	/**
	 * Executes the configured shell command in the job's working directory
	 * (the cloned repository) and captures its output. stdout and stderr are
	 * redirected to temporary files so a noisy command cannot exhaust heap, the
	 * process is killed if it exceeds {@link #DEFAULT_TIMEOUT_SECONDS}, and only
	 * the trailing {@link #MAX_CAPTURE_BYTES} of each stream are read back. The
	 * result is published as a workstream message when a workstream URL is
	 * configured, regardless of whether the command succeeds or fails.
	 */
	@Override
	protected void doWork() {
		if (command == null || command.isEmpty()) {
			warn("No command configured for ShellCommandJob");
			exitCode = -1;
			stdout = "";
			stderr = "No command configured";
			publishOutput();
			return;
		}

		log("Executing command: " + command);

		File outFile = null;
		File errFile = null;
		try {
			outFile = File.createTempFile("shellcmd-out-", ".log");
			errFile = File.createTempFile("shellcmd-err-", ".log");

			ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
			if (getWorkingDirectory() != null) {
				pb.directory(new File(getWorkingDirectory()));
			}
			pb.redirectOutput(outFile);
			pb.redirectError(errFile);

			GitOperations.augmentPath(pb);

			Process process = pb.start();
			boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			stdout = readCapped(outFile);
			if (finished) {
				exitCode = process.exitValue();
				stderr = readCapped(errFile);
				log("Command completed with exit code " + exitCode);
			} else {
				process.destroyForcibly();
				exitCode = -1;
				stderr = "Command timed out after " + DEFAULT_TIMEOUT_SECONDS
						+ " seconds (process killed).\n" + readCapped(errFile);
				warn("Command timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds: " + command);
			}
		} catch (IOException e) {
			warn("Command execution failed: " + e.getMessage(), e);
			exitCode = -1;
			stderr = e.getMessage();
			stdout = stdout == null ? "" : stdout;
		} catch (InterruptedException e) {
			warn("Command execution interrupted: " + e.getMessage(), e);
			exitCode = -1;
			stderr = e.getMessage();
			stdout = stdout == null ? "" : stdout;
			Thread.currentThread().interrupt();
		} finally {
			deleteQuietly(outFile);
			deleteQuietly(errFile);
		}

		publishOutput();
	}

	/**
	 * Reads a redirected output file as UTF-8 text, keeping at most the trailing
	 * {@link #MAX_CAPTURE_BYTES} bytes so that a runaway command cannot exhaust
	 * the agent's heap.
	 *
	 * @param file the redirected output file
	 * @return the (possibly tail-only) file contents, or an error message if it
	 *         cannot be read
	 */
	private static String readCapped(File file) {
		try {
			long size = file.length();
			if (size <= MAX_CAPTURE_BYTES) {
				return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			}
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				raf.seek(size - MAX_CAPTURE_BYTES);
				byte[] buf = new byte[MAX_CAPTURE_BYTES];
				raf.readFully(buf);
				return new String(buf, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			return "Failed to read command output: " + e.getMessage();
		}
	}

	/**
	 * Deletes a temporary file if it exists, ignoring failures (the OS purges
	 * the temp directory on a schedule).
	 *
	 * @param file the file to delete, or {@code null}
	 */
	private static void deleteQuietly(File file) {
		if (file != null && file.exists()) {
			file.delete();
		}
	}

	/**
	 * Returns a human-readable description of this job.
	 *
	 * @return the command string as the task description
	 */
	@Override
	public String getTaskString() {
		return command != null ? command : "";
	}

	/**
	 * Skips all git commit and push handling: a ShellCommandJob runs a command
	 * against the repository but never mutates it.
	 *
	 * @return always {@code false}
	 */
	@Override
	protected boolean validateChanges() {
		return false;
	}

	/**
	 * Publishes the command output as a workstream message when a workstream URL
	 * is configured.
	 */
	private void publishOutput() {
		String url = getWorkstreamUrl();
		if (url == null || url.isEmpty()) {
			return;
		}

		String message = buildOutputMessage();
		postJson(resolveWorkstreamUrl() + "/messages",
				"{\"text\":\"" + JsonFieldExtractor.escapeJson(message) + "\"}");
	}

	/**
	 * Builds a human-readable message describing the command result.
	 *
	 * @return the formatted message
	 */
	String buildOutputMessage() {
		StringBuilder sb = new StringBuilder();
		sb.append("ShellCommandJob [").append(getTaskId()).append("]: ");
		sb.append("Command completed with exit code ").append(exitCode).append("\n\n");

		sb.append("Command: ").append(truncate(command, COMMAND_TRUNCATE_LENGTH)).append("\n\n");

		if (stdout != null && !stdout.isEmpty()) {
			sb.append("STDOUT:\n").append(truncate(stdout, OUTPUT_TRUNCATE_LENGTH)).append("\n\n");
		}

		if (stderr != null && !stderr.isEmpty()) {
			sb.append("STDERR:\n").append(truncate(stderr, OUTPUT_TRUNCATE_LENGTH)).append("\n\n");
		}

		if (exitCode != 0) {
			sb.append("Note: exit code != 0 indicates command failure");
		}

		return sb.toString();
	}

	/**
	 * Truncates a string to at most {@code maxLength} characters, appending
	 * {@link #TRUNCATION_MARKER} when shortened. Small or non-positive limits are
	 * handled without overflow: a non-positive limit yields an empty string, and
	 * a limit too small to hold the marker yields a bare prefix of the input.
	 *
	 * @param s         the string to truncate, or {@code null}
	 * @param maxLength maximum number of characters to retain
	 * @return the (possibly truncated) string
	 */
	static String truncate(String s, int maxLength) {
		if (s == null) return "";
		if (maxLength <= 0) return "";
		if (s.length() <= maxLength) return s;
		if (maxLength <= TRUNCATION_MARKER.length()) {
			return s.substring(0, maxLength);
		}
		return s.substring(0, maxLength - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
	}

	/**
	 * Produces a short, single-line summary of a command suitable for status
	 * notifications. Whitespace is collapsed and the result is truncated to
	 * {@link #COMMAND_SUMMARY_LENGTH} characters.
	 *
	 * @param command the command, or {@code null}
	 * @return a single-line summary, never {@code null}
	 */
	public static String summarizeCommand(String command) {
		if (command == null || command.isEmpty()) {
			return "(no command)";
		}
		return truncate(command.replaceAll("\\s+", " ").trim(), COMMAND_SUMMARY_LENGTH);
	}

	/**
	 * Encodes this job for transmission over the FlowTree messaging layer. The
	 * base {@link GitManagedJob} fields are emitted by {@code super.encode()};
	 * this override appends the command.
	 *
	 * @return the encoded job string
	 */
	@Override
	public String encode() {
		StringBuilder sb = new StringBuilder(super.encode());
		if (command != null) {
			sb.append("::command:=").append(base64Encode(command));
		}
		return sb.toString();
	}

	/**
	 * Deserializes a single key-value property into this job instance. The
	 * {@code command} key is handled here; all other keys (including the
	 * {@link GitManagedJob} fields) are delegated to {@code super.set(...)}.
	 *
	 * @param key   the property key
	 * @param value the property value (Base64-encoded for string fields)
	 */
	@Override
	public void set(String key, String value) {
		if ("command".equals(key)) {
			this.command = base64Decode(value);
		} else {
			super.set(key, value);
		}
	}

	/**
	 * Factory for creating {@link ShellCommandJob} instances. Each factory holds
	 * a single command plus the git configuration needed to clone and check out
	 * the repository, and produces one job via {@link #nextJob()}.
	 *
	 * <p>All configuration is stored in the inherited property map (Base64-encoded
	 * via {@link GitManagedJob#base64Encode(String)}) so it survives the
	 * encode/decode round-trip performed when the factory is dispatched to a
	 * remote node.</p>
	 */
	public static class Factory extends AbstractJobFactory {

		/** Whether the single job has already been produced by {@link #nextJob()}. */
		private boolean dispatched;

		/** Creates a factory with a randomly generated task ID. */
		public Factory() {
			super(UUID.randomUUID().toString());
		}

		/**
		 * Creates a factory with the specified command.
		 *
		 * @param command the shell command to execute
		 */
		public Factory(String command) {
			this();
			setCommand(command);
		}

		/**
		 * Returns the configured command.
		 *
		 * @return the command string, or {@code null} if unset
		 */
		public String getCommand() {
			return GitManagedJob.base64Decode(get("command"));
		}

		/**
		 * Sets the command to execute.
		 *
		 * @param command the shell command string
		 */
		public void setCommand(String command) {
			set("command", GitManagedJob.base64Encode(command));
		}

		/**
		 * Returns the working directory the command runs in.
		 *
		 * @return the working directory path, or {@code null} if unset
		 */
		public String getWorkingDirectory() {
			return GitManagedJob.base64Decode(get("workDir"));
		}

		/**
		 * Sets the working directory the command runs in. When unset and a repo
		 * URL is configured, the repository is cloned to a resolved location.
		 *
		 * @param workingDirectory the working directory path
		 */
		public void setWorkingDirectory(String workingDirectory) {
			set("workDir", GitManagedJob.base64Encode(workingDirectory));
		}

		/**
		 * Returns the repository URL cloned before the command runs.
		 *
		 * @return the repository URL, or {@code null} if unset
		 */
		public String getRepoUrl() {
			return GitManagedJob.base64Decode(get("repoUrl"));
		}

		/**
		 * Sets the repository URL to clone before the command runs.
		 *
		 * @param repoUrl the repository URL
		 */
		public void setRepoUrl(String repoUrl) {
			set("repoUrl", GitManagedJob.base64Encode(repoUrl));
		}

		/**
		 * Returns the branch checked out before the command runs.
		 *
		 * @return the target branch, or {@code null} if unset
		 */
		public String getTargetBranch() {
			return GitManagedJob.base64Decode(get("branch"));
		}

		/**
		 * Sets the branch to check out before the command runs.
		 *
		 * @param targetBranch the target branch
		 */
		public void setTargetBranch(String targetBranch) {
			set("branch", GitManagedJob.base64Encode(targetBranch));
		}

		/**
		 * Returns the workstream URL used for status reporting.
		 *
		 * @return the workstream URL, or {@code null} if unset
		 */
		public String getWorkstreamUrl() {
			return GitManagedJob.base64Decode(get("workstreamUrl"));
		}

		/**
		 * Sets the workstream URL used for status reporting.
		 *
		 * @param workstreamUrl the workstream URL
		 */
		public void setWorkstreamUrl(String workstreamUrl) {
			set("workstreamUrl", GitManagedJob.base64Encode(workstreamUrl));
		}

		/**
		 * Returns the next job to execute, or {@code null} if the single job has
		 * already been dispatched or no command is configured.
		 *
		 * @return the next job, or {@code null} if exhausted
		 */
		@Override
		public Job nextJob() {
			String cmd = getCommand();
			if (dispatched || cmd == null || cmd.isEmpty()) {
				return null;
			}
			dispatched = true;

			ShellCommandJob job = new ShellCommandJob(getTaskId(), cmd);
			job.setWorkstreamUrl(getWorkstreamUrl());
			job.setWorkingDirectory(getWorkingDirectory());

			String repoUrl = getRepoUrl();
			if (repoUrl != null) {
				job.setRepoUrl(repoUrl);
			}

			String targetBranch = getTargetBranch();
			if (targetBranch != null) {
				job.setTargetBranch(targetBranch);
			}

			for (Map.Entry<String, String> entry : getRequiredLabels().entrySet()) {
				job.setRequiredLabel(entry.getKey(), entry.getValue());
			}

			return job;
		}

		/**
		 * Not used — jobs are produced via {@link #nextJob()} and reconstructed
		 * on remote nodes by the {@code JobClassLoader} (no-arg constructor plus
		 * {@link ShellCommandJob#set(String, String)}).
		 *
		 * @param data ignored
		 * @return always {@code null}
		 */
		@Override
		public Job createJob(String data) {
			return null;
		}

		/**
		 * Returns {@code 1.0} once the single job has been dispatched (or there is
		 * no command), {@code 0.0} otherwise.
		 *
		 * @return the completeness ratio
		 */
		@Override
		public double getCompleteness() {
			String cmd = getCommand();
			return (dispatched || cmd == null || cmd.isEmpty()) ? 1.0 : 0.0;
		}
	}
}
