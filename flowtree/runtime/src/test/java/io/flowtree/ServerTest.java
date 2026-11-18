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

import io.almostrealism.db.DatabaseConnection;
import io.almostrealism.db.Query;
import io.almostrealism.resource.IOStreams;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.Message;
import io.flowtree.test.ServerTestBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Properties;

/**
 * Exercises the CPU-only public contract of {@link Server}: a passive server that
 * binds no listening socket (port disabled), starts no peer connections, and owns
 * no live nodes. These tests pin the behaviour of the many small accessors,
 * parameter-handling branches, cache delegators, and resource/query entry points
 * that can be driven without any network, database, or output server present.
 *
 * @author Michael Murray
 */
public class ServerTest extends ServerTestBase {

	/**
	 * Builds a passive {@link Server}: no listening socket, no initial nodes, and
	 * no eager peer connections, so the instance is entirely CPU-local.
	 *
	 * @return the passive server under test
	 * @throws IOException if the server cannot be constructed
	 */
	private static Server passiveServer() throws IOException {
		Properties p = new Properties();
		p.setProperty("server.port", "-1");
		p.setProperty("nodes.initial", "0");
		p.setProperty("servers.total", "0");
		return new Server(p);
	}

	/** A freshly constructed passive server reports the documented JobFactory defaults. */
	@Test(timeout = 60000)
	public void jobFactoryDefaults() throws IOException {
		Server server = passiveServer();

		Assert.assertEquals("Server", server.getName());
		Assert.assertEquals(0.0, server.getCompleteness(), 0.0);
		Assert.assertFalse(server.isComplete());
		Assert.assertNull(server.getTaskId());
		Assert.assertNull(server.getCompletableFuture());
		Assert.assertEquals(Server.class.getName(), server.encode());
	}

	/** The job priority is 0 by default and round-trips through the setter. */
	@Test(timeout = 60000)
	public void priorityRoundTrips() throws IOException {
		Server server = passiveServer();

		Assert.assertEquals(0.0, server.getPriority(), 0.0);
		server.setPriority(3.5);
		Assert.assertEquals(3.5, server.getPriority(), 0.0);
	}

	/** {@link Server#getObject(String)} returns the server itself for the key {@code "server"}. */
	@Test(timeout = 60000)
	public void getObjectServerReturnsThis() throws IOException {
		Server server = passiveServer();
		Assert.assertSame(server, server.getObject("server"));
	}

	/** The {@code server.hostname} parameter overrides the reported local address. */
	@Test(timeout = 60000)
	public void hostnameParameterOverridesLocalAddress() throws IOException {
		Server server = passiveServer();

		Assert.assertTrue(server.setParam("server.hostname", "example-host"));
		Assert.assertEquals("example-host", server.getLocalSocketAddress());
	}

	/** An unrecognised parameter name is rejected with {@code false}. */
	@Test(timeout = 60000)
	public void unknownParameterIsRejected() throws IOException {
		Server server = passiveServer();
		Assert.assertFalse(server.setParam("no.such.parameter", "value"));
	}

	/** The {@code db.verbose} parameter is recognised and toggles the database flag. */
	@Test(timeout = 60000)
	public void dbVerboseParameterTogglesFlag() throws IOException {
		Server server = passiveServer();
		boolean original = DatabaseConnection.verbose;
		try {
			Assert.assertTrue(server.setParam("db.verbose", "true"));
			Assert.assertTrue(DatabaseConnection.verbose);
			Assert.assertTrue(server.setParam("db.verbose", "false"));
			Assert.assertFalse(DatabaseConnection.verbose);
		} finally {
			DatabaseConnection.verbose = original;
		}
	}

	/** An output-host parameter is rejected when no {@link io.flowtree.node.Client} is current. */
	@Test(timeout = 60000)
	public void outputHostParameterRejectedWithoutClient() throws IOException {
		Server server = passiveServer();
		Assert.assertFalse(server.setParam("servers.output.host", "some-host"));
	}

	/** {@link Server#setParam(Properties)} applies every recognised entry in the bundle. */
	@Test(timeout = 60000)
	public void setParamAppliesWholeBundle() throws IOException {
		Server server = passiveServer();

		Properties bundle = new Properties();
		bundle.setProperty("server.hostname", "bundle-host");
		server.setParam(bundle);

		Assert.assertEquals("bundle-host", server.getLocalSocketAddress());
	}

	/** A passive server with no peers reports an empty peer list. */
	@Test(timeout = 60000)
	public void peerListEmptyWithNoPeers() throws IOException {
		Server server = passiveServer();
		Assert.assertEquals(0, server.getPeers().length);
	}

	/** With no peers the average peer job time is zero. */
	@Test(timeout = 60000)
	public void averagePeerJobTimeZeroWithNoPeers() throws IOException {
		Server server = passiveServer();
		Assert.assertEquals(0.0, server.getAveragePeerJobTime(), 0.0);
	}

	/** The status message encodes both the job time and the activity rating. */
	@Test(timeout = 60000)
	public void statusMessageEncodesJobTimeAndActivity() throws IOException {
		Server server = passiveServer();
		Message m = server.getStatusMessage();
		String data = m.getData();

		Assert.assertTrue("expected jobtime field, got: " + data, data.startsWith("jobtime:"));
		Assert.assertTrue("expected activity field, got: " + data, data.contains(";activity:"));
	}

	/** Start time is set at construction and uptime is non-negative. */
	@Test(timeout = 60000)
	public void startTimeAndUptimeAreConsistent() throws IOException {
		Server server = passiveServer();
		Assert.assertTrue(server.getStartTime() > 0);
		Assert.assertTrue(server.getUptime() >= 0);
	}

	/** The cache defaults to ten entries and honours {@link Server#setMaxCache(int)}. */
	@Test(timeout = 60000)
	public void cacheSizeDefaultsAndUpdates() throws IOException {
		Server server = passiveServer();

		Assert.assertEquals(10, server.getMaxCache());
		server.setMaxCache(25);
		Assert.assertEquals(25, server.getMaxCache());

		Assert.assertFalse(server.cacheContains("resource:absent"));
		Assert.assertNull(server.loadFromCache("resource:absent"));
	}

	/** Directory queries return the no-task sentinels when no distribution task is active. */
	@Test(timeout = 60000)
	public void directoryQueriesReturnNoTaskSentinels() throws IOException {
		Server server = passiveServer();
		Assert.assertFalse(server.isDirectory("/files/anything"));
		Assert.assertNull(server.getChildren("/files/anything"));
	}

	/** A direct resource stream to localhost or an empty host is refused with {@code null}. */
	@Test(timeout = 60000)
	public void resourceStreamToLocalhostIsNull() throws IOException {
		Server server = passiveServer();
		Assert.assertNull(server.getResourceStream("localhost", 7767, "/x"));
		Assert.assertNull(server.getResourceStream("", 7767, "/x"));
		Assert.assertNull(server.getResourceStream((String) null, 7767, "/x"));
	}

	/** With no peers a distributed resource stream request yields {@code null}. */
	@Test(timeout = 60000)
	public void resourceStreamWithNoPeersIsNull() throws IOException {
		Server server = passiveServer();
		Assert.assertNull(server.getResourceStream("resource:/some/uri"));
	}

	/** No resource URI is produced when the embedded resource server is disabled. */
	@Test(timeout = 60000)
	public void resourceUriNullWhenResourceServerDisabled() throws IOException {
		Server server = passiveServer();
		Assert.assertNull(server.getResourceUri("/some/path"));
	}

	/** Parsing a {@code resource://localhost/...} URI resolves to a local, hence null, stream. */
	@Test(timeout = 60000)
	public void parseResourceUriLocalhostResolvesToNull() throws IOException {
		Server server = passiveServer();
		IOStreams io = server.parseResourceUri("resource://localhost/files/a.txt");
		Assert.assertNull(io);
	}

	/** An unresolvable class name produces a null job rather than throwing. */
	@Test(timeout = 60000)
	public void createJobReturnsNullForUnknownClass() throws IOException {
		Server server = passiveServer();
		Assert.assertNull(server.createJob("no.such.JobClass::key:=value"));
	}

	/** The server owns a dedicated, named thread group. */
	@Test(timeout = 60000)
	public void threadGroupIsNamed() throws IOException {
		Server server = passiveServer();
		Assert.assertEquals("Network Server", server.getThreadGroup().getName());
	}

	/**
	 * A query with no relay and no output server present returns an empty aggregated
	 * result instead of throwing.
	 */
	@Test(timeout = 60000)
	public void executeQueryWithoutDatabaseReturnsEmptyResult() throws IOException {
		Server server = passiveServer();
		Query q = new Query();
		Message m = server.executeQuery(q);
		Assert.assertEquals("", m.getData());
	}

	/** Added log items are rendered into the printed status HTML. */
	@Test(timeout = 60000)
	public void printStatusIncludesLogItems() throws IOException {
		Server server = passiveServer();
		server.addLogItem("Build Log", "line-one-content");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		server.printStatus(new PrintStream(bytes));
		String html = bytes.toString();

		Assert.assertTrue("expected the log item title, got: " + html, html.contains("Build Log"));
		Assert.assertTrue("expected the log item body, got: " + html, html.contains("line-one-content"));
	}

	/** The server encodes as its own {@link JobFactory} class name. */
	@Test(timeout = 60000)
	public void serverIsItsOwnJobFactory() throws IOException {
		Server server = passiveServer();
		Assert.assertTrue(server instanceof JobFactory);
		Assert.assertEquals(Server.class.getName(), server.encode());
	}
}
