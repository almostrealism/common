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

package io.flowtree;

import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.test.ServerTestBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Exercises the lifecycle and failure handling of {@link CodingAgentClient}: the
 * agent pool accounting, the state guards that reject use before {@link
 * CodingAgentClient#start()} or without any configured agent, and the submission
 * path when the target agent is unreachable. A closed (refused) local port drives
 * the unreachable case deterministically, with no reliance on timing.
 *
 * @author Michael Murray
 */
public class CodingAgentClientTest extends ServerTestBase {

	/**
	 * Returns a local port that is guaranteed to be closed, so a connection attempt
	 * to it is refused immediately rather than hanging.
	 *
	 * @return a closed TCP port on the loop-back interface
	 * @throws IOException if a probe socket cannot be opened
	 */
	private static int closedPort() throws IOException {
		ServerSocket probe = new ServerSocket(0);
		int port = probe.getLocalPort();
		probe.close();
		return port;
	}

	/** A new client has no configured agents. */
	@Test(timeout = 60000)
	public void newClientHasNoAgents() {
		Assert.assertEquals(0, new CodingAgentClient().getAgentCount());
	}

	/** Each added agent increments the configured-agent count. */
	@Test(timeout = 60000)
	public void addAgentIncrementsCount() {
		CodingAgentClient client = new CodingAgentClient();
		client.addAgent("localhost", 7766);
		client.addAgent("localhost", 7767);
		Assert.assertEquals(2, client.getAgentCount());
	}

	/** Starting with no agents configured is rejected. */
	@Test(timeout = 60000)
	public void startWithoutAgentsIsRejected() throws IOException {
		try {
			new CodingAgentClient().start();
			Assert.fail("expected IllegalStateException when no agents are configured");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	/** Submitting before {@link CodingAgentClient#start()} is rejected. */
	@Test(timeout = 60000)
	public void submitBeforeStartIsRejected() {
		CodingAgentClient client = new CodingAgentClient();
		client.addAgent("localhost", 7766);
		try {
			client.submit("do the work");
			Assert.fail("expected IllegalStateException before start()");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	/** Targeted submission before {@link CodingAgentClient#start()} is rejected. */
	@Test(timeout = 60000)
	public void submitToBeforeStartIsRejected() {
		CodingAgentClient client = new CodingAgentClient();
		client.addAgent("localhost", 7766);
		CodingAgentJob.Factory factory = new CodingAgentJob.Factory("do the work");
		try {
			client.submitTo(factory, 0);
			Assert.fail("expected IllegalStateException before start()");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	/** After starting, a submission to an out-of-range agent index is rejected. */
	@Test(timeout = 60000)
	public void submitToInvalidIndexIsRejected() throws IOException {
		CodingAgentClient client = new CodingAgentClient();
		client.addAgent("localhost", closedPort());
		client.start();

		CodingAgentJob.Factory factory = new CodingAgentJob.Factory("do the work");
		try {
			client.submitTo(factory, 3);
			Assert.fail("expected IllegalArgumentException for out-of-range index");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/** A submission to an unreachable agent reports failure rather than throwing. */
	@Test(timeout = 60000)
	public void submitToUnreachableAgentReportsFailure() throws IOException {
		CodingAgentClient client = new CodingAgentClient();
		client.addAgent("localhost", closedPort());
		client.start();

		Assert.assertFalse(client.submit("fix the unreachable thing"));
	}
}
