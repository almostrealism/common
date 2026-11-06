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

import io.almostrealism.db.Query;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.NodeGroup;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Properties;

/**
 * Verifies that {@link ServerQueryExecutor} relays an aggregated query across
 * peers resiliently: a single peer whose write fails must not discard the
 * results of the healthy peers that follow it.
 *
 * <p>{@link NodeProxy#writeObject(Object, int)} now rethrows a
 * {@link SocketException} after disconnecting, so a relayed job learns the peer
 * never received it. That same rethrow previously aborted the query fan-out at
 * the first broken peer; these tests drive {@link Server#executeQuery(Query, long)}
 * through a mix of broken and healthy peer proxies and assert that every healthy
 * peer's contribution is still collected regardless of where the broken peer
 * sits in the list.</p>
 *
 * @author Michael Murray
 */
public class ServerQueryExecutorRelayTest extends TestSuiteBase {

	/** A broken peer at the head of the list must not cost the healthy peer's result. */
	@Test(timeout = 30000)
	public void brokenPeerFirstStillCollectsHealthyResult() throws Exception {
		Message reply = stringMessage("healthy-result");
		NodeProxy broken = brokenPeer();
		NodeProxy healthy = healthyPeer(reply);

		String result = relayQuery(broken, healthy);

		Assert.assertEquals("the healthy peer's result survives a leading broken peer",
				"healthy-result", result);
	}

	/** A broken peer after a healthy one must not discard the already-collected result. */
	@Test(timeout = 30000)
	public void brokenPeerLastStillCollectsHealthyResult() throws Exception {
		Message reply = stringMessage("healthy-result");
		NodeProxy healthy = healthyPeer(reply);
		NodeProxy broken = brokenPeer();

		String result = relayQuery(healthy, broken);

		Assert.assertEquals("a trailing broken peer does not discard the healthy result",
				"healthy-result", result);
	}

	/** Two healthy peers around a broken one both contribute, joined by the separator. */
	@Test(timeout = 30000)
	public void brokenPeerBetweenHealthyPeersKeepsBoth() throws Exception {
		NodeProxy first = healthyPeer(stringMessage("alpha"));
		NodeProxy broken = brokenPeer();
		NodeProxy second = healthyPeer(stringMessage("beta"));

		String result = relayQuery(first, broken, second);

		Assert.assertEquals("both healthy peers contribute around the broken one",
				"alpha" + Query.sep + "beta", result);
	}

	/**
	 * Runs a relay query across the given peers and returns the aggregated data.
	 *
	 * @param peers the peer proxies the server reports as connected
	 * @return the aggregated query result string
	 * @throws IOException if the server cannot be constructed
	 */
	private String relayQuery(NodeProxy... peers) throws IOException {
		Server server = serverWithPeers(peers);
		try {
			Query q = new Query();
			q.setRelay(1);
			Message result = server.executeQuery(q, 50);
			return result.getData();
		} finally {
			server.stop();
		}
	}

	/**
	 * Builds a {@link Server} that reports the given peers as connected and
	 * binds no listening socket.
	 *
	 * @param peers the peers the server's node group reports
	 * @return the server double
	 * @throws IOException if the server cannot be constructed
	 */
	private Server serverWithPeers(NodeProxy... peers) throws IOException {
		Properties p = ephemeralProperties();
		return new Server(p) {
			private final NodeGroup group = new NodeGroup(ephemeralProperties(), this) {
				@Override
				public NodeProxy[] getServers() { return peers; }
			};

			@Override
			public NodeGroup getNodeGroup() { return group; }
		};
	}

	/** Properties that skip socket binding and start no child nodes. */
	private static Properties ephemeralProperties() {
		Properties p = new Properties();
		p.setProperty("server.port", "0");
		p.setProperty("nodes.initial", "0");
		p.setProperty("servers.total", "0");
		return p;
	}

	/**
	 * Builds a {@link Message#StringMessage} carrying the given data.
	 *
	 * @param data the message body
	 * @return the message
	 * @throws IOException never, but declared by the {@link Message} constructor
	 */
	private static Message stringMessage(String data) throws IOException {
		Message m = new Message(Message.StringMessage, -1);
		m.setString(data);
		return m;
	}

	/**
	 * Creates a peer proxy whose write fails with a {@link SocketException}, as a
	 * peer whose socket has been reset would.
	 *
	 * @return the broken peer proxy
	 * @throws Exception if the proxy cannot be constructed
	 */
	private static NodeProxy brokenPeer() throws Exception {
		return new NodeProxy(new HeaderOnlySocket()) {
			@Override
			public void writeObject(Object o, int id) throws IOException {
				throw new SocketException("Connection reset by peer");
			}
		};
	}

	/**
	 * Creates a peer proxy whose write succeeds and which replies with the given
	 * message.
	 *
	 * @param reply the message returned from {@link NodeProxy#waitForMessage}
	 * @return the healthy peer proxy
	 * @throws Exception if the proxy cannot be constructed
	 */
	private static NodeProxy healthyPeer(Message reply) throws Exception {
		return new NodeProxy(new HeaderOnlySocket()) {
			@Override
			public void writeObject(Object o, int id) { }

			@Override
			public Object waitForMessage(int type, String data, long timeout) { return reply; }
		};
	}

	/**
	 * A socket whose output discards writes and whose input carries only an
	 * object-stream header, so the proxy's reader thread finds nothing to read
	 * and the write path is driven entirely by the overridden proxy methods.
	 */
	private static final class HeaderOnlySocket extends Socket {
		/** Reports the loopback address, which the proxy uses as its label. */
		@Override
		public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }

		/**
		 * @return an output stream that silently discards everything written
		 */
		@Override
		public OutputStream getOutputStream() {
			return new OutputStream() {
				@Override
				public void write(int b) { }

				@Override
				public void write(byte[] b, int off, int len) { }
			};
		}

		/**
		 * @return an input stream holding just a valid object-stream header
		 * @throws IOException never, but declared by the superclass
		 */
		@Override
		public InputStream getInputStream() throws IOException {
			ByteArrayOutputStream header = new ByteArrayOutputStream();
			new ObjectOutputStream(header).close();
			return new ByteArrayInputStream(header.toByteArray());
		}
	}
}
