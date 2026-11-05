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

package io.flowtree.node;

import io.flowtree.job.Job;
import io.flowtree.msg.Connection;
import io.flowtree.msg.NodeProxy;
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
import java.util.concurrent.CompletableFuture;

/**
 * Deterministic reproduction of the relay job-loss bug and verification of the
 * requeue fix.
 *
 * <p>The relay loop used to take a job off the queue with {@link Node#nextJob()}
 * and then hand it to a peer with {@link Connection#sendJob(Job)}; when that send
 * threw — a {@link SocketException} from a peer that had gone away, or an
 * {@link IOException} from a connection with no proxy — the job was silently
 * dropped. Submitting several jobs at once made such a failing send far more
 * likely, which is exactly when jobs disappeared in production. These tests drive
 * {@link Node#relayJobToPeer(Connection)} directly against connections that fail
 * the send, and assert that the job is returned to the head of the queue rather
 * than lost, while a successful send still removes it.</p>
 *
 * <p>The node is built standalone ({@code parent == null}), which leaves its
 * worker thread unstarted, so the queue is observed exactly as the relay loop
 * leaves it rather than being drained by a competing worker.</p>
 *
 * @author Michael Murray
 */
public class NodeRelayRequeueTest extends TestSuiteBase {

	/** A failed send must return the job to the head of the queue, not drop it. */
	@Test(timeout = 10000)
	public void socketExceptionRequeuesJobAtHead() throws IOException {
		Node node = new Node(null, 0, 4, 4);
		QueuedJob a = new QueuedJob("a");
		QueuedJob b = new QueuedJob("b");
		node.addJob(a);
		node.addJob(b);

		FailingConnection peer = new FailingConnection(node, new SocketException("peer gone"));
		node.connect(peer);
		Assert.assertEquals("the peer was connected", 1, node.getPeers().length);

		boolean relayed = node.relayJobToPeer(peer);

		Assert.assertFalse("send threw, so no job was handed off", relayed);
		Assert.assertEquals("the failing peer was dropped", 0, node.getPeers().length);
		Assert.assertSame("the un-sent job is back at the head of the queue", a, node.nextJob());
		Assert.assertSame("the second job is untouched behind it", b, node.nextJob());
		Assert.assertNull("no phantom extra jobs were created", node.nextJob());
	}

	/** An IOException on a proxy-less connection must also requeue, not drop. */
	@Test(timeout = 10000)
	public void ioExceptionRequeuesJob() throws IOException {
		Node node = new Node(null, 0, 4, 4);
		QueuedJob a = new QueuedJob("a");
		node.addJob(a);

		FailingConnection peer = new FailingConnection(node, new IOException("no proxy"));

		boolean relayed = node.relayJobToPeer(peer);

		Assert.assertFalse("send threw, so no job was handed off", relayed);
		Assert.assertSame("the un-sent job is still queued", a, node.nextJob());
		Assert.assertNull("exactly one job remained", node.nextJob());
	}

	/** A successful send removes the job from the queue (no double-dispatch). */
	@Test(timeout = 10000)
	public void successfulSendRemovesJob() throws IOException {
		Node node = new Node(null, 0, 4, 4);
		QueuedJob a = new QueuedJob("a");
		QueuedJob b = new QueuedJob("b");
		node.addJob(a);
		node.addJob(b);

		AcceptingConnection peer = new AcceptingConnection(node);

		boolean relayed = node.relayJobToPeer(peer);

		Assert.assertTrue("the send succeeded", relayed);
		Assert.assertTrue("the peer actually received the job", peer.received);
		Assert.assertSame("the sent job was removed; the next job is now at the head", b, node.nextJob());
		Assert.assertNull("the sent job is not still queued", node.nextJob());
	}

	/** Relaying from an empty queue is a no-op that reports nothing was sent. */
	@Test(timeout = 10000)
	public void emptyQueueRelaysNothing() throws IOException {
		Node node = new Node(null, 0, 4, 4);
		Assert.assertFalse(node.relayJobToPeer(new AcceptingConnection(node)));
	}

	/**
	 * A peer whose socket breaks mid-write must surface the failure through the
	 * real {@link NodeProxy} write path, so the job is requeued rather than
	 * reported as relayed. {@link NodeProxy#writeObject(Object, int, boolean)} used
	 * to swallow the {@link SocketException} after disconnecting, which made the
	 * relay believe the peer had the job.
	 */
	@Test(timeout = 10000)
	public void brokenSocketThroughRealProxyRequeuesJob() throws Exception {
		Node node = new Node(null, 0, 4, 4);
		QueuedJob a = new QueuedJob("a");
		node.addJob(a);

		BrokenSocket socket = new BrokenSocket();
		NodeProxy proxy = new NodeProxy(socket);
		// A new proxy buffers writes until registered; NodeGroupServerRegistry
		// releases that buffer once the server is added, and so does this test.
		proxy.flushQueue();
		Connection peer = new Connection(node, proxy, 7);
		node.connect(peer);
		socket.broken = true;

		boolean relayed = node.relayJobToPeer(peer);

		Assert.assertFalse("the write failed, so the job was not handed off", relayed);
		Assert.assertFalse("the proxy recorded the disconnect", proxy.isConnected());
		Assert.assertEquals("the broken peer was dropped", 0, node.getPeers().length);
		Assert.assertSame("the un-sent job is back in the queue", a, node.nextJob());
		Assert.assertNull("exactly one job remained", node.nextJob());
	}

	/**
	 * A socket whose output fails with a {@link SocketException} once
	 * {@link #broken} is set. Its input carries only an object-stream header, so
	 * the proxy's reader thread finds nothing to read and never disconnects on
	 * its own.
	 */
	private static final class BrokenSocket extends Socket {
		/** When {@code true}, every write throws as a reset connection would. */
		private volatile boolean broken;

		/** Reports the loopback address, which the proxy uses as its label. */
		@Override
		public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }

		/**
		 * @return an output stream that fails once the socket is broken
		 */
		@Override
		public OutputStream getOutputStream() {
			return new OutputStream() {
				@Override
				public void write(int b) throws IOException {
					if (broken) throw new SocketException("Connection reset by peer");
				}

				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					if (broken) throw new SocketException("Connection reset by peer");
				}
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

	/** A Connection whose {@link #sendJob} throws a supplied exception. */
	private static final class FailingConnection extends Connection {
		/** The exception every {@link #sendJob} call re-throws. */
		private final IOException failure;

		/**
		 * @param node    the local node owning this connection
		 * @param failure the exception {@link #sendJob} should throw
		 * @throws IOException never, but declared by the superclass constructor
		 */
		FailingConnection(Node node, IOException failure) throws IOException {
			super(node, null, 99);
			this.failure = failure;
		}

		/** Overridden to a no-op so the connection can be added without a proxy. */
		@Override
		public void start() { }

		/**
		 * Always fails, simulating a peer that has gone away.
		 *
		 * @param j the job that would have been sent
		 * @throws IOException always
		 */
		@Override
		public void sendJob(Job j) throws IOException {
			throw failure;
		}

		/** Avoids the superclass toString, which dereferences the (null) proxy. */
		@Override
		public String toString() { return "FailingConnection"; }
	}

	/** A Connection whose {@link #sendJob} succeeds and records the hand-off. */
	private static final class AcceptingConnection extends Connection {
		/** Set true once {@link #sendJob} has been called. */
		private boolean received;

		/**
		 * @param node the local node owning this connection
		 * @throws IOException never, but declared by the superclass constructor
		 */
		AcceptingConnection(Node node) throws IOException {
			super(node, null, 100);
		}

		/** Overridden to a no-op so the connection can be added without a proxy. */
		@Override
		public void start() { }

		/**
		 * Accepts the job and records that it was received.
		 *
		 * @param j the job handed to the peer
		 */
		@Override
		public void sendJob(Job j) {
			this.received = true;
		}

		/** Avoids the superclass toString, which dereferences the (null) proxy. */
		@Override
		public String toString() { return "AcceptingConnection"; }
	}

	/** A minimal queued {@link Job} identified only by its task id. */
	private static final class QueuedJob implements Job {
		/** The job's task identifier. */
		private String taskId;

		/** Completed when {@link #run()} is called. */
		private final CompletableFuture<Void> future = new CompletableFuture<>();

		/**
		 * @param taskId the task identifier for this job
		 */
		QueuedJob(String taskId) { this.taskId = taskId; }

		/** Marks the job complete. */
		@Override
		public void run() { future.complete(null); }

		/**
		 * Restores the task id during reflective decoding.
		 *
		 * @param key   property name
		 * @param value property value
		 */
		@Override
		public void set(String key, String value) {
			if ("id".equals(key)) this.taskId = value;
		}

		/**
		 * @return the encoded form carrying the task id
		 */
		@Override
		public String encode() {
			return QueuedJob.class.getName() + "::id:=" + taskId;
		}

		@Override
		public String getTaskId() { return taskId; }

		@Override
		public String getTaskString() { return "QueuedJob:" + taskId; }

		@Override
		public CompletableFuture<Void> getCompletableFuture() { return future; }
	}
}
