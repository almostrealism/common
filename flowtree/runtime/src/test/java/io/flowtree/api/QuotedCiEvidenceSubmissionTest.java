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

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The controller-side half of the guarantee that an auto-resolve prompt quoting a CI log which
 * names the CI shard-selection variables can still be submitted, while an instruction to use
 * those variables cannot.
 *
 * <p>The failure log in {@code tools/ci/testdata/python-tests-shard-variable-failure.txt} is the
 * shape that used to be refused with HTTP 400: the prompt-template lint test failing, its
 * assertion message quoting the template line that names the variables. Each test builds the
 * real {@code python-tests} auto-resolve prompt from it with
 * {@code tools/ci/prompts/build-python-failure-prompt.sh} (whose {@code redact_ci_shard_variables}
 * filter rewrites the names inside the quoted excerpt) and hands the result to
 * {@link TestExecutionLimitsSubmissionValidator}, the check {@code /api/submit} applies. The
 * ar-manager half is {@code tools/tests/test_quoted_ci_evidence.py}.</p>
 */
public class QuotedCiEvidenceSubmissionTest extends TestSuiteBase {

	/** The quoted failure log, relative to the repository root. */
	private static final String FIXTURE = "tools/ci/testdata/python-tests-shard-variable-failure.txt";

	/** The auto-resolve builder for a {@code python-tests} failure, relative to the root. */
	private static final String BUILDER = "tools/ci/prompts/build-python-failure-prompt.sh";

	/** The text the builder substitutes for each shard-variable name in a quoted excerpt. */
	private static final String PLACEHOLDER = "<redacted CI shard variable>";

	/** Instruction text, outside any quoted output, telling the agent to use the variables. */
	private static final String SHARD_INSTRUCTION =
			"Then reproduce it with AR_TEST_GROUP=2 exported, as CI does.";

	/** The fixture really exercises the defect: quoted verbatim, the controller refuses it. */
	@Test(timeout = 30000)
	public void verbatimLogIsRejected() throws IOException {
		String log = Files.readString(repositoryRoot().resolve(FIXTURE));
		assertNotNull("the verbatim log must be refused", submit(log));
	}

	/** The prompt the builder produces from the log is accepted, and still carries the evidence. */
	@Test(timeout = 60000)
	public void builtPromptQuotingTheLogIsAccepted() throws IOException, InterruptedException {
		String prompt = buildPrompt();
		assertNull("the built prompt must be accepted", submit(prompt));
		assertTrue(prompt.contains("TestCiPromptTemplatesLintClean"));
		assertTrue(prompt.contains(PLACEHOLDER));
		assertFalse(prompt.contains("AR_TEST_GROUP"));
	}

	/** Redaction touches only quoted output, so an instruction to use the variables is refused. */
	@Test(timeout = 60000)
	public void instructionOutsideTheQuotedLogIsRejected() throws IOException, InterruptedException {
		List<String> logLines = new ArrayList<>();
		Response response = submit(buildPrompt() + "\n" + SHARD_INSTRUCTION + "\n", logLines);
		assertNotNull("an instruction naming the shard variables must be refused", response);
		assertEquals(1, logLines.size());
		assertTrue(logLines.get(0), logLines.get(0).contains("AR_TEST_GROUP"));
	}

	/** Validates {@code prompt} as {@code /api/submit} does; {@code null} means accepted. */
	private Response submit(String prompt) {
		return submit(prompt, new ArrayList<>());
	}

	/** Validates {@code prompt} as {@code /api/submit} does, recording rejections in {@code logLines}. */
	private Response submit(String prompt, List<String> logLines) {
		TestExecutionLimitsSubmissionValidator validator = new TestExecutionLimitsSubmissionValidator(
				logLines::add,
				message -> NanoHTTPD.newFixedLengthResponse(
						Response.Status.BAD_REQUEST, "text/plain", message));
		return validator.validate("ws-quoted-evidence", null, null, prompt);
	}

	/**
	 * Runs the {@code python-tests} auto-resolve builder on the fixture log and returns the
	 * prompt it writes.
	 */
	private String buildPrompt() throws IOException, InterruptedException {
		Path root = repositoryRoot();
		Path output = Files.createTempFile("quoted-ci-evidence-", ".txt");
		try {
			ProcessBuilder builder = new ProcessBuilder("bash", root.resolve(BUILDER).toString(),
					root.resolve(FIXTURE).toString(), output.toString());
			builder.environment().put("BRANCH", "ci/example");
			builder.environment().put("COMMIT_SHA", "0".repeat(40));
			builder.redirectErrorStream(true);
			Process process = builder.start();
			String transcript = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertTrue("builder timed out", process.waitFor(30, TimeUnit.SECONDS));
			assertEquals(transcript, 0, process.exitValue());
			return Files.readString(output);
		} finally {
			Files.deleteIfExists(output);
		}
	}

	/** The repository root: the nearest directory at or above the working directory holding the fixture. */
	private static Path repositoryRoot() {
		for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			if (Files.isRegularFile(dir.resolve(FIXTURE))) {
				return dir;
			}
		}
		throw new IllegalStateException("Could not locate " + FIXTURE + " above "
				+ Paths.get("").toAbsolutePath());
	}
}
