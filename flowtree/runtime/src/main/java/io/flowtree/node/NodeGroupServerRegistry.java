/*
 * Copyright 2018 Michael Murray
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

import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.Connection;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.ConsoleFeatures;

import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The server-connection layer of a {@link NodeGroup}: the live socket-level
 * links to remote Servers, each wrapped in a {@link NodeProxy}.
 *
 * <p>This is the outer of the two networking layers described by
 * {@link NodeGroup}.  It carries {@link Message} objects between Servers and is
 * what a peer {@link Connection} between two individual Nodes is built on top
 * of — {@link #getConnection(int)} selects one of these links and negotiates a
 * peer connection over it.</p>
 *
 * <h2>Locking</h2>
 * <p><b>Writes</b> run under the monitor of the owning {@link NodeGroup}, not
 * the registry's own.  The group's {@code synchronized} methods are the entry
 * points, and every path that leads back into the registry from a callback or
 * a background thread — the persistent-host thread, the initial connections
 * opened by {@link #open(Properties, int)} — re-enters through those methods
 * so that all mutation contends for a single lock.  Do not add
 * {@code synchronized} to the methods here: it would lock the registry's own
 * monitor, which no writer holds, and the two would not exclude each other.</p>
 *
 * <p><b>Reads take no lock at all</b>, and must not.  The collections are
 * copy-on-write, which is what makes an unlocked read consistent rather than
 * racy.  Reading under the group's monitor instead would be a deadlock
 * hazard: {@link NodeProxy#fireConnect()} and {@code fireDisconnect()} invoke
 * listener callbacks while holding the proxy's listener monitor, and those
 * callbacks are {@link NodeGroup#connect(NodeProxy)} and
 * {@link NodeGroup#disconnect(NodeProxy)}, which want the group's monitor —
 * while {@link #addServer(NodeProxy)} holds the group's monitor and calls
 * back into the proxy for exactly that listener monitor.  Any read that
 * acquired the group's monitor from a proxy callback thread would close that
 * cycle.</p>
 *
 * @author  Michael Murray
 * @see NodeGroup
 * @see NodeProxy
 */
public class NodeGroupServerRegistry implements ConsoleFeatures {
	/**
	 * Name of the thread started by {@link #startPersistentHost(String, int)},
	 * which reconnects to the root server whenever no connections remain.
	 */
	public static final String PERSISTENT_HOST_THREAD = "Persistent Host Attempt";

	/** The group that owns these connections. */
	private final NodeGroup group;

	/**
	 * Live socket-level connections to remote servers, each wrapped in a
	 * {@link NodeProxy} that handles message framing and optional encryption.
	 *
	 * <p>Copy-on-write: writers are already serialized by the group's monitor,
	 * and readers reach this from threads that must not take that monitor.</p>
	 */
	private final List<NodeProxy> servers = new CopyOnWriteArrayList<>();

	/**
	 * Proxies currently being initialised inside {@link #addServer(NodeProxy)}.
	 * A proxy is present in this list from the moment it enters that method until
	 * initialisation completes, so that re-entrant callbacks (e.g.
	 * {@link NodeGroup#connect(NodeProxy)}) can skip still-pending proxies.
	 *
	 * <p>Copy-on-write: {@link NodeGroup#connect(NodeProxy)} reads it without
	 * the group's monitor, which is the case this list exists to serve.</p>
	 */
	private final List<NodeProxy> connecting = new CopyOnWriteArrayList<>();

	/**
	 * External {@link NodeProxy.EventListener} instances that must be notified
	 * whenever a server connection is removed from this group.
	 *
	 * <p>Copy-on-write: registration happens without the group's monitor while
	 * {@link #removeServer(NodeProxy)} iterates under it.</p>
	 */
	private final List<NodeProxy.EventListener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Password used to authenticate and/or encrypt communication with remote
	 * servers via {@link NodeProxy}. {@code null} means no authentication.
	 */
	private final char[] passwd;

	/**
	 * Name of the symmetric encryption algorithm applied to server communication,
	 * as understood by {@link NodeProxy}. {@code null} means no encryption.
	 */
	private final String crypt;

	/**
	 * Maximum number of simultaneous connections permitted to the same remote
	 * endpoint before the oldest duplicate is dropped.
	 */
	private final int maxDuplicateConnections = 2;

	/**
	 * @param group   The group that owns these connections.
	 * @param passwd  Password for authenticating remote servers, or {@code null}.
	 * @param crypt   Symmetric encryption algorithm name, or {@code null}.
	 */
	public NodeGroupServerRegistry(NodeGroup group, char[] passwd, String crypt) {
		this.group = group;
		this.passwd = passwd;
		this.crypt = crypt;
	}

	/**
	 * Opens the initial server connections specified in {@code p} and wires up
	 * the persistent-host reconnect thread when the {@code FLOWTREE_ROOT_HOST}
	 * environment variable is set.
	 *
	 * <p>When {@link NodeGroupNodeConfig#OFFLINE_MODE_PROPERTY}
	 * ({@code flowtree.offline}) is {@code true} the {@code FLOWTREE_ROOT_HOST}
	 * connection is suppressed so that tests cannot accidentally contact a live
	 * production controller.  Explicitly configured {@code servers.N.host}
	 * entries are still opened; they are test-internal peers, not production
	 * endpoints.</p>
	 *
	 * @param p            Properties to read server host/port entries from.
	 * @param serverCount  Number of server entries to open.
	 */
	public void open(Properties p, int serverCount) {
		if (NodeGroupNodeConfig.isOfflineMode()) {
			log("Offline mode active — skipping environment-provided root-host connection.");
		} else {
			String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
			String rootPort = System.getenv("FLOWTREE_ROOT_PORT");

			if (rootHost != null) {
				if (rootPort == null) rootPort = String.valueOf(Server.defaultPort);
				startPersistentHost(rootHost, Integer.parseInt(rootPort));
			}
		}

		if (serverCount > 0) log("Opening server connections...");

		for (int i = 0; i < serverCount; i++) {
			String host = p.getProperty("servers." + i + ".host", "localhost");
			int port = Integer.parseInt(p.getProperty("servers." + i + ".port", "7777"));

			try {
				log("Connecting to server " + i + " (" + host + ":" + port + ")...");
				group.addServer(new Socket(host, port));
			} catch (UnknownHostException uh) {
				warn("Server " + i + " is unknown host", null);
			} catch (IOException ioe) {
				warn("IO error while connecting to server " + i + " -- " + ioe.getMessage(), ioe);
			} catch (SecurityException se) {
				warn("Security exception while connecting to server " + i +
						" (" + se.getMessage() + ")", se);
			}
		}
	}

	/**
	 * Wraps the supplied socket in a {@link NodeProxy} and registers it as a
	 * server connection. The {@code server} flag controls how the NodeProxy
	 * performs the initial handshake (client-side vs. server-side role).
	 * Encryption and authentication errors are caught and logged; in those cases
	 * the method returns {@code false} without throwing.
	 *
	 * @param s       Socket connected to the remote server.
	 * @param server  {@code true} if this side initiated the listen socket
	 *                (server role in the handshake).
	 * @return {@code true} if the proxy was successfully added;
	 *         {@code false} if a cryptographic error prevented proxy creation.
	 * @throws IOException  If an I/O error occurs while constructing the proxy.
	 */
	public boolean addServer(Socket s, boolean server) throws IOException {
		try {
			return this.addServer(new NodeProxy(s, this.passwd, this.crypt, server));
		} catch (InvalidKeyException e) {
			warn("Invalid key (" + e.getMessage() + ").");
		} catch (NoSuchAlgorithmException e) {
			warn("Encryption algorithm not found (" + e.getMessage() + ").");
		} catch (InvalidKeySpecException e) {
			warn("Invalid key spec (" + e.getMessage() + ").");
		} catch (NoSuchPaddingException e) {
			warn("Encryption padding not found (" + e.getMessage() + ").");
		} catch (InvalidAlgorithmParameterException e) {
			warn("Invalid encryption parameter (" + e.getMessage() + ").");
		}

		return false;
	}

	/**
	 * Registers a fully constructed {@link NodeProxy} as a live server connection.
	 * If the same remote endpoint already has {@link #maxDuplicateConnections}
	 * entries the oldest duplicate is removed first. All registered
	 * {@link NodeProxy.EventListener}s (including task factories that implement
	 * the interface) are wired to the new proxy, and the proxy's queued messages
	 * are flushed immediately.
	 *
	 * @param pr  The proxy to register.
	 * @return Always {@code true} once the proxy is successfully registered.
	 */
	public boolean addServer(NodeProxy pr) {
		this.connecting.add(pr);

		int d = 0;
		NodeProxy p = null;

		for (NodeProxy np : this.servers) {
			if (np.equals(pr)) {
				d++;

				if (d == 1) p = np;
			}
		}

		if (d >= this.maxDuplicateConnections) {
			group.removeServer(p);
			group.displayMessage("Removed duplicate server " + p);
		}

		pr.addEventListener(group);

		for (JobFactory f : group.getTasksCopy()) {
			if (f instanceof NodeProxy.EventListener) {
				pr.addEventListener((NodeProxy.EventListener) f);
			}
		}

		pr.fireConnect();
		this.servers.add(pr);

		String msg = "Added server " + (this.servers.size() - 1);
		group.displayMessage(msg + " - " + pr);
		group.getStatusRenderer().addActivityMessage(msg);

		pr.flushQueue();

		this.connecting.remove(pr);

		return true;
	}

	/**
	 * Removes and disposes the connection maintained by the specified NodeProxy object.
	 *
	 * @param p  NodeProxy maintaing connection that is to be removed.
	 * @return  The total number of node connections dropped due to the removal.
	 */
	public int removeServer(NodeProxy p) {
		p.removeEventListener(group);

		int tot = 0;

		for (Node n : group.nodes()) {
			tot += n.disconnect(p);
		}

		boolean r = this.servers.remove(p);

		if (tot > 0)
			group.displayMessage("Dropped " + tot + " connections to " + p);
		else if (r)
			group.displayMessage("Dropped server " + p);

		for (NodeProxy.EventListener l : this.listeners) {
			l.disconnect(p);
		}

		if (p.isConnected()) p.close();

		return tot;
	}

	/**
	 * Starts a background daemon thread that monitors the server list and
	 * reconnects to the specified host whenever no active server connections
	 * remain. The thread waits 30 seconds between each connection attempt to
	 * avoid tight reconnect loops. This is the mechanism used when the
	 * {@code FLOWTREE_ROOT_HOST} environment variable is set.
	 *
	 * @param host  Hostname or IP address of the root server.
	 * @param port  TCP port of the root server.
	 */
	public void startPersistentHost(String host, int port) {
		Thread t = new Thread(() -> {
			w: while (true) {
				try {
					Thread.sleep(30 * 1000L);
				} catch (InterruptedException e) {
					warn(e.getMessage(), e);
					return;
				}

				if (getServers().length > 0)
					continue w;

				log("Connecting to root server...");

				try {
					group.addServer(new Socket(host, port));
				} catch (UnknownHostException uh) {
					warn("Server " + host + " is unknown host");
				} catch (IOException ioe) {
					warn("IO error while connecting to server " +
							host + " -- " + ioe.getMessage());
				} catch (SecurityException se) {
					warn("Security exception while connecting to server " + host +
							" (" + se.getMessage() + ")");
				}
			}
		}, PERSISTENT_HOST_THREAD);

		// The loop never terminates on its own, so leaving the thread
		// non-daemon would keep the JVM alive after everything else has stopped
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Returns a snapshot array of all currently registered server proxies.
	 * The array is a copy, so it is safe to iterate after the call returns.
	 *
	 * <p>No lock is taken: the backing list is copy-on-write, so the snapshot
	 * is consistent whatever a writer is doing concurrently.  See the class
	 * documentation for why a read here must not take the group's monitor.</p>
	 *
	 * @return  Array of active {@link NodeProxy} connections; never {@code null}.
	 */
	public NodeProxy[] getServers() {
		return this.servers.toArray(new NodeProxy[0]);
	}

	/**
	 * Returns the proxy registered at the specified index.
	 *
	 * <p>An index is only meaningful for as long as the registry is unchanged,
	 * so callers must hold the owning group's monitor across the bounds check
	 * and this call.  Every caller reaches it through a {@code synchronized}
	 * method of {@link NodeGroup}, which is what makes the pair atomic.</p>
	 *
	 * @param index  Index of the connection.
	 * @return  The proxy maintaining that connection.
	 */
	public NodeProxy get(int index) { return this.servers.get(index); }

	/**
	 * Returns the number of registered server connections.
	 *
	 * <p>Read without the group's monitor, so the count is advisory unless the
	 * caller holds it: a connection may be added or dropped immediately after
	 * the value is returned.  Callers that act on a specific index must hold
	 * the monitor across both calls — see {@link #get(int)}.</p>
	 *
	 * @return  The connection count.
	 */
	public int size() { return this.servers.size(); }

	/**
	 * Returns {@code true} if the specified proxy is still being initialised by
	 * {@link #addServer(NodeProxy)} and should not be registered again.
	 *
	 * @param pr  The proxy to test.
	 * @return  {@code true} if initialisation of {@code pr} is in progress.
	 */
	public boolean isConnecting(NodeProxy pr) { return this.connecting.contains(pr); }

	/**
	 * Registers an external listener that will be notified when a server
	 * connection is removed from this group.
	 *
	 * @param l  Listener to register.
	 */
	public void addEventListener(NodeProxy.EventListener l) { this.listeners.add(l); }

	/**
	 * Selects a server at random and sends a connection request.
	 * This method may return null.
	 *
	 * @param id  Unique id of the child node that is requesting the connection.
	 * @return  A Connection object that can be used to relay data between a local node and a remote node.
	 */
	public Connection getConnection(int id) {
		NodeProxy p;

		while (true) {
			if (this.servers.size() < 1) return null;

			int s = (int) (Math.random() * this.servers.size());
			p = this.servers.get(s);

			if (p.isConnected())
				break;
			else
				group.removeServer(p);
		}

		Connection c = null;

		try {
			Message m = new Message(Message.ConnectionRequest, id, p);
			m.setLocalNode(group.getNode(id));
			c = (Connection) m.send(-1);
		} catch (SocketException se) {
			group.displayMessage("Removing server " + p + " (" + se.getMessage() + ")");
			group.removeServer(p);
		} catch (IOException ioe) {
			warn(String.valueOf(ioe));
			return null;
		}

		return c;
	}
}
