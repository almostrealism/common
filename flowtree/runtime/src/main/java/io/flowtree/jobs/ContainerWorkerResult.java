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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The result a {@link CodingAgentJobWorker} reports to its host {@code DockerNode}
 * over the container's standard output, and the framing of that output stream.
 *
 * <p>Because the worker is not a Server, its standard output is the only transport
 * back to the host. The stream carries two kinds of framed line, distinguished by a
 * leading control-character marker so ordinary agent logging on the same stream is
 * never mistaken for either:</p>
 * <ul>
 *   <li>a {@link #HEARTBEAT_LINE heartbeat} line, emitted periodically so the host's
 *       outer silence guard sees the worker is alive even while the agent is quiet
 *       inside a long tool call;</li>
 *   <li>a single {@link #encodeLine() result} line, emitted once the job finishes,
 *       carrying every field of the job's {@link CodingAgentJobOutput} — the agent's
 *       exit code, session id, prompt and captured output. The host
 *       {@link #parseLine(String) parses} it and forwards
 *       {@link #toJobOutput(String) the reconstructed output} to the job's own output
 *       consumer, so a containerized run delivers the same metadata a native run
 *       does.</li>
 * </ul>
 *
 * <p>The session id, prompt and output are Base64-encoded so the line is newline-safe and a
 * multi-line transcript never splits the framed record.</p>
 *
 * @author  Michael Murray
 * @see CodingAgentJobWorker
 */
public class ContainerWorkerResult {

	/** Leading marker (a control character to avoid colliding with agent logging). */
	private static final String RESULT_PREFIX = "flowtree-worker-result ";

	/** The liveness line the worker emits while the job runs. */
	public static final String HEARTBEAT_LINE = "flowtree-worker-heartbeat";

	/** The agent process exit code reported by the job's output. */
	private final int agentExitCode;

	/** The agent session id, or an empty string when none was reported. */
	private final String sessionId;

	/** The prompt the agent was given, or an empty string when none was reported. */
	private final String prompt;

	/** The captured agent output. */
	private final String output;

	/**
	 * Constructs a result that reports no prompt.
	 *
	 * @param agentExitCode  the agent process exit code.
	 * @param sessionId      the agent session id; {@code null} is stored as empty.
	 * @param output         the captured output; {@code null} is stored as empty.
	 */
	public ContainerWorkerResult(int agentExitCode, String sessionId, String output) {
		this(agentExitCode, sessionId, "", output);
	}

	/**
	 * Constructs a result.
	 *
	 * @param agentExitCode  the agent process exit code.
	 * @param sessionId      the agent session id; {@code null} is stored as empty.
	 * @param prompt         the prompt the agent was given; {@code null} is stored as empty.
	 * @param output         the captured output; {@code null} is stored as empty.
	 */
	public ContainerWorkerResult(int agentExitCode, String sessionId, String prompt,
			String output) {
		this.agentExitCode = agentExitCode;
		this.sessionId = sessionId == null ? "" : sessionId;
		this.prompt = prompt == null ? "" : prompt;
		this.output = output == null ? "" : output;
	}

	/**
	 * Captures every field of a job's output, for framing onto the worker's standard
	 * output.
	 *
	 * @param output  the output the job emitted; must not be {@code null}.
	 * @return  the result carrying that output's exit code, session id, prompt and text.
	 */
	public static ContainerWorkerResult of(CodingAgentJobOutput output) {
		return new ContainerWorkerResult(output.getExitCode(), output.getSessionId(),
				output.getPrompt(), output.getOutput());
	}

	/**
	 * Reconstructs the job output this result was captured from, so the host delivers
	 * the same {@link CodingAgentJobOutput} — with its prompt, session id and exit code
	 * — that an in-process run would.
	 *
	 * @param taskId  the task id of the job the output belongs to.
	 * @return  the reconstructed output.
	 */
	public CodingAgentJobOutput toJobOutput(String taskId) {
		return new CodingAgentJobOutput(taskId, prompt, output, sessionId, agentExitCode);
	}

	/**
	 * Returns the agent process exit code.
	 *
	 * @return  the agent process exit code.
	 */
	public int agentExitCode() {
		return agentExitCode;
	}

	/**
	 * Returns the agent session id.
	 *
	 * @return  the agent session id; never {@code null}.
	 */
	public String sessionId() {
		return sessionId;
	}

	/**
	 * Returns the prompt the agent was given.
	 *
	 * @return  the prompt; never {@code null}.
	 */
	public String prompt() {
		return prompt;
	}

	/**
	 * Returns the captured agent output.
	 *
	 * @return  the captured agent output; never {@code null}.
	 */
	public String output() {
		return output;
	}

	/**
	 * Encodes this result as the single framed line the worker writes to its standard
	 * output. The counterpart decoder is {@link #parseLine(String)}.
	 *
	 * @return  the framed result line (without a trailing newline).
	 */
	public String encodeLine() {
		return RESULT_PREFIX + agentExitCode + " " + base64(sessionId) + " " + base64(prompt)
				+ " " + base64(output);
	}

	/**
	 * Decodes a framed result line produced by {@link #encodeLine()}.
	 *
	 * @param line  a line read from the container's standard output.
	 * @return  the decoded result, or {@code null} if the line is not a result line
	 *          (a heartbeat, ordinary agent logging, or a malformed record).
	 */
	public static ContainerWorkerResult parseLine(String line) {
		if (line == null || !line.startsWith(RESULT_PREFIX)) return null;

		String[] parts = line.substring(RESULT_PREFIX.length()).split(" ", 4);
		if (parts.length != 4) return null;

		try {
			return new ContainerWorkerResult(Integer.parseInt(parts[0]),
					decode(parts[1]), decode(parts[2]), decode(parts[3]));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Returns whether the given line is the worker's liveness heartbeat.
	 *
	 * @param line  a line read from the container's standard output.
	 * @return  {@code true} if it is a heartbeat line.
	 */
	public static boolean isHeartbeat(String line) {
		return HEARTBEAT_LINE.equals(line);
	}

	/**
	 * @param value  the text to encode.
	 * @return  the Base64 encoding of the UTF-8 bytes of {@code value}.
	 */
	private static String base64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @param value  the Base64 text to decode.
	 * @return  the decoded UTF-8 string.
	 */
	private static String decode(String value) {
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
	}
}
