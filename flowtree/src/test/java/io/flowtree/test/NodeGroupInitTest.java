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

package io.flowtree.test;

import io.flowtree.Server;
import io.flowtree.node.NodeGroupNodeConfig;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

/**
 * Tests that {@link io.flowtree.node.NodeGroup} initialises correctly
 * with various node counts and configuration properties.
 *
 * <p>These tests guard against initialisation-order bugs where fields
 * like {@code nodeConfig} must be set before {@code setParam(Properties)}
 * is called in the constructor.</p>
 */
public class NodeGroupInitTest extends ServerTestBase {

	/**
	 * Regression guard: fails if offline mode is not active.
	 *
	 * <p>If this test fails it means {@link #enforceOfflineMode()} did not run
	 * before a {@link Server} was constructed, which would allow the test
	 * Server to connect to production.</p>
	 */
	@Test(timeout = 5000)
	public void offlineModeIsActiveForServerTests() {
		Assert.assertTrue(
				"Tests that create Server instances must run with flowtree.offline=true. " +
				"The @BeforeClass enforceOfflineMode() must execute before any Server is constructed.",
				NodeGroupNodeConfig.isOfflineMode());
	}

	/**
	 * Verifies that a {@link Server} with one initial node can be
	 * constructed without a {@link NullPointerException} during
	 * parameter application.
	 */
	@Test(timeout = 10000)
	public void constructWithOneNode() throws IOException {
		Properties p = new Properties();
		p.setProperty("server.port", "7720");
		p.setProperty("nodes.initial", "1");
		p.setProperty("servers.total", "0");

		Server server = new Server(p);
		Assert.assertNotNull("Server should be constructed", server);
		server.stop();
	}

	/**
	 * Verifies that construction succeeds with multiple nodes and
	 * configuration properties that exercise {@code setParam}.
	 */
	@Test(timeout = 10000)
	public void constructWithMultipleNodesAndParams() throws IOException {
		Properties p = new Properties();
		p.setProperty("server.port", "7721");
		p.setProperty("nodes.initial", "3");
		p.setProperty("servers.total", "0");
		p.setProperty("node.relay.probability", "0.5");
		p.setProperty("node.activity.sleep.c", "200");

		Server server = new Server(p);
		Assert.assertNotNull("Server should be constructed", server);
		server.stop();
	}

	/**
	 * Verifies that the zero-node case still works (relay-only configuration
	 * used by the FlowTree controller).
	 */
	@Test(timeout = 10000)
	public void constructWithZeroNodes() throws IOException {
		Properties p = new Properties();
		p.setProperty("server.port", "7722");
		p.setProperty("nodes.initial", "0");
		p.setProperty("servers.total", "0");

		Server server = new Server(p);
		Assert.assertNotNull("Server should be constructed", server);
		server.stop();
	}
}
