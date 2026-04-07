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
import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.Connection;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.RSSFeed;
import org.almostrealism.util.Chart;

import javax.crypto.NoSuchPaddingException;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A {@link NodeGroup} manages a collection of child {@link Node}s and
 * their connections to remote Servers.
 *
 * <h2>Two Networking Layers</h2>
 * <ul>
 *   <li><b>Server connections</b> ({@link NodeProxy}, stored in
 *       {@code this.servers}) are socket-level links between two
 *       Servers. They carry {@link Message} objects (tasks, connection
 *       requests, job data). When an agent connects to the controller,
 *       a NodeProxy is added to this list.</li>
 *   <li><b>Peer connections</b> ({@link Connection}, stored in each
 *       {@code Node.peers}) are logical links between individual Nodes
 *       on different Servers. They wrap a NodeProxy and target a
 *       specific remote Node by ID. Peer connections are how jobs
 *       actually move between Nodes via the relay loop.</li>
 * </ul>
 *
 * <h2>Job Distribution</h2>
 * <p>The run loop iterates registered {@link JobFactory} instances,
 * calls {@code nextJob()} to produce {@link Job} objects, and hands
 * each job to {@link #routeJob(Job)}, which routes to a label-matching worker
 * Node or falls back to the relay Node if none is available locally.</p>
 *
 * <h2>Peer Connection Establishment</h2>
 * <p>Child Nodes request peer connections automatically through their
 * activity threads by calling {@link #getConnection(int)}, which picks
 * a random entry from {@code this.servers} and sends a
 * {@link Message#ConnectionRequest}. The remote NodeGroup responds with
 * a {@link Message#ConnectionConfirmation}, establishing a peer
 * {@link Connection} on both sides.</p>
 *
 * @author  Michael Murray
 * @see Node
 * @see Connection
 * @see <a href="../docs/node-relay.md">Node Relay and Job Routing</a>
 */
public class NodeGroup extends Node implements Runnable, NodeProxy.EventListener,
														Node.ActivityListener {
	/**
	 * Separator token used between key and value when encoding
	 * {@link JobFactory} configuration entries into a string payload.
	 */
	public static final String KEY_VALUE_SEPARATOR = ":=";

	/**
	 * Additive offset applied to the raw average-activity computation in
	 * {@link #getAverageActivityRating()}. A negative value biases the reported
	 * activity lower so that a lightly loaded group still appears willing to accept
	 * more work from peers.
	 */
	private double activityO = -0.2;

	/**
	 * Maximum number of {@link NodeProxy} entries permitted for the same remote
	 * endpoint before the oldest duplicate is forcibly dropped.
	 */
	private final int maxDuplicateConnections = 2;

	/**
	 * Legacy single-factory job source. Tasks are now managed via {@link #tasks}
	 * together with {@link #addTask(JobFactory)}; this field is retained only for
	 * backwards compatibility and may be {@code null}.
	 *
	 * @deprecated Use {@link #tasks} with {@link #addTask(JobFactory)} instead.
	 */
	@Deprecated
	private final JobFactory defaultFactory;

	/**
	 * Worker {@link Node} instances owned by this group. Each node executes jobs
	 * whose required labels are satisfied by the node's own labels.
	 */
	private final List<Node> nodes;

	/**
	 * Dedicated relay {@link Node} labelled {@code role=relay} whose sole purpose
	 * is to forward jobs that cannot be satisfied by any local worker node to a
	 * capable peer over an established {@link Connection}.
	 */
	private final Node relayNode;

	/**
	 * Live socket-level connections to remote servers, each wrapped in a
	 * {@link NodeProxy} that handles message framing and optional encryption.
	 */
	private final List<NodeProxy> servers;

	/**
	 * Proxies currently being initialised inside {@link #addServer(NodeProxy)}.
	 * A proxy is present in this list from the moment it enters that method until
	 * initialisation completes, so that re-entrant callbacks (e.g.
	 * {@link #connect(NodeProxy)}) can skip still-pending proxies.
	 */
	private final List connecting;

	/**
	 * Active {@link JobFactory} instances registered as tasks for this group.
	 * The run loop polls each factory every iteration to produce new
	 * {@link Job} objects up to {@link #maxTasks} factories per cycle.
	 */
	private final List<JobFactory> tasks;

	/**
	 * String-encoded task representations received while their
	 * {@link JobFactory} class was unavailable. Entries are retained so
	 * they can be reported by {@link #taskList()} and potentially replayed.
	 */
	private final List cachedTasks;

	/**
	 * External {@link NodeProxy.EventListener} instances that must be notified
	 * whenever a server connection is removed from this group.
	 */
	private final List plisteners;

	/**
	 * Number of {@link Job} objects to request from each {@link JobFactory} per
	 * run-loop iteration, scaled by the factory's priority.
	 */
	private int jobsPerTask = 1;

	/**
	 * Upper bound on the number of active task factories processed per
	 * run-loop iteration.
	 */
	private int maxTasks = 10;

	/**
	 * Password used to authenticate and/or encrypt communication with remote
	 * servers via {@link NodeProxy}. {@code null} means no authentication.
	 */
	private char[] passwd;

	/**
	 * Name of the symmetric encryption algorithm applied to server communication,
	 * as understood by {@link NodeProxy}. {@code null} means no encryption.
	 */
	private final String crypt;

	/**
	 * Renders HTML status pages and maintains the activity and throughput
	 * time-series charts for this group.
	 */
	private final NodeGroupStatusRenderer statusRenderer;

	/**
	 * Dispatches incoming {@link Message} objects to the appropriate handler
	 * on behalf of this group.
	 */
	private final NodeGroupMessageHandler messageHandler;

	/**
	 * Propagates configuration parameters to all child {@link Node}s and
	 * handles the {@link #setParam(String, String)} dispatch table.
	 */
	private final NodeGroupNodeConfig nodeConfig;

	/**
	 * Computes aggregate performance and connectivity metrics over the child
	 * node collection (completed jobs, time worked, activity ratings, etc.).
	 */
	private final NodeGroupMetrics metrics;

	/**
	 * The thread that drives the group's main run loop ({@link #run()}).
	 */
	private final Thread thread;

	/**
	 * Background daemon thread that periodically samples the group's activity
	 * and sleep metrics without blocking the main run loop.
	 */
	private final Thread monitor;

	/**
	 * When {@code true} the run loop exits at the next opportunity.
	 * Set by {@link #stop()}.
	 */
	private boolean stop;

	/**
	 * Counter that increments each run-loop iteration while no server connections
	 * are present. When it exceeds 200 the group considers itself isolated and
	 * calls {@link #becameIsolated()}.
	 */
	private int isolationTime;

	/**
	 * Interval in milliseconds between successive monitor-thread samples of
	 * activity and sleep metrics. Defaults to 30 seconds.
	 */
	private int monitorSleep = 30000;

	/**
	 * Counter of consecutive iterations where {@link #nextJob()} returned
	 * {@code null}. Used to suppress per-iteration log noise; a summary is printed
	 * every 21 null results.
	 */
	private int jobWasNull = 1;
	
	/**
	 * Constructs a new NodeGroup object using the specified JobFactory object
	 * and the properties defined in the specified Properties object.
	 * 
	 * @param p  Properties object to read properties from.
	 * @param f  Source of jobs for this NodeGroup object.
	 */
	public NodeGroup(Properties p, JobFactory f) {
		super(null, 0, 0, 0);
		super.setName("Node Group");
		
		this.defaultFactory = f;
		
		this.setSleep(Integer.parseInt(p.getProperty("group.thread.sleep", "10000")));
		
		String pass = p.getProperty("group.proxy.password");
		if (pass != null) this.passwd = pass.toCharArray();
		
		this.crypt = p.getProperty("group.proxy.crypt");
		
		int nodeCount = Integer.parseInt(p.getProperty("nodes.initial", "1"));
		int nodeMaxJobs = Integer.parseInt(p.getProperty("nodes.jobs.max", "4"));
		int nodeMaxPeers = Integer.parseInt(p.getProperty("nodes.peers.max", "10"));
		int relayMaxPeers = Integer.parseInt(p.getProperty("nodes.relay.peers.max", "10"));
		
		int serverCount = Integer.parseInt(p.getProperty("servers.total", "0"));
		
		this.connecting = new ArrayList();
		this.tasks = new ArrayList();
		this.nodes = new ArrayList(nodeCount);
		this.cachedTasks = new ArrayList();
		
		if (nodeCount > 0) System.out.println("NodeGroup: Constructing child nodes...");

		for (int i = 0; i < nodeCount; i++) {
			Node n = new Node(this, i, nodeMaxJobs, nodeMaxPeers);
			this.nodes.add(n);

			System.out.println("NodeGroup: Added node " + i + " (" + n + ")");
		}

		this.relayNode = new Node(this, nodeCount, nodeMaxJobs, relayMaxPeers);
		this.relayNode.setLabel("role", "relay");
		System.out.println("NodeGroup: Added relay node (index " + nodeCount + ")");

		NodeGroupNodeConfig.applyNodeLabels(this, this.nodes, p);

		this.plisteners = new ArrayList();
		super.sleepGraph = new Chart(Integer.MAX_VALUE - 1);

		Chart activityChart = new Chart(Integer.MAX_VALUE - 1);
		this.statusRenderer = new NodeGroupStatusRenderer(this, activityChart, 5);
		this.messageHandler = new NodeGroupMessageHandler(this);
		this.nodeConfig = new NodeGroupNodeConfig(this, this.nodes);

		this.setParam(p);

		this.servers = new ArrayList(serverCount);
		NodeGroupNodeConfig.initServerConnections(this, p, serverCount);

		super.rssfile = p.getProperty("group.rss.file");
		String rsslink = p.getProperty("group.rss.url");

		if (rssfile != null) {
			SimpleDateFormat df = new SimpleDateFormat("h:mm a 'on' EEEE, MMMM d");
			super.log = new RSSFeed("Network Node Group Log", "Started at " + df.format(new Date()));

			if (rsslink != null) super.log.setLink(rsslink);
		}
		this.metrics = new NodeGroupMetrics(this.nodes);

		Client c = Client.getCurrentClient();
		ThreadGroup g = null;
		if (c != null) g = c.getServer().getThreadGroup();
		this.thread = new Thread(g, this);
		this.thread.setName("Node Group Thread");
		this.thread.setPriority(Server.MODERATE_PRIORITY);

		this.monitor = new Thread(new Runnable() {
			/** {@inheritDoc} */
			@Override
			public void run() {
				while (true) {
					try {
						Thread.sleep(NodeGroup.this.monitorSleep);
					} catch (InterruptedException ie) { }

					double aar = NodeGroup.this.getAverageActivityRating();
					NodeGroup.this.statusRenderer.recordActivitySample(aar);

					int s = NodeGroup.this.getSleep();
					NodeGroup.this.sleepSum += s;
					NodeGroup.this.totalSleepSum += s;
					NodeGroup.this.sleepDiv++;
					NodeGroup.this.totalSleepDiv++;
				}
			}
		});

		this.monitor.setDaemon(true);
	}

	/**
	 * Starts the thread that manages the activity of this NodeGroup and the threads
	 * for the child nodes stored by this NodeGroup.
	 */
	@Override
	public Node start() {
		this.stop = false;
		this.thread.start();

		synchronized (this.nodes) {
			nodes.forEach(Node::start);
		}

		this.relayNode.start();
		
		return this;
	}
	
	/**
	 * Starts the background monitor thread at the specified scheduler priority
	 * and sampling interval. The monitor periodically records the group's
	 * average activity and sleep metrics so that status polls can report
	 * meaningful interval averages even when no poll has occurred recently.
	 *
	 * @param priority  Thread priority for the monitor (see {@link Thread#setPriority}).
	 * @param sleep     Sampling interval in milliseconds between successive metric reads.
	 */
	public void startMonitor(int priority, int sleep) {
		this.monitorSleep = sleep;
		this.monitor.setPriority(priority);
		this.monitor.start();
	}
	
	/**
	 * Stops the thread that manages the activity of this {@link NodeGroup} and the
	 * threads for the child nodes stored by this NodeGroup.
	 */
	@Override
	public void stop() {
		this.stop = true;
		
		synchronized (this.nodes) {
			Iterator itr = this.nodes.iterator();
			while (itr.hasNext()) ((Node)itr.next()).stop();
		}

		this.relayNode.stop();
	}
	
	/**
	 * @return  True if any child node of this {@link NodeGroup} is working, false otherwise.
	 */
	@Override
	public boolean isWorking() {
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) if (((Node)itr.next()).isWorking()) return true;
		
		return false;
	}
	
	/**
	 * Applies every entry in the supplied {@link Properties} object as a named
	 * parameter by delegating to {@link #setParam(String, String)}. Numeric
	 * parse errors are logged but do not abort processing of the remaining entries.
	 *
	 * @param p  Properties whose entries are applied as group configuration parameters.
	 */
	public void setParam(Properties p) {
		Iterator itr = p.entrySet().iterator();
		while (itr.hasNext()) {
			Map.Entry e = (Map.Entry) itr.next();
			String k = (String) e.getKey();
			String v = (String) e.getValue();
			
			try {
				this.setParam(k, v);
			} catch (NumberFormatException nfe) {
				System.out.println("NodeGroup: Error parsing number " +
							k + " = " + v + " (" + nfe.getMessage() + ")");
			}
		}
	}
	
	/**
	 * Applies a single named configuration parameter to this group and propagates
	 * relevant parameters to all child nodes. Recognised keys are documented in
	 * {@link NodeGroupNodeConfig}.
	 *
	 * @param name   The property key.
	 * @param value  The string value to apply.
	 * @return {@code true} if the key was recognised and applied;
	 *         {@code false} if it is unknown to this group.
	 * @throws NumberFormatException  If {@code value} cannot be parsed as the
	 *         numeric type required by the named property.
	 */
	public boolean setParam(String name, String value) {
		return this.nodeConfig.setParam(name, value);
	}
	
	/**
	 * Retrieves a named object from this group or its child nodes.
	 * Supported keys:
	 * <ul>
	 *   <li>{@code group.tasks} — returns {@link #taskList()} as a {@code String[]}.</li>
	 *   <li>{@code group.tasks.<i>} — returns the {@link JobFactory} at index {@code i}
	 *       from the tasks list.</li>
	 *   <li>{@code node.<i>} — returns the child {@link Node} at index {@code i}.</li>
	 *   <li>{@code node.<i>.<subkey>} — delegates {@link Node#getObject(String)} on
	 *       child {@code i} using {@code subkey}.</li>
	 * </ul>
	 *
	 * @param key  Hierarchical dot-separated key identifying the object.
	 * @return The requested object, or {@code null} if the key is not recognised.
	 */
	// TODO Add tasks object value to documentation.
	@Override
	public Object getObject(String key) {
		if (key.equals("group.tasks")) {
			return this.taskList();
		} else if (key.startsWith("group.tasks.")) {
			int i = Integer.parseInt(key.substring(12));
			return this.tasks.get(i);
		} else if (key.startsWith("node.")) {
			int in = key.indexOf(".", 5);
			boolean r = false;
			
			if (in < 0) {
				in = key.length();
				r = true;
			}
			
			int i = Integer.parseInt(key.substring(5, in));
			Node n = this.nodes.get(i);
			
			if (r)
				return n;
			else
				return n.getObject(key.substring(in + 1));
		} else {
			return null;
		}
	}
	
	/**
	 * Returns the set of Node objects stored by this NodeGroup object.
	 *
	 * @return  The set of Node objects stored by this NodeGroup object.
	 */
	public Node[] getNodes() { return this.nodes.toArray(new Node[0]); }
	
	/**
	 * Returns a live view of the child {@link Node} collection managed by this
	 * group. Callers must synchronize on the returned collection when iterating
	 * while the group is running.
	 *
	 * @return  The mutable, live collection of child nodes.
	 */
	public Collection<Node> nodes() { return nodes; }
	
	/**
	 * @return  The default {@link JobFactory} used by this {@link NodeGroup} object.
	 */
	@Override
	public JobFactory getJobFactory() { return this.defaultFactory; }
	
	/**
	 * Returns a snapshot of all registered tasks as strings, combining the
	 * string representations of active {@link JobFactory} instances in
	 * {@link #tasks} with the raw encoded strings stored in
	 * {@link #cachedTasks}.
	 *
	 * @return  Array of task descriptions; never {@code null}.
	 */
	public String[] taskList() {
		List l = new ArrayList();
		
		Iterator itr = this.tasks.iterator();
		while (itr.hasNext()) l.add(itr.next().toString());
		
		itr = this.cachedTasks.iterator();
		while (itr.hasNext()) l.add(itr.next());
		
		return (String[]) l.toArray(new String[0]);
	}
	
	/**
	 * Adds an encoded task string to the cached-task list if it is not already
	 * present. Cached tasks are retained as raw strings when the corresponding
	 * {@link JobFactory} class cannot be instantiated at reception time.
	 *
	 * @param task  Encoded task string to cache.
	 */
	public void addCachedTask(String task) {
		if (!this.cachedTasks.contains(task)) this.cachedTasks.add(task);
	}
	
	/**
	 * Returns the Node object stored by this NodeGroup object with the lowest connectivity rating.
	 *
	 * @return  The Node object stored by this NodeGroup object with the lowest connectivity rating.
	 */
	public Node getLeastConnectedNode() { return this.metrics.getLeastConnectedNode(); }

	/**
	 * Returns the {@link Node} with the lowest activity rating.
	 *
	 * @return  The {@link Node} with the lowest activity rating.
	 */
	public Node getLeastActiveNode() { return this.metrics.getLeastActiveNode(); }

	/**
	 * Finds the least active child Node whose labels satisfy the
	 * job's required labels. Returns null if no child qualifies.
	 *
	 * @param j the job to match
	 * @return a suitable Node, or null
	 */
	public Node findNodeForJob(Job j) { return this.metrics.findNodeForJob(j); }

	/**
	 * Adds the specified socket connection as a server for this NodeGroup to communicate with.
	 *
	 * @param s  Socket connection to server.
	 * @throws IOException  If an IO error occurs constructing a NodeProxy using the Socket.
	 * @throws InvalidAlgorithmParameterException
	 * @throws NoSuchPaddingException 
	 * @throws InvalidKeySpecException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public synchronized boolean addServer(Socket s) throws IOException {
		return this.addServer(s, false);
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
	public synchronized boolean addServer(Socket s, boolean server) throws IOException {
		try {
			return this.addServer(new NodeProxy(s, this.passwd, this.crypt, server));
		} catch (InvalidKeyException e) {
			System.out.println("\nNodeGroup: Invalid key (" + e.getMessage() + ").");
		} catch (NoSuchAlgorithmException e) {
			System.out.println("\nNodeGroup: Encryption algorithm not found (" + e.getMessage() + ").");
		} catch (InvalidKeySpecException e) {
			System.out.println("\nNodeGroup: Invalid key spec (" + e.getMessage() + ").");
		} catch (NoSuchPaddingException e) {
			System.out.println("\nNodeGroup: Encryption padding not found (" + e.getMessage() + ").");
		} catch (InvalidAlgorithmParameterException e) {
			System.out.println("\nNodeGroup: Invalid encryption parameter (" + e.getMessage() + ").");
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
	public synchronized boolean addServer(NodeProxy pr) {
		this.connecting.add(pr);
		
		Iterator itr = this.servers.iterator();
		int d = 0;
		NodeProxy p = null;
		
		while (itr.hasNext()) {
			NodeProxy np = (NodeProxy) itr.next();
			
			if (np.equals(pr)) {
				d++;
				
				if (d == 1) p = np;
			}
		}
		
		if (d >= this.maxDuplicateConnections) {
			this.removeServer(p);
			this.displayMessage("Removed duplicate server " + p);
		}
		
		pr.addEventListener(this);
		
		synchronized (this.tasks) {
			Iterator titr = this.tasks.iterator();
			
			while (titr.hasNext()) {
				Object o = titr.next();
				if (o instanceof NodeProxy.EventListener) {
					pr.addEventListener((NodeProxy.EventListener) o);
				}
			}
		}
		
		pr.fireConnect();
		this.servers.add(pr);
		
		String msg = "Added server " + (this.servers.size() - 1);
		this.displayMessage(msg + " - " + pr);
		this.statusRenderer.addActivityMessage(msg);
		
		pr.flushQueue();
		
		this.connecting.remove(pr);
		
		return true;
	}
	
	/**
	 * Removes and disposes the connection between this node group and the peer
	 * with the specified index.
	 * 
	 * @param index  Index of peer to remove.
	 * @return  The total number of node connections dropped due to the removal.
	 */
	public synchronized int removeServer(int index) {
		return this.removeServer(this.servers.get(index));
	}
	
	/**
	 * Removes and disposes the connection maintained by the specified NodeProxy object.
	 * 
	 * @param p  NodeProxy maintaing connection that is to be removed.
	 * @return  The total number of node connections dropped due to the removal.
	 */
	public synchronized int removeServer(NodeProxy p) {
		p.removeEventListener(this);
		
		int tot = 0;
		
		Iterator itr = NodeGroup.this.nodes.iterator();
		while (itr.hasNext()) tot += ((Node)itr.next()).disconnect(p);
		
		boolean r = this.servers.remove(p);
		
		if (tot > 0)
			this.displayMessage("Dropped " + tot + " connections to " + p);
		else if (r)
			this.displayMessage("Dropped server " + p);
		
		itr = this.plisteners.iterator();
		while (itr.hasNext()) ((NodeProxy.EventListener)itr.next()).disconnect(p);
		
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
	public void startPersistentHost(String host, int port){
		new Thread(() -> {
			w: while (true) {
				try {
					Thread.sleep(30 * 1000L);
				} catch (InterruptedException e) {
					e.printStackTrace();
					return;
				}

				if (getServers().length > 0)
					continue w;

				System.out.println("NodeGroup: Connecting to root server...");

				try {
					addServer(new Socket(host, port));
				} catch (UnknownHostException uh) {
					System.out.println("NodeGroup: Server " + host + " is unknown host");
				} catch (IOException ioe) {
					System.out.println("NodeGroup: IO error while connecting to server " +
							host + " -- " + ioe.getMessage());
				} catch (SecurityException se) {
					System.out.println("NodeGroup: Security exception while connecting to server " + host +
							" (" + se.getMessage() + ")");
				}
			}
		}, "Persistent Host Attempt").start();
	}
	
	/**
	 * Returns a snapshot of the job currently executing in each child node,
	 * in index order. Entries are {@code null} for nodes that are idle.
	 *
	 * @return  Array of job descriptions (or {@code null} per idle slot),
	 *          one element per child node.
	 */
	public String[] getCurrentWork() {
		synchronized (this.nodes) {
			String[] w = new String[this.nodes.size()];
			Iterator itr = this.nodes.iterator();
			int i = 0;
			
			while (itr.hasNext()) {
				Object o = ((Node)itr.next()).getCurrentJob();
				if (o == null)
					w[i] = null;
				else
					w[i] = o.toString();
				
				i++;
			}
			
			return w;
		}
	}
	
	/**
	 * Returns a snapshot array of all currently registered server proxies.
	 * The array is a copy, so it is safe to iterate without holding the
	 * internal lock after the call returns.
	 *
	 * @return  Array of active {@link NodeProxy} connections; never {@code null}.
	 */
	public NodeProxy[] getServers() {
		synchronized (this.servers) {
			return this.servers.toArray(new NodeProxy[0]);
		}
	}
	
	/**
	 * Pings the specified peer.
	 * 
	 * @param peer  Index of peer to ping.
	 * @param size  Size of packet in characters (pairs of bytes).
	 * @param timeout  Max time to wait for a response, in milliseconds.
	 * @return  The time, in milliseconds, to respond to the ping.
	 */
	public synchronized long ping(int peer, int size, int timeout) throws IOException {
		return this.servers.get(peer).ping(size, timeout);
	}
	
	/**
	 * Selects a server at random and sends a connection request.
	 * This method may return null.
	 * 
	 * @param id  Unique id of this child node that is requesting the connection.
	 * @return  A Connection object that can be used to relay data bewteen a local node and a remote node.
	 */
	public synchronized Connection getConnection(int id) {
		NodeProxy p = null;
		
		w: while (true) {
			if (this.servers.size() < 1) return null;
			
			int s = (int)(Math.random() * this.servers.size());
			p = this.servers.get(s);
			
			if (p.isConnected())
				break;
			else
				this.removeServer(p);
		}
		
		Connection c = null;
		
		try {
			Message m = new Message(Message.ConnectionRequest, id, p);
			Node localNode = id < this.nodes.size() ? this.nodes.get(id) : this.relayNode;
			m.setLocalNode(localNode);
			c = (Connection)m.send(-1);
		} catch (SocketException se) {
			this.displayMessage("Removing server " + p + " (" + se.getMessage() + ")");
			this.removeServer(p);
		} catch (IOException ioe) {
			System.out.println("NodeGroup: " + ioe);
			return null;
		}

		return c;
	}
	
	/**
	 * Decodes an encoded task string into a fully initialised
	 * {@link JobFactory}. The string format is:
	 * <pre>
	 *   &lt;ClassName&gt;&lt;ENTRY_SEP&gt;&lt;key1&gt;:=&lt;value1&gt;&lt;ENTRY_SEP&gt;...
	 * </pre>
	 * The first token is the fully-qualified class name; subsequent tokens are
	 * key-value pairs applied to the factory via {@link JobFactory#set(String, String)}.
	 * Returns {@code null} if the class cannot be found or instantiated.
	 *
	 * @param data  Encoded representation of the {@link JobFactory}.
	 * @return  The constructed and configured factory, or {@code null} on failure.
	 */
	/**
	 * Decodes an encoded task string into a fully initialised
	 * {@link JobFactory}. Delegates to
	 * {@link NodeGroupNodeConfig#createTask(String, String)}.
	 *
	 * @param data  Encoded representation of the {@link JobFactory}.
	 * @return  The constructed and configured factory, or {@code null} on failure.
	 */
	protected JobFactory createTask(String data) {
		return NodeGroupNodeConfig.createTask(data, KEY_VALUE_SEPARATOR);
	}
	
	/**
	 * Adds the specified {@link JobFactory} as a task for this {@link NodeGroup}.
	 *
	 * @param f  JobFactory to use as task.
	 * @return  True if added, false otherwise.
	 */
	public boolean addTask(JobFactory f) {
		this.statusRenderer.addActivityMessage("Added task " + f.getTaskId());
		return this.tasks.add(f);
	}
	
	/**
	 * Constructs a {@link JobFactory} and adds it as a task for this {@link NodeGroup}.
	 * 
	 * @param data  Encoded {@link JobFactory} to use as task.
	 * @return  True if added, false otherwise.
	 */
	public boolean addTask(String data) {
		JobFactory t = this.createTask(data);
		
		if (t == null) return false;
		
		if (super.getLog() != null && super.getLog().getLink() != null) {
			String n = this.getName();
			this.setName(n + "  Received task " + t);
			this.displayMessage("Received task " + t, super.getLog().getLink() +
								"NetworkRender-" + t.getTaskId() + ".jpg");
			this.setName(n);
		} else {
			this.displayMessage("Recieved task " + t);
		}
		
		return this.addTask(t);
	}
	
	/**
	 * Sends an encoded {@link JobFactory} instance to a server that
	 * this {@link NodeGroup} is connected to.
	 * 
	 * @param data  Encoded JobFactory.
	 * @param server  Server index.
	 */
	public synchronized void sendTask(String data, int server) {
		try {
			Message m = new Message(Message.Task, -1, this.servers.get(server));
			m.setString(data);
			m.send(-1);
		} catch (IOException ioe) {
			System.out.println("NodeGroup: " + ioe);
		}
	}
	
	/**
	 * Sends an encoded JobFactory instance to a server that this {@link NodeGroup}
	 * is connected to.
	 *
	 * @param f  JobFactory to transmit.
	 * @param server  Server index.
	 */
	public synchronized void sendTask(JobFactory f, int server) {
		try {
			Message m = new Message(Message.Task, -1, this.servers.get(server));
			m.setString(f.encode());
			m.send(-1);
		} catch (IOException ioe) {
			System.out.println("NodeGroup: " + ioe);
		}
	}
	
	/**
	 * Sends a kill signal for the specified task to 
	 * 
	 * @param task  Task id to kill.
	 * @param relay  Number of times to relay the signal.
	 */
	@Override
	public void sendKill(String task, int relay) {
		synchronized (this.tasks) {
			Iterator itr = this.tasks.iterator();
			while (itr.hasNext()) {
				if (((JobFactory)itr.next()).getTaskId().equals(task)) {
					itr.remove();
					System.out.println("NodeGroup: Killed task " + task);
				}
			}
		}
		
		synchronized (this.nodes) {
			Iterator itr = this.nodes.iterator();
			while (itr.hasNext()) ((Node)itr.next()).sendKill(task, relay);
		}
	}
	
	/**
	 * Sets the component which will display the last status message printed by this node.
	 *
	 * @param label  JLabel component to display status messages.
	 */
	@Override
	public void setStatusLabel(JLabel label) {
		super.setStatusLabel(label);

		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node) itr.next()).setStatusLabel(label);
	}

	/**
	 * Propagates the activity-sleep coefficient to every child node.
	 * This coefficient scales how much a node's sleep time adapts to its
	 * current activity rating.
	 *
	 * @param acs  Activity-sleep coefficient to apply to all child nodes.
	 */
	@Override
	public void setActivitySleepC(double acs) {
		if (this.nodes == null) return;
		this.nodeConfig.setActivitySleepC(acs);
	}

	/**
	 * Propagates the peer-activity-sleep coefficient to every child node.
	 * This coefficient adjusts how much a node's sleep time responds to
	 * the activity levels of its connected peers.
	 *
	 * @param pacs  Peer-activity-sleep coefficient to apply to all child nodes.
	 */
	@Override
	public void setPeerActivitySleepC(double pacs) {
		if (this.nodes == null) return;
		this.nodeConfig.setPeerActivitySleepC(pacs);
	}

	/**
	 * Propagates the parental-relay probability to every child node.
	 * This probability governs how often a node relays a job upward to its
	 * parent (this group) rather than to a peer.
	 *
	 * @param parp  Parental relay probability in the range [0.0, 1.0].
	 */
	@Override
	public void setParentalRelayP(double parp) {
		if (this.nodes == null) return;
		this.nodeConfig.setParentalRelayP(parp);
	}

	/**
	 * Propagates the peer relay coefficient to every child node.
	 *
	 * @param prc  Peer relay coefficient to apply to all child nodes.
	 */
	@Override
	public void setPeerRelayC(double prc) {
		if (this.nodes == null) return;
		this.nodeConfig.setPeerRelayC(prc);
	}

	/**
	 * Propagates the minimum-job probability to every child node.
	 * This is the lower-bound probability used when deciding whether to
	 * execute or relay the next job.
	 *
	 * @param mjp  Minimum job-execution probability in the range [0.0, 1.0].
	 */
	@Override
	public void setMinimumJobP(double mjp) {
		if (this.nodes == null) return;
		this.nodeConfig.setMinimumJobP(mjp);
	}

	/**
	 * Sets the max number of failed jobs to be stored by each child
	 * of this node group.
	 *
	 * @param mfj  Maximum number of failed jobs to retain per child node.
	 */
	@Override
	public void setMaxFailedJobs(int mfj) {
		if (this.nodes == null) return;
		this.nodeConfig.setMaxFailedJobs(mfj);
	}

	/**
	 * Sets the relay probability (0.0 - 1.0) for each child of this node group
	 * to the specified double value.
	 *
	 * @param r  Relay probability in the range [0.0, 1.0].
	 */
	@Override
	public void setRelayProbability(double r) {
		if (this.nodes == null) return;
		this.nodeConfig.setRelayProbability(r);
	}

	/**
	 * Propagates the peer-weighting flag to every child node.
	 *
	 * @param w  True if peers to relay should be chosen using weighted probability.
	 */
	@Override
	public void setWeightPeers(boolean w) {
		if (this.nodes == null) return;
		this.nodeConfig.setWeightPeers(w);
	}

	/**
	 * Sets the activity-offset bias applied to the group's activity rating.
	 * A negative value shifts the effective baseline, reducing the reported
	 * activity when the group is lightly loaded.
	 *
	 * @param o  The offset to apply to the activity rating.
	 */
	void setActivityOffset(double o) { this.activityO = o; }

	/**
	 * Sets the number of {@link io.flowtree.job.Job} objects requested from
	 * each factory per run-loop cycle.
	 *
	 * @param n  Jobs to request per factory per cycle.
	 */
	void setJobsPerTask(int n) { this.jobsPerTask = n; }

	/**
	 * Sets the maximum number of task factories processed per run-loop cycle.
	 *
	 * @param n  Upper bound on factory iterations per cycle.
	 */
	void setMaxTasks(int n) { this.maxTasks = n; }

	/**
	 * Returns the {@link NodeGroupStatusRenderer} that builds HTML status pages
	 * and maintains the activity time-series charts for this group.
	 *
	 * @return the status renderer; never {@code null}
	 */
	NodeGroupStatusRenderer getStatusRenderer() { return this.statusRenderer; }

	/**
	 * Routes a job directly from the message handler without going through the
	 * full {@link #run()} dispatch path.  Used by {@link NodeGroupMessageHandler}
	 * to forward inbound {@link io.flowtree.msg.Message#Job} messages.
	 *
	 * @param j  The job to route; must not be {@code null}.
	 */
	void routeJobFromHandler(Job j) { routeJob(j); }

	/**
	 * Returns the dedicated relay {@link Node} that forwards jobs unable to be
	 * satisfied by any local worker node.
	 *
	 * @return  The relay node; never {@code null} after construction.
	 */
	Node getRelayNode() { return this.relayNode; }

	/**
	 * Returns a snapshot copy of the active task-factory list, safe to iterate
	 * without holding the internal lock.  Used by {@link NodeGroupStatusRenderer}.
	 *
	 * @return  Copy of the current task-factory list; never {@code null}.
	 */
	List<JobFactory> getTasksCopy() {
		synchronized (this.tasks) {
			return new ArrayList<>(this.tasks);
		}
	}

	/**
	 * Returns the sleep-time trend chart for this group, used by the status
	 * renderer to record sleep samples.
	 *
	 * @return  The sleep chart, or {@code null} if not initialised.
	 */
	Chart getSleepGraph() { return super.sleepGraph; }

	/**
	 * Returns the current interval sleep-time accumulator for the status renderer.
	 *
	 * @return  Sum of sleep values sampled in the current interval.
	 */
	double getSleepSum() { return super.sleepSum; }

	/**
	 * Returns the sample count for the current sleep-time interval.
	 *
	 * @return  Number of samples in the current interval.
	 */
	int getSleepDiv() { return super.sleepDiv; }

	/**
	 * Resets the per-interval sleep-time metrics after the renderer has consumed
	 * them to record a chart entry.
	 */
	void resetSleepMetrics() {
		super.sleepSum = 0.0;
		super.sleepDiv = 0;
	}

	/**
	 * Returns the number of jobs completed by all the children of this node.
	 *
	 * @return  The number of jobs completed by all the children of this node.
	 */
	@Override
	public int getCompletedJobCount() { return this.metrics.getCompletedJobCount(); }

	/**
	 * Returns the total time the nodes in this node group have worked, measured in milliseconds.
	 *
	 * @return  The total time the nodes in this node group have worked, measured in msecs.
	 */
	@Override
	public double getTimeWorked() { return this.metrics.getTimeWorked(); }

	/**
	 * Returns the total time all child nodes in this group have spent on
	 * network communication, measured in milliseconds (truncated to whole
	 * milliseconds).
	 *
	 * @return  Total communication time in milliseconds across all child nodes.
	 */
	@Override
	public double getTimeCommunicated() { return this.metrics.getTimeCommunicated(); }

	/**
	 * Returns the average time for a node in this node group to complete a job.
	 *
	 * @return  The average time for a node in this node group to complete a job.
	 *          (-1.0 if no jobs have been completed).
	 */
	public double getAverageJobTime() { return this.metrics.getAverageJobTime(); }

	/**
	 * Returns the average connectivity rating for the nodes in this node group.
	 *
	 * @return  The average connectivity rating for the nodes in this node group.
	 */
	public double getAverageConnectivityRating() { return this.metrics.getAverageConnectivityRating(); }

	/**
	 * Returns the average activity rating for this node group.
	 *
	 * @return  The value of this.getAverageActivityRating.
	 */
	@Override
	public double getActivityRating() { return this.getAverageActivityRating(); }

	/**
	 * Computes the mean activity rating across all child nodes, with
	 * {@link #activityO} added as a bias offset.  A negative offset means this
	 * group always appears slightly less busy than the raw average would
	 * suggest, making it more attractive as a job recipient.
	 *
	 * @return  Biased average activity rating; 0.0 if no child nodes exist.
	 */
	public double getAverageActivityRating() {
		return this.metrics.getAverageActivityRating(this.activityO);
	}

	/**
	 * Returns the ratio of the average activity rating reported by known servers to
	 * the average node's activity rating.
	 *
	 * @return  The ratio of the average activity rating reported by known servers to
	 *          the average node's activity rating.
	 */
	public double getPeerActivityRatio() {
		return this.getAveragePeerActivityRating() / this.getAverageActivityRating();
	}

	/**
	 * Returns the average activity rating reported by the servers connected to this node group.
	 *
	 * @return  The average activity rating reported by the servers connected
	 *          to this node group. Returns {@code 0.0} if no peers have reported an activity
	 *          rating measurement.
	 */
	public double getAveragePeerActivityRating() { return NodeGroupMetrics.getAveragePeerActivityRating(this.getServers()); }
	
	/**
	 * Prints the status of this network node group to standard out.
	 */
	@Override
	public void printStatus() { this.statusRenderer.printStatus(System.out); }

	/**
	 * Prints the status of this network node group using the specified PrintStream object.
	 *
	 * @param out  PrintStream to use.
	 */
	@Override
	public void printStatus(PrintStream out) { this.statusRenderer.printStatus(out); }

	/**
	 * Returns a string containing status information for this network node group,
	 * formatted as HTML. Delegates to {@link NodeGroupStatusRenderer#getStatus(String)}.
	 *
	 * @param nl  Newline token to use between sections (typically {@code "<br>\n"}).
	 * @return    The rendered HTML status string.
	 */
	@Override
	public String getStatus(String nl) { return this.statusRenderer.getStatus(nl); }

	/**
	 * Stores the elements in the activity rating graph maintained by this node group.
	 * The data will be output by the Graph class (net.sf.j3d.util) and the format should
	 * be newline separated decimal values, with each new line representing a uniform
	 * increment of time.
	 *
	 * @param f  File representing location to store activity rating data.
	 * @return  True if the file was written, false if no activity data is being collected.
	 * @throws IOException  If an IO error occurs writing the file.
	 */
	public boolean storeActivityGraph(File f) throws IOException { return this.statusRenderer.storeActivityGraph(f); }

	/**
	 * Stores the elements in the sleep time graph maintained by this node group.
	 * The data will be output by the Graph class (net.sf.j3d.util) and the format should
	 * be newline separated integer values, with each new line representing a uniform
	 * increment of time.
	 *
	 * @param f  File representing location to store sleep time data.
	 * @return  True if the file was written, false if no sleep time data is being collected.
	 * @throws IOException  If an IO error occurs writing the file.
	 */
	public boolean storeSleepGraph(File f) throws IOException {
		if (this.sleepGraph != null) {
			this.sleepGraph.storeValues(f);
			return true;
		}

		return false;
	}
	
	/**
	 * Main run loop for the NodeGroup management thread. Each iteration:
	 * <ol>
	 *   <li>Calls {@link #iteration(Node)} to adjust the group's sleep time and
	 *       notify {@link Node.ActivityListener}s.</li>
	 *   <li>Sleeps for the computed interval (longer when isolated).</li>
	 *   <li>Detects network isolation and invokes
	 *       {@link #becameIsolated()} after 200 consecutive isolated cycles.</li>
	 *   <li>Calls {@link #nextJob()} on the default factory and routes any
	 *       resulting job via {@link #routeJob(Job)}.</li>
	 *   <li>Polls all registered task {@link JobFactory}s, routing their jobs
	 *       and removing completed factories.</li>
	 * </ol>
	 */
	@Override
	public void run() {
		while (!this.stop) {
			this.iteration(this);
			
			int svrs = this.servers.size();
			
			try {
				int sleep = super.getSleep();
				
				if (svrs > 0 || this.tasks.size() > 0)
					Thread.sleep(sleep);
				else
					Thread.sleep(sleep * 10L);
			} catch (InterruptedException ie) {
				System.out.println("NodeGroup: " + ie);
			}
			
			if (this.isolationTime > 200) {
				this.becameIsolated();
			} else if (svrs == 0) {
				this.isolationTime++;
			} else {
				this.isolationTime = 0;
			}
			
			Iterator itr = ((List)((ArrayList) this.tasks).clone()).iterator();

			Job j = this.nextJob();
			if (j == null) this.jobWasNull++;

			if (this.verbose && j != null) {
				if (this.jobWasNull > 1) {
					System.out.println("NodeGroup: Last " +
									(this.jobWasNull - 1) +
									" jobs were null.");
				}
				
				System.out.println("NodeGroup: nextJob = " + j);
			}
			
			if (this.jobWasNull % 21 == 0) {
				if (this.verbose)
					System.out.println("NodeGroup: Last " +
									(this.jobWasNull - 1) +
									" jobs were null.");
				
				this.jobWasNull = 1;
			}
			
			if (j != null) {
				routeJob(j);
			}

			addJobs(defaultFactory);

			List<JobFactory> completed = new ArrayList<>();
			for (int i = 0; itr.hasNext() && i < this.maxTasks; i++) {
				JobFactory f = (JobFactory) itr.next();

				if (f.isComplete()) {
					completed.add(f);
				} else {
					addJobs(f);
				}
			}
			this.tasks.removeAll(completed);
		}
	}

	/**
	 * Routes a job to the most appropriate child {@link Node}. If a Node exists
	 * whose labels satisfy the job's requirements it receives the job directly.
	 * Otherwise the job is queued on the relay Node so the activity thread can
	 * forward it to a capable peer.
	 *
	 * @param j the job to route
	 */
	private void routeJob(Job j) {
		Node target = findNodeForJob(j);
		if (target == null) target = this.relayNode;
		target.addJob(j);
	}

	/**
	 * Requests up to {@code jobsPerTask * f.getPriority()} jobs from the given
	 * {@link JobFactory} and routes each non-null result via
	 * {@link #routeJob(Job)}. Runtime exceptions thrown by the factory are
	 * caught and logged so a misbehaving factory cannot abort the run loop.
	 *
	 * @param f  The factory from which jobs are requested.
	 */
	private void addJobs(JobFactory f) {
		double t = this.jobsPerTask * f.getPriority();

		Job j = null;

		for (int k = 0; k < t; k++) {
			try {
				j = f.nextJob();
			} catch (RuntimeException e) {
				System.out.println("NodeGroup: Runtime exception while getting next job from " + f);

				if (e.getCause() != null)
					e.getCause().printStackTrace();
				else
					e.printStackTrace();
			}

			if (j != null) {
				if (this.verbose)
					System.out.println("NodeGroup: " + f + "  nextJob = " + j);

				routeJob(j);
			}
		}
	}
	
	/**
	 * Registers an external listener that will be notified when a server
	 * connection is removed from this group. Useful for components that
	 * maintain state tied to specific {@link NodeProxy} instances.
	 *
	 * @param l  Listener to register.
	 */
	public void addProxyEventListener(NodeProxy.EventListener l) { this.plisteners.add(l); }
	
	/**
	 * @see NodeProxy.EventListener#connect(NodeProxy)
	 */
	@Override
	public void connect(NodeProxy pr) {
		if (this.connecting.contains(pr)) return;
		this.addServer(pr);
	}
	
	/**
	 * @see NodeProxy.EventListener#disconnect(NodeProxy)
	 * @return  The number of connections dropped.
	 */
	@Override
	public int disconnect(NodeProxy p) { return this.removeServer(p); }
	
	/**
	 * Dispatches an incoming {@link Message} to the appropriate handler via
	 * {@link NodeGroupMessageHandler}. Only messages addressed to receiver
	 * {@code -1} (the group itself) are processed; all others return {@code false}.
	 *
	 * @param m         The received message.
	 * @param receiver  ID of the intended recipient; must be {@code -1} for this
	 *                  method to handle the message.
	 * @return {@code true} if the message was handled; {@code false} otherwise.
	 */
	@Override
	public boolean recievedMessage(Message m, int receiver) {
		return this.messageHandler.recievedMessage(m, receiver);
	}

	/**
	 * Returns a human-readable summary of this group, listing the number of
	 * child nodes, active server connections, and queued jobs.
	 *
	 * @return  A descriptive string such as
	 *          {@code "Network Node Group: 2 children and 1 server connection."}.
	 */
	@Override
	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append("Network Node Group: ");
		int nodes = this.nodes != null ? this.nodes.size() : 0;
		if (nodes > 0) {
			b.append(nodes);
			b.append(" child");
			if (nodes > 1) b.append("ren");
		}
		int servers = this.servers != null ? this.servers.size() : 0;
		if (servers > 0) {
			if (nodes > 0) b.append(" and ");
			b.append(servers);
			b.append(" server connection");
			if (servers > 1) b.append("s");
		}
		b.append(".");
		int jobs = this.jobs != null ? this.jobs.size() : 0;
		if (jobs > 0) {
			b.append(" ");
			b.append(jobs);
			b.append(" jobs in queue.");
		}
		return b.toString();
	}
	
	/**
	 * Called at the start of each run-loop cycle. Adjusts the node's sleep
	 * time proportionally to its current activity rating, then notifies all
	 * registered {@link Node.ActivityListener}s that an iteration has occurred.
	 * This is the primary feedback mechanism that lets the group self-throttle
	 * under load.
	 *
	 * @param n  The node (always {@code this} group) whose sleep time is updated.
	 */
	@Override
	public void iteration(Node n) {
		n.setSleep((int) (n.getActivityRating() * n.getSleep()));
		
		if (this.verbose)
			System.out.println("NodeGroup: Notifying iteration.");
		
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).iteration(this);
		}
		
		if (this.verbose)
			System.out.println("NodeGroup: Notified listeners.");
	}
}
