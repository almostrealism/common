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

import io.flowtree.test.ServerTestBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Verifies that {@link ServerStatusWriter#writeStatus(String)} renders the owning
 * {@link Server}'s status to a local HTML file. With no
 * {@link io.flowtree.fs.ResourceDistributionTask} active, the writer takes the
 * local-only branch and never touches the distributed file system, so the test can
 * assert on the file that lands on disk.
 *
 * @author Michael Murray
 */
public class ServerStatusWriterTest extends ServerTestBase {

	/**
	 * Builds a passive {@link Server} whose status can be rendered locally.
	 *
	 * @return a server that binds no socket and owns no peers
	 * @throws IOException if the server cannot be constructed
	 */
	private static Server renderableServer() throws IOException {
		Properties config = new Properties();
		config.setProperty("server.port", "-1");
		config.setProperty("nodes.initial", "0");
		config.setProperty("servers.total", "0");
		return new Server(config);
	}

	/** The status writer renders the server status, including log items, to {@code <file>-stat.html}. */
	@Test(timeout = 60000)
	public void writesStatusHtmlFileWithLogItems() throws IOException {
		Server server = renderableServer();
		server.addLogItem("Status Section", "diagnostic-body-text");

		ServerStatusWriter writer = new ServerStatusWriter(server);

		Path dir = Files.createTempDirectory("server-status-test");
		Path base = dir.resolve("server");
		Path output = dir.resolve("server-stat.html");

		try {
			writer.writeStatus(base.toString());

			Assert.assertTrue("status file should be written", Files.exists(output));
			String html = new String(Files.readAllBytes(output));
			Assert.assertTrue("status should include the log item title, got: " + html,
					html.contains("Status Section"));
			Assert.assertTrue("status should include the log item body, got: " + html,
					html.contains("diagnostic-body-text"));
		} finally {
			Files.deleteIfExists(output);
			Files.deleteIfExists(base);
			Files.deleteIfExists(dir);
		}
	}
}
