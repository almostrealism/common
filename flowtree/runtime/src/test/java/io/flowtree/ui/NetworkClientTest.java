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

package io.flowtree.ui;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Exercises {@link NetworkClient}, the thin programmatic client that opens a socket to a FlowTree
 * server so jobs can be submitted without a GUI.
 *
 * <p>The constructor's documented contract is that it throws {@link IOException} when a connection
 * cannot be established. This test pins that failure path deterministically: it reserves a loopback
 * port, releases it, and then connects to it with nothing listening, which must be refused. The
 * connected (success) path is intentionally not exercised here — it requires a live peer performing
 * the {@code ObjectInputStream}/{@code ObjectOutputStream} handshake and spawns a daemon reader
 * thread, which cannot be driven deterministically in a CPU-only unit test without flakiness.</p>
 *
 * @author Michael Murray
 */
public class NetworkClientTest extends TestSuiteBase {

	/**
	 * Reserves and then immediately releases a loopback port, so a subsequent connection to it is
	 * very likely to find nothing listening.
	 *
	 * @return a port number that was free at the moment of release
	 * @throws IOException if a free port cannot be reserved
	 */
	private static int reserveThenReleasePort() throws IOException {
		try (ServerSocket probe = new ServerSocket(0)) {
			return probe.getLocalPort();
		}
	}

	/**
	 * Connecting to a loopback port with no server listening must surface as an {@link IOException}
	 * from the constructor, per its documented contract.
	 */
	@Test(timeout = 20000)
	public void constructorThrowsIOExceptionWhenConnectionRefused() throws Exception {
		int closedPort = reserveThenReleasePort();

		boolean threw = false;
		try {
			new NetworkClient("127.0.0.1", closedPort);
		} catch (IOException e) {
			threw = true;
		}

		Assert.assertTrue("connecting to a port with no listener must throw IOException", threw);
	}
}
