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
import io.flowtree.fs.OutputServer;
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
import java.util.Optional;
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
	 * Time-series chart of the group's average activity rating, sampled once per
	 * status poll. Used to render the activity history section of the HTML status
	 * page returned by {@link #getStatus(String)}.
	 */
	private final Chart activityGraph;

	/**
	 * Time-series chart of {@link OutputServer} job throughput, sampled every
	 * {@link #tpFreq} status polls to avoid over-weighting short bursts.
	 */
	private final Chart throughputGraph;

	/**
	 * Throughput sampling interval: one sample is taken every {@code tpFreq}
	 * calls to {@link #getStatus(String)}.
	 */
	private final int tpFreq = 5;

	/**
	 * Counter tracking the current position within the {@link #tpFreq} sampling
	 * interval for throughput measurements.
	 */
	private int tpLast = 0;

	/**
	 * Accumulator for activity ratings gathered since the last status poll, used
	 * to compute the interval average displayed on the status page.
	 */
	private double activitySum;

	/**
	 * Lifetime accumulator for all activity rating samples ever recorded,
	 * used to compute the running-total average shown in the status page.
	 */
	private double totalActivitySum;

	/**
	 * Number of samples added to {@link #activitySum} since the last status
	 * poll. Reset to zero after each poll.
	 */
	private int activityDivisor;

	/**
	 * Lifetime count of all activity rating samples ever added to
	 * {@link #totalActivitySum}.
	 */
	private int totalActivityDiv;

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

		// Load labels from properties (nodes.labels.<key>=<value>)
		String labelPrefix = "nodes.labels.";
		for (Object keyObj : p.keySet()) {
			String key = (String) keyObj;
			if (key.startsWith(labelPrefix)) {
				String labelKey = key.substring(labelPrefix.length());
				String labelValue = p.getProperty(key);
				this.setLabel(labelKey, labelValue);
				for (Node n : this.nodes) {
					n.setLabel(labelKey, labelValue);
				}
			}
		}

		// Load labels from environment variable (FLOWTREE_NODE_LABELS=key1:value1,key2:value2)
		String envLabels = System.getenv("FLOWTREE_NODE_LABELS");
		if (envLabels != null && !envLabels.isEmpty()) {
			for (String pair : envLabels.split(",")) {
				String[] parts = pair.split(":", 2);
				if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
					this.setLabel(parts[0].trim(), parts[1].trim());
					for (Node n : this.nodes) {
						n.setLabel(parts[0].trim(), parts[1].trim());
					}
				}
			}
		}

		// Auto-detect platform label if not explicitly set
		if (this.getLabels().get("platform") == null) {
			String os = System.getProperty("os.name", "").toLowerCase();
			String platform = os.contains("mac") ? "macos" : "linux";
			this.setLabel("platform", platform);
			for (Node n : this.nodes) {
				n.setLabel("platform", platform);
			}
			System.out.println("NodeGroup: Auto-detected platform label: platform=" + platform);
		}

		this.setParam(p);
		
		this.servers = new ArrayList(serverCount);

		String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
		String rootPort = System.getenv("FLOWTREE_ROOT_PORT");

		if (rootHost != null) {
			if (rootPort == null) rootPort = String.valueOf(Server.defaultPort);
			startPersistentHost(rootHost, Integer.parseInt(rootPort));
		}
		
		if (serverCount > 0) System.out.println("NodeGroup: Opening server connections...");
		
		for (int i = 0; i < serverCount; i++) {
			String host = p.getProperty("servers." + i + ".host", "localhost");
			int port = Integer.parseInt(p.getProperty("servers." + i + ".port", "7777"));
			
			try {
				System.out.println("NodeGroup: Connecting to server " + i + " (" + host + ":" + port + ")...");
				
				Socket s = new Socket(host, port);
				// s.setKeepAlive(true);
				
				this.addServer(s);
			} catch (UnknownHostException uh) {
				System.out.println("NodeGroup: Server " + i + " is unknown host");
			} catch (IOException ioe) {
				System.out.println("NodeGroup: IO error while connecting to server " +
								i + " -- " + ioe.getMessage());
			} catch (SecurityException se) {
				System.out.println("NodeGroup: Security exception while connecting to server " + i +
								" (" + se.getMessage() + ")");
			}
		}
		
		super.rssfile = p.getProperty("group.rss.file");
		String rsslink = p.getProperty("group.rss.url");
		
		if (rssfile != null) {
			SimpleDateFormat df = new SimpleDateFormat("h:mm a 'on' EEEE, MMMM d");
			super.log = new RSSFeed("Network Node Group Log", "Started at " + df.format(new Date()));
			
			if (rsslink != null) super.log.setLink(rsslink);
		}
		
		this.plisteners = new ArrayList();
		this.activityGraph = new Chart(Integer.MAX_VALUE - 1);
		this.throughputGraph = new Chart();
		super.sleepGraph = new Chart(Integer.MAX_VALUE - 1);
		
		Client c = Client.getCurrentClient();
		ThreadGroup g = null;
		if (c != null) g = c.getServer().getThreadGroup();
		this.thread = new Thread(g, this);
		this.thread.setName("Node Group Thread");
		this.thread.setPriority(Server.MODERATE_PRIORITY);
		
		this.monitor = new Thread(new Runnable() {
			public void run() {
				while (true) {
					try {
						Thread.sleep(NodeGroup.this.monitorSleep);
					} catch (InterruptedException ie) { }
					
					double aar = NodeGroup.this.getAverageActivityRating();
					NodeGroup.this.activitySum += aar;
					NodeGroup.this.totalActivitySum += aar;
					NodeGroup.this.activityDivisor++;
					NodeGroup.this.totalActivityDiv++;
					
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
	 * relevant parameters to all child nodes. Recognised keys include:
	 * {@code nodes.acs}, {@code nodes.pasc}, {@code nodes.parp}, {@code nodes.prc},
	 * {@code nodes.mjp}, {@code nodes.mfj}, {@code group.aco}, {@code group.msc},
	 * {@code group.taskjobs}, {@code group.taskmax}, {@code nodes.workingDirectory},
	 * {@code nodes.relay}, {@code nodes.wp}, and the {@link Message} verbosity flags.
	 *
	 * @param name   The property key.
	 * @param value  The string value to apply.
	 * @return {@code true} if the key was recognised and applied;
	 *         {@code false} if it is unknown to this group.
	 * @throws NumberFormatException  If {@code value} cannot be parsed as the
	 *         numeric type required by the named property.
	 */
	public boolean setParam(String name, String value) {
		String msg = null;
		
		if (name.equals("nodes.acs")) {
			msg = "ActivitySleepC = " + value;
			this.setActivitySleepC(Double.parseDouble(value));
		} else if (name.equals("nodes.pasc")) {
			msg = "PeerActivitySleepC = " + value;
			this.setPeerActivitySleepC(Double.parseDouble(value));
		} else if (name.equals("nodes.parp")) {
			msg = "ParentalRelayP = " + value;
			this.setParentalRelayP(Double.parseDouble(value));
		} else if (name.equals("nodes.prc")) {
			msg = "PeerRelayC = " + value;
			this.setPeerRelayC(Double.parseDouble(value));
		} else if (name.equals("nodes.mjp")) {
			msg = "MinimumJobP = " + value;
			this.setMinimumJobP(Double.parseDouble(value));
		} else if (name.equals("nodes.mfj")) {
			msg = "MaxFailedJobs = " + value;
			this.setMaxFailedJobs(Integer.parseInt(value));
		} else if (name.equals("group.aco")) {
			msg = "ActivityOffset = " + value;
			this.activityO = Double.parseDouble(value);
		} else if (name.equals("group.msc")) {
			msg = "MaxSleepC = " + value;
			this.setMaxSleepC(Double.parseDouble(value));
		} else if (name.equals("network.msg.verbose")) {
			Message.verbose = Boolean.parseBoolean(value);
		} else if (name.equals("network.msg.dverbose")) {
			Message.dverbose = Boolean.parseBoolean(value);
		} else if (name.equals("network.msg.sverbose")) {
			Message.sverbose = Boolean.parseBoolean(value);
		} else if (name.equals("group.nverbose")) {
			this.verbose = Boolean.parseBoolean(value);
		} else if (name.equals("group.taskjobs")) {
			msg = "JobsPerTask = " + value;
			this.jobsPerTask = Integer.parseInt(value);
		} else if (name.equals("group.taskmax")) {
			msg = "MaxTasks = " + value;
			this.maxTasks = Integer.parseInt(value);
		} else if (name.equals("nodes.workingDirectory")) {
			msg = "WorkingDirectory = " + value;
			System.setProperty("flowtree.workingDirectory", value);
		} else if (name.equals("nodes.relay")) {
			msg = "RelayP = " + value;
			this.setRelayProbability(Double.parseDouble(value));
		} else if (name.equals("nodes.wp")) {
			msg = "WeightPeers = " + value;
			this.setWeightPeers(Boolean.parseBoolean(value));
		} else {
			return false;
		}
		
		if (msg != null) {
			System.out.println("NodeGroup: " + msg);
			if (this.activityGraph != null) this.activityGraph.addMessage(msg);
		}
		
		return true;
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
	 * @return  The Node object stored by this NodeGroup object with the lowest connectivity rating.
	 */
	public Node getLeastConnectedNode() {
		Node n = null;
		
		synchronized (this.nodes) {
			Iterator itr = this.nodes.iterator();
			
			while (itr.hasNext()) {
				Node next = (Node)itr.next();
				
				if (n == null || n.getConnectivityRating() > next.getConnectivityRating())
					n = next;
			}
		}
		
		return n;
	}
	
	/**
	 * @return  The {@link Node} with the lowest activity rating.
	 */
	public Node getLeastActiveNode() {
		List<Node> l = new ArrayList<Node>();
		double rating = -1.0;
		
		synchronized (this.nodes) {
			Iterator<Node> itr = this.nodes.iterator();
			
			while (itr.hasNext()) {
				Node next = itr.next();
				double a = next.getActivityRating();
				
				if (rating == -1.0 || rating > a) {
					l.clear();
					l.add(next);
					rating = a;
				} else if (a == rating) {
					l.add(next);
				}
			}
		}
		
		if (l.size() > 0)
			return l.get(random.nextInt(l.size()));
		else
			return null;
	}
	
	/**
	 * Finds the least active child Node whose labels satisfy the
	 * job's required labels. Returns null if no child qualifies.
	 *
	 * @param j the job to match
	 * @return a suitable Node, or null
	 */
	public Node findNodeForJob(Job j) {
		Map<String, String> requirements = j.getRequiredLabels();

		List<Node> candidates = new ArrayList<>();
		double rating = -1.0;

		synchronized (this.nodes) {
			for (Node n : this.nodes) {
				if (n.satisfies(requirements)) {
					double a = n.getActivityRating();
					if (rating == -1.0 || rating > a) {
						candidates.clear();
						candidates.add(n);
						rating = a;
					} else if (a == rating) {
						candidates.add(n);
					}
				}
			}
		}

		if (candidates.isEmpty()) return null;
		return candidates.get(random.nextInt(candidates.size()));
	}

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
		if (this.activityGraph != null)
			this.activityGraph.addMessage(msg);
		
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
		
		// boolean b = c.confirm();
		
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
	protected JobFactory createTask(String data) {
		int index = data.indexOf(JobFactory.ENTRY_SEPARATOR);
		String className = data.substring(0, index);
		
		Class c = null;
		JobFactory j = null;
		
		try {
			c = Class.forName(className);
			j = (JobFactory)c.newInstance();
			
			boolean end = false;

			while (!end) {
				data = data.substring(index + JobFactory.ENTRY_SEPARATOR.length());
				index = data.indexOf(JobFactory.ENTRY_SEPARATOR);

				while (data.charAt(index + JobFactory.ENTRY_SEPARATOR.length()) == '/' || index > 0 && data.charAt(index - 1) == '\\') {
					index = data.indexOf(JobFactory.ENTRY_SEPARATOR, index + 1);
				}

				String s = null;

				if (index <= 0) {
					s = data;
					end = true;
				} else {
					s = data.substring(0, index);
				}

				String key = s.substring(0, s.indexOf(KEY_VALUE_SEPARATOR));
				String value = s.substring(s.indexOf(KEY_VALUE_SEPARATOR) + KEY_VALUE_SEPARATOR.length());

				j.set(key, value);
			}
		} catch (ClassNotFoundException cnf) {
			System.out.println("NodeGroup: Class not found: " + className);
		} catch (ClassCastException cce) {
			System.out.println("NodeGroup: Error casting " +
					Optional.ofNullable(c).map(Class::getName).orElse(null) +
					" to JobFactory");
		} catch (Exception e) {
			System.out.println("NodeGroup: " + e);
		}
		
		return j;
	}
	
	/**
	 * Adds the specified {@link JobFactory} as a task for this {@link NodeGroup}.
	 * 
	 * @param f  JobFactory to use as task.
	 * @return  True if added, false otherwise.
	 */
	public boolean addTask(JobFactory f) {
		if (this.activityGraph != null)
			this.activityGraph.addMessage("Added task " + f.getTaskId());
		
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
	public void setStatusLabel(JLabel label) {
		super.setStatusLabel(label);
		
		
		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setStatusLabel(label);
	}
	
	/**
	 * Propagates the activity-sleep coefficient to every child node.
	 * This coefficient scales how much a node's sleep time adapts to its
	 * current activity rating.
	 *
	 * @param acs  Activity-sleep coefficient to apply to all child nodes.
	 */
	public void setActivitySleepC(double acs) {
		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setActivitySleepC(acs);
	}
	
	/**
	 * Propagates the peer-activity-sleep coefficient to every child node.
	 * This coefficient adjusts how much a node's sleep time responds to
	 * the activity levels of its connected peers.
	 *
	 * @param pacs  Peer-activity-sleep coefficient to apply to all child nodes.
	 */
	public void setPeerActivitySleepC(double pacs) {
		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setPeerActivitySleepC(pacs);
	}
	
	/**
	 * Propagates the parental-relay probability to every child node.
	 * This probability governs how often a node relays a job upward to its
	 * parent (this group) rather than to a peer.
	 *
	 * @param parp  Parental relay probability in the range [0.0, 1.0].
	 */
	public void setParentalRelayP(double parp) {
		if (this.nodes == null) return;
		Iterator<Node> itr = this.nodes.iterator();
		while (itr.hasNext()) itr.next().setParentalRelayP(parp);
	}
	
	/**
	 * Sets the peer relay coefficient for each child of this node group
	 * to the specified double value.
	 */
	public void setPeerRelayC(double prc) {
		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setPeerRelayC(prc);
	}
	
	/**
	 * Propagates the minimum-job probability to every child node.
	 * This is the lower-bound probability used when deciding whether to
	 * execute or relay the next job.
	 *
	 * @param mjp  Minimum job-execution probability in the range [0.0, 1.0].
	 */
	public void setMinimumJobP(double mjp) {
		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setMinimumJobP(mjp);
	}
	
	/**
	 * Sets the max number of failed jobs to be stored by each child
	 * of this node group.
	 */
	public void setMaxFailedJobs(int mfj) {
		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setMaxFailedJobs(mfj);
	}
	
	/**
	 * Sets the relay probability (0.0 - 1.0) for each child of this node group
	 * to the specified double value.
	 */
	public void setRelayProbability(double r) {
		if (this.nodes == null) return;
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setRelayProbability(r);
	}
	
	/**
	 * @param w  True if peers to relay should be chosen using weighted probability.
	 */
	public void setWeightPeers(boolean w) {
		if (this.nodes == null) return;
		
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) ((Node)itr.next()).setWeightPeers(w);
	}
	
	/**
	 * @return  The number of jobs completed by all the children of this node.
	 */
	public int getCompletedJobCount() {
		int t = 0;
		
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) t += ((Node)itr.next()).getCompletedJobCount();
		
		return t;
	}
	
	/**
	 * @return  The total time the nodes in this node group have worked, measured in msecs.
	 */
	public double getTimeWorked() {
		double t = 0;
		
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) t += ((Node)itr.next()).getTimeWorked();
		
		return t - (t % 1);
	}
	
	/**
	 * Returns the total time all child nodes in this group have spent on
	 * network communication, measured in milliseconds (truncated to whole
	 * milliseconds).
	 *
	 * @return  Total communication time in milliseconds across all child nodes.
	 */
	public double getTimeCommunicated() {
		double t = 0;
		
		Iterator itr = this.nodes.iterator();
		while (itr.hasNext()) t += ((Node)itr.next()).getTimeCommunicated();
		
		return t - (t % 1);
	}
	
	/**
	 * @return  The average time for a node in this node group to complete a job.
	 *          (-1.0 if no jobs have been completed).
	 */
	public double getAverageJobTime() {
		Iterator itr = this.nodes.iterator();
		
		int i = 0;
		double tot = 0.0;
		
		while (itr.hasNext()) {
			Node n = (Node) itr.next();
			
			tot += n.getTimeWorked();
			i += n.getCompletedJobCount();
		}
		
		if (i == 0) {
			return -1.0;
		} else {
			return tot / i;
		}
	}
	
	/**
	 * @return  The average connectivity rating for the nodes in this node group.
	 */
	public double getAverageConnectivityRating() {
		Iterator itr = this.nodes.iterator();
		
		int i = 0;
		double tot = 0.0;
		
		while (itr.hasNext()) {
			tot += ((Node)itr.next()).getConnectivityRating();
			i++;
		}
		
		if (i == 0)
			return 0.0;
		else
			return tot / i;
	}
	
	/**
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
		Iterator itr = this.nodes.iterator();
		
		int i = 0;
		double tot = 0.0;
		
		while (itr.hasNext()) {
			tot += ((Node)itr.next()).getActivityRating();
			i++;
		}
		
		if (i == 0)
			return 0.0;
		else
			return tot / i + this.activityO;
	}
	
	/**
	 * @return  The ratio of the average activity rating reported by known servers to
	 *          the average node's activity rating.
	 */
	public double getPeerActivityRatio() {
		return this.getAveragePeerActivityRating() / this.getAverageActivityRating();
	}
	
	/**
	 * @return  The average activity rating reported by the servers connected
	 *          to this node group. (0.0 if no peers have reported an activity
	 *          rating measurement).
	 */
	public double getAveragePeerActivityRating() {
		NodeProxy[] p = this.getServers();
		
		double sum = 0.0;
		int peers = 0;
		
		for (int i = 0; i < p.length; i++) {
			double j = p[i].getActivityRating();
			if (j > 0) {
				sum += j;
				peers++;
			}
		}
		
		if (peers > 0) {
			return sum / peers;
		} else {
			return 0.0;
		}
	}
	
	/**
	 * Prints the status of this network node group to standard out.
	 */
	public void printStatus() { this.printStatus(System.out); }
	
	/**
	 * Prints the status of this network node group using the specified PrintStream object.
	 * 
	 * @param out  PrintStream to use.
	 */
	public void printStatus(PrintStream out) {
		out.println("<html>");
		out.println("<head><title>");
		out.println("Node Group Status");
		out.println("</title></head><body>");
		out.println(this.getStatus("<br>\n"));
		out.println("</body></html>");
		
		// HtmlFormat.printPage(out, "Node Group Status", this.getStatus("<br>\n"));
	}
	
	/**
	 * @return  A String containing status information for this network node group.
	 *          The string is formatted with HTML.
	 */
	public String getStatus(String nl) {
		if (Message.verbose) System.out.println("NodeGroup: Starting status check.");
		
		StringBuffer buf = new StringBuffer();
		
		Date now = new Date();
		
		buf.append(now + nl + nl);
		
		buf.append("<center><h1>Network Node Group Status</h1>");
		buf.append("<p><h3>" + this + "</h3>" + nl);
		buf.append("<b>Sleep time:</b> " + Node.formatTime(super.getSleep()) + "</p></center>" + nl);
		
		NodeProxy[] s = this.getServers();
		if (Message.verbose) System.out.println("NodeGroup.getStatus: Got server list.");
		
		buf.append("<table><tr><td><h3>Servers</h3></td><td><h3>TaskList</h3></td></tr><tr>");
		
		buf.append("<td>");
		
		for (int i = 0; i < s.length; i++)
			buf.append("\t" + s[i].toString(true) + nl);
		
		buf.append("</td><td>");
		
		Iterator itr;
		
		itr = ((List)((ArrayList)this.tasks).clone()).iterator();
		while (itr.hasNext())
			buf.append("\t" + ((JobFactory)itr.next()).getName() + nl);
		
		buf.append("</td></tr></table>");
		
		itr = this.nodes.iterator();
		while (itr.hasNext()) buf.append(((Node)itr.next()).getStatus(nl));
		
		buf.append(nl);
		
		if (this.activityGraph != null) {
			double a = 0.0;
			
			if (this.activityDivisor > 0) {
				a = this.activitySum / this.activityDivisor;
				this.activitySum = 0.0;
				this.activityDivisor = 0;
			} else {
				a = this.getActivityRating();
			}
			
			this.activityGraph.addEntry(a);
		}
		
		if (this.sleepGraph != null) {
			double sl = 0.0;
			
			if (this.sleepDiv > 0) {
				sl = this.sleepSum / this.sleepDiv;
				this.sleepSum = 0.0;
				this.sleepDiv = 0;
			} else {
				sl = this.getSleep();
			}
			
			this.sleepGraph.addEntry(sl);
		}
		
		if (this.activityGraph != null) {
			buf.append("<b>Activity Rating</b>" + nl);
			buf.append("Running Total Average = ");
			buf.append(this.totalActivitySum / this.totalActivityDiv);
			buf.append(nl);
			buf.append("<pre><font size=\"-2\">" + nl);
			this.activityGraph.print(buf);
			buf.append("</font></pre>" + nl);
		}
		
		if (Message.verbose) System.out.println("NodeGroup: Getting dbs info...");
		
		OutputServer dbs = OutputServer.getCurrentServer();
		if (dbs != null) {
			if (this.tpLast % this.tpFreq == 0) {
				this.throughputGraph.addEntry(dbs.getThroughput());
				this.tpLast = 1;
			} else {
				this.tpLast++;
			}
			
			synchronized (dbs) {
				buf.append("<b>DBS Throughput</b>" + nl);
				buf.append("Running Total Average = ");
				buf.append(dFormat.format(dbs.getTotalAverageThroughput()));
				buf.append(" jobs per minute.");
				buf.append(nl);
				buf.append("Average Job Time = ");
				buf.append(dFormat.format(dbs.getTotalAverageJobTime() / 60000.0));
				buf.append(" minutes per job.");
				buf.append(nl);
				buf.append("<pre><font size=\"-2\">" + nl);
				this.throughputGraph.print(buf);
				buf.append("</font></pre>" + nl);
			}
		}
		
		if (Message.verbose) System.out.println("NodeGroup: Returning status check.");
		
		return buf.toString();
	}
	
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
	public boolean storeActivityGraph(File f) throws IOException {
		if (this.activityGraph != null) {
			this.activityGraph.storeValues(f);
			return true;
		}
		
		return false;
	}
	
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
				
//				double aar = this.getAverageActivityRating();
//				this.activitySum += aar;
//				this.totalActivitySum += aar;
//				this.activityDivisor++;
//				this.totalActivityDiv++;
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
	public void connect(NodeProxy pr) {
		if (this.connecting.contains(pr)) return;
		this.addServer(pr);
	}
	
	/**
	 * @see NodeProxy.EventListener#disconnect(NodeProxy)
	 * @return  The number of connections dropped.
	 */
	public int disconnect(NodeProxy p) { return this.removeServer(p); }
	
	/**
	 * @see NodeProxy.EventListener#recievedMessage(Message, int)
	 */
	/**
	 * Dispatches an incoming {@link Message} to the appropriate handler.
	 * Only messages addressed to receiver {@code -1} (i.e. the group itself
	 * rather than a specific child node) are processed here; all others are
	 * ignored and {@code false} is returned.
	 *
	 * <p>Handled message types:
	 * <ul>
	 *   <li>{@link Message#Job} — decodes and routes an inbound job.</li>
	 *   <li>{@link Message#StringMessage} — logs a human-readable message.</li>
	 *   <li>{@link Message#ConnectionRequest} — picks the least-connected
	 *       local node, creates a {@link Connection}, and sends a
	 *       {@link Message#ConnectionConfirmation} back to the requester.</li>
	 *   <li>{@link Message#ConnectionConfirmation} — echoes a confirmation
	 *       if the data payload is null (two-phase handshake completion).</li>
	 *   <li>{@link Message#ServerStatus} — updates the sending proxy's
	 *       activity rating and average job time from the encoded payload.</li>
	 *   <li>{@link Message#ServerStatusQuery} — responds with the list of
	 *       known peer servers (excluding the querying peer itself).</li>
	 *   <li>{@link Message#ResourceRequest} — looks up the URI of the
	 *       requested resource and replies with a {@link Message#ResourceUri}.</li>
	 *   <li>{@link Message#Task} — deserialises and registers a new
	 *       {@link JobFactory} task.</li>
	 *   <li>{@link Message#Kill} — kills the identified task and propagates
	 *       the kill signal to all child nodes.</li>
	 * </ul>
	 *
	 * @param m         The received message.
	 * @param receiver  ID of the intended recipient; must be {@code -1} for this
	 *                  method to handle the message.
	 * @return {@code true} if the message was handled; {@code false} otherwise.
	 */
	public boolean recievedMessage(Message m, int receiver) {
		if (receiver == -1) {
			NodeProxy p = m.getNodeProxy();
			
			int type = m.getType();
			int remoteId = m.getSender();
			
			if (type == Message.Job) {
				System.out.println("NodeGroup: Received job. Data = " + m.getData());
				routeJob(this.defaultFactory.createJob(m.getData()));
			} else if (type == Message.StringMessage) {
				System.out.println("Message from " + p.toString() + ": " + m.getData());
			} else if (type == Message.ConnectionRequest) {
				try {
					Node n = this.getLeastConnectedNode();
					if (n == null) n = this.relayNode;
					Connection c;

					if (n == null) {
						System.out.println("NodeGroup: ConnectionRequest rejected -- no available node (no workers, no relay)");
						c = null;
					} else if (n.getPeers().length >= n.getMaxPeers()) {
						System.out.println("NodeGroup: ConnectionRequest rejected -- node " + n.getId() +
								" at peer capacity (" + n.getPeers().length + "/" + n.getMaxPeers() + ")");
						c = null;
					} else if (n.isConnected(p)) {
						if (Message.verbose) System.out.println("NodeGroup: ConnectionRequest rejected -- node " + n.getId() +
								" already connected via proxy " + p);
						c = null;
					} else {
						System.out.println("NodeGroup: Constructing connection...");
						c = new Connection(n, p, remoteId);
					}

					if (c != null && n.connect(c)) {
						Message response = new Message(Message.ConnectionConfirmation, n.getId(), p);
						response.setString("true");
						response.send(remoteId);
					} else {
						if (c != null) {
							System.out.println("NodeGroup: ConnectionRequest rejected -- n.connect(c) returned false for node " + n.getId());
						}
						Message response = new Message(-1, -1, p);
						response.setString("false");
						response.send(remoteId);
					}
				} catch (IOException ioe) {
					System.out.println("NodeGroup: " + ioe);
				}
			} else if (type == Message.ConnectionConfirmation) {
				if (m.getData() == null) {
					try {
						Message response = new Message(Message.ConnectionConfirmation, -1, p);
						response.setString("true");
						response.send(remoteId);
					} catch (IOException ioe) {
						System.out.println("NodeGroup: " + ioe);
					}
				}
			} else if (type == Message.ServerStatus) {
				String[] s = m.getData().split(";");
				
				boolean h = false;
				
				for (int i = 0; i < s.length; i++) {
					int index = s[i].indexOf(JobFactory.ENTRY_SEPARATOR);
					String v = "";
					if (index > 0 && index < s[i].length() - 1) v = s[i].substring(index + JobFactory.ENTRY_SEPARATOR.length());
					
					try {
						if (s[i].startsWith("jobtime" + JobFactory.ENTRY_SEPARATOR)) {
							p.setJobTime(Double.parseDouble(v));
							h = true;
						} else if (s[i].startsWith("activity" + JobFactory.ENTRY_SEPARATOR)) {
							p.setActivityRating(Double.parseDouble(v));
							h = true;
						} else {
							System.out.println("NodeGroup: Unknown status type '" + s[i] + "'");
						}
					} catch (NumberFormatException nfe) {
						System.out.println("NodeGroup: Could not parse status item '" +
											s[i] + "' (" + nfe.getMessage() + ")");
					}
				}

				return h;
			} else if (type == Message.ServerStatusQuery) {
				if (m.getData().equals("peers")) {
					try {
						Message response = new Message(Message.ServerStatus, -1, p);
						
						NodeProxy[] svs = this.getServers();
						
						StringBuffer b = new StringBuffer();
						b.append("peers:");
						boolean f = false;
						int j = 0;
						for (int i = 0; i < svs.length; i++) {
							if (svs[i] != p) {
								if (f) {
									b.append("," + svs[i]);
								} else {
									b.append(svs[i]);
									f = true;
								}
							} else {
								j++;
							}
						}
						
						if (Message.verbose)
							System.out.println("NodeGroup: Reported " + (svs.length - j) +
												" peers for status query (Excluded " + p + ").");
						
						response.setString(b.toString());
						response.send(remoteId);
					} catch (IOException ioe) {
						System.out.println("NodeGroup: Error sending server status (" +
											ioe.getMessage() + ")");
					}
				}
			} else if (type == Message.ResourceRequest) {
				try {
					Message response = new Message(Message.ResourceUri, -1, p);
					
					Server s = OutputServer.getCurrentServer().getNodeServer();
					String r = s.getResourceUri(m.getData());
					System.out.println("NodeGroup: Sending resource uri (" + r + ")");
					response.setString(r);
					response.send(remoteId);
				} catch (IOException ioe) {
					System.out.println("NodeGroup: Error sending resource uri (" +
										ioe.getMessage() + ")");
				}
			} else if (type == Message.Task) {
				if (m.getData() != null)
					this.addTask(m.getData());
				else
					this.displayMessage("Received null task.");
			} else if (type == Message.Kill) {
				int i = m.getData().indexOf(JobFactory.ENTRY_SEPARATOR);
				String task = m.getData().substring(0, i);
				int relay = Integer.parseInt(m.getData().substring(i + JobFactory.ENTRY_SEPARATOR.length()));
				
				this.sendKill(task, relay--);
			} else {
				return false;
			}
			
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Returns a human-readable summary of this group, listing the number of
	 * child nodes, active server connections, and queued jobs.
	 *
	 * @return  A descriptive string such as
	 *          {@code "Network Node Group: 2 children and 1 server connection."}.
	 */
	public String toString() {
		StringBuffer b = new StringBuffer();
		b.append("Network Node Group: ");
		
		int nodes = 0;
		
		if (this.nodes != null) nodes = this.nodes.size();
		
		if (nodes > 0) {
			b.append(nodes);
			b.append(" child");
			if (nodes > 1) b.append("ren");
		}
		
		int servers = 0;
		
		if (this.servers != null) servers = this.servers.size();
		
		if (servers > 0) {
			if (nodes > 0) b.append(" and ");
			b.append(servers);
			b.append(" server connection");
			if (servers > 1) b.append("s");
		}
		
		b.append(".");
		
		int jobs = 0;
		if (this.jobs != null) jobs = this.jobs.size();
		
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
