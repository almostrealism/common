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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link TmuxSession}. Skipped when {@code tmux} is not on the PATH
 * so the suite still passes in minimal environments.
 */
public class TmuxSessionTest extends TestSuiteBase {

	/** Skips tests when tmux is not available on PATH. */
	@Before
	public void requireTmux() {
		Assume.assumeTrue("tmux not on PATH", tmuxAvailable());
	}

	/** startAndWaitFor returns the wrapped process exit code. */
	@Test(timeout = 30000)
	public void startAndWaitForCapturesExitCode() throws Exception {
		try (TmuxSession session = TmuxSession.create()) {
			session.start(Arrays.asList("bash", "-c", "exit 42"));
			int code = session.waitFor(10_000);
			assertEquals(42, code);
		}
	}

	/** captureOutput streams stdout lines from the wrapped process. */
	@Test(timeout = 30000)
	public void captureOutputStreamsStdoutLines() throws Exception {
		try (TmuxSession session = TmuxSession.create()) {
			session.start(Arrays.asList("bash", "-c",
					"echo hello-line-one; echo hello-line-two"));
			StringBuilder collected = new StringBuilder();
			try (BufferedReader reader = session.captureOutput()) {
				String line;
				while ((line = reader.readLine()) != null) {
					collected.append(line).append("\n");
				}
			}
			int code = session.waitFor(10_000);
			assertEquals(0, code);
			String out = collected.toString();
			assertTrue("expected first line in: " + out,
					out.contains("hello-line-one"));
			assertTrue("expected second line in: " + out,
					out.contains("hello-line-two"));
		}
	}

	/** sendLine delivers text to the wrapped process stdin. */
	@Test(timeout = 30000)
	public void sendLineDeliversTextToWrappedProcess() throws Exception {
		try (TmuxSession session = TmuxSession.create()) {
			session.start(Arrays.asList("bash", "-c",
					"read line; echo GOT-LINE:\"$line\""));
			// Give bash a moment to reach `read`.
			Thread.sleep(400);
			session.sendLine("hello-stdin");
			int code = session.waitFor(10_000);
			assertEquals(0, code);
			try (BufferedReader reader = session.captureOutput()) {
				StringBuilder all = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					all.append(line).append("\n");
				}
				assertTrue("expected echoed input in: " + all,
						all.toString().contains("GOT-LINE:hello-stdin"));
			}
		}
	}

	/** close kills a running tmux session. */
	@Test(timeout = 30000)
	public void closeKillsRunningSession() throws Exception {
		TmuxSession session = TmuxSession.create();
		try {
			session.start(Arrays.asList("bash", "-c", "sleep 30"));
			assertTrue("session should be alive", session.isAlive());
			session.close();
			Thread.sleep(300);
			assertFalse("session should be dead after close", session.isAlive());
		} finally {
			session.close();
		}
	}

	/** environment overrides are visible to the command via the set environment. */
	@Test(timeout = 30000)
	public void environmentOverridesAreVisibleToCommand() throws Exception {
		try (TmuxSession session = TmuxSession.create()) {
			session.environment("AR_TEST_VAR", "tmux-session-value");
			session.start(Arrays.asList("bash", "-c",
					"echo VAR=$AR_TEST_VAR"));
			StringBuilder collected = new StringBuilder();
			try (BufferedReader reader = session.captureOutput()) {
				String line;
				while ((line = reader.readLine()) != null) {
					collected.append(line).append("\n");
				}
			}
			session.waitFor(10_000);
			assertTrue("expected env var in: " + collected,
					collected.toString().contains("VAR=tmux-session-value"));
		}
	}

	/** Checks whether tmux is available on PATH. */
	private static boolean tmuxAvailable() {
		try {
			ProcessBuilder pb = new ProcessBuilder("tmux", "-V");
			pb.redirectErrorStream(true);
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			return pb.start().waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
