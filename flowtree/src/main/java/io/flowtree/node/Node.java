/*
 * Copyright 2020 Michael Murray
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.almostrealism.util.Chart;

import javax.swing.*;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A {@link Node} represents a member of the distributed network
 * which can process one {@link Job} at a time. A {@link Node} is
 * tied to a parent {@link NodeGroup} and is assigned an id that
 * is unique for that parent.
 *
 * <h2>Threads</h2>
 * <p>Each Node runs two threads:</p>
 * <ul>
 *   <li><b>Worker thread</b> -- pulls jobs from the queue, checks
 *       label requirements via {@link #satisfies(Map)}, and either
 *       executes the job or puts it back in the queue for relay.</li>
 *   <li><b>Activity thread</b> -- manages peer {@link Connection}
 *       establishment and relays excess jobs to peers. This is the
 *       only mechanism that moves jobs between Nodes on different
 *       Servers.</li>
 * </ul>
 *
 * <h2>Job Routing</h2>
 * <p>{@link io.flowtree.node.NodeGroup} routes each incoming job to the most
 * appropriate child Node via {@code routeJob()}: a Node whose labels satisfy
 * the job's requirements is preferred; if none exists locally the job goes to
 * the NodeGroup's built-in relay Node. A worker Node that still receives a
 * mismatched job (e.g. via a direct peer connection) relays it to its parent
 * NodeGroup for re-routing rather than re-queuing to itself.</p>
 *
 * <h2>Relay Nodes</h2>
 * <p>A Node with the label {@code role:relay} never executes jobs.
 * The worker thread is not started on relay Nodes (the
 * {@link #addJob(Job)} method skips worker startup when
 * {@code role:relay} is set). Jobs accumulate in the queue and are
 * moved exclusively by the relay loop in the activity thread until a
 * capable peer connection becomes available.</p>
 *
 * @author  Michael Murray
 * @see NodeGroup
 * @see Connection
 * @see <a href="../docs/node-relay.md">Node Relay and Job Routing</a>
 */
// TODO  Implement JobQueue
public class Node implements Runnable, ThreadFactory {
	/** Shared {@link Random} instance used for probabilistic relay and connection decisions. */
	public static Random random = new Random();

	/** Formatter for percentage values used in status output. */
	protected NumberFormat pFormat = NumberFormat.getPercentInstance();

	/** Formatter for decimal values (three decimal places) used in status output. */
	protected NumberFormat dFormat = new DecimalFormat("#.000");

	/**
	 * When {@code true}, the RSS log entry for each status message includes the full
	 * node status block in addition to the message headline.
	 */
	protected boolean verboseNews = true;

	/**
	 * When {@code true}, extra diagnostic output is written to stdout (e.g. peer
	 * selection decisions during relay).
	 */
	protected boolean verbose = false;

	/**
	 * When {@code true}, peer selection for relay is weighted by the activity rating
	 * reported by each remote node group, favouring the least-busy peer.
	 * When {@code false}, peers are selected uniformly at random.
	 */
	protected boolean weightPeers = false;

	/** Minimum sleep interval between activity-thread iterations, in milliseconds. */
	protected double minSleep = 5000;

	/**
	 * Coefficient that caps the maximum sleep interval as a multiple of
	 * {@link #minSleep}.  The effective maximum is {@code minSleep * maxSleepC}.
	 * May be overridden by the parent {@link NodeGroup}.
	 */
	private double maxSleepC = 300;

	/**
	 * Coefficient ({@code activitySleepC}) and offset ({@code activitySleepO}) that
	 * control how the activity rating of this node is translated into a sleep interval.
	 * The next sleep value is computed as
	 * {@code currentSleep * (activitySleepC / (activityRating + activitySleepO) - ...)}.
	 */
	private double activitySleepC = 1.2, activitySleepO = -0.4;

	/**
	 * Contribution of the parent NodeGroup's peer-activity ratio to the sleep
	 * adjustment formula.  A value of {@code 0.0} disables this influence.
	 */
	private double peerActivitySleepC = 0.0;

	/**
	 * Scaling constant used in the activity-rating calculation:
	 * {@code 1 + (jobs - minJobs) / (activityC * maxJobs)}.
	 */
	private final double activityC = 2.0;

	/**
	 * Minimum job-queue fill ratio (relative to {@link #maxJobs}) below which relay
	 * is suppressed.  Updated by {@link #setMinimumJobP(double)}.
	 */
	private double minJobP = 0.4;

	/**
	 * Coefficient added to the relay probability based on the fraction of peer
	 * connections currently in use.  Encourages relay when the node is well-connected.
	 */
	private double peerRelayC = 0.2;

	/**
	 * Probability (0.0–1.0) that a relay attempt routes the job to the parent
	 * {@link NodeGroup} rather than to a random peer connection.
	 */
	private double parentalRelayP = 0.0;

	/**
	 * Fraction of the parent {@link NodeGroup}'s sleep time that is added to this
	 * node's own sleep interval each iteration.  Currently fixed at {@code 0.0}.
	 */
	private final double parentalSleepP = 0.0;

	/**
	 * Listener interface for monitoring the activity state of a {@link Node}.
	 * Implementations are notified at the end of each activity iteration and
	 * whenever the node transitions between working, idle, and isolated states.
	 */
	public interface ActivityListener {
		/**
		 * Called at the end of each activity-thread iteration for the given node.
		 *
		 * @param n the node that completed an iteration
		 */
		void iteration(Node n);

		/**
		 * Called when the node's worker thread begins executing a job.
		 */
		void startedWorking();

		/**
		 * Called when the node's worker thread finishes executing a job.
		 */
		void stoppedWorking();

		/**
		 * Called when the node detects it has no peer connections and cannot
		 * relay jobs to any other part of the network.
		 */
		void becameIsolated();
	}
	
	/** The {@link NodeGroup} that owns and coordinates this node. May be {@code null} for standalone nodes (which do not execute jobs). */
	protected NodeGroup parent;

	/** Immutable identifier that is unique within the parent {@link NodeGroup}. */
	private final int id;

	/**
	 * Minimum number of jobs that should remain in the queue before relay is
	 * suppressed.  Below this threshold the node prefers to keep jobs locally
	 * rather than forwarding them to peers.
	 */
	private int minJobs;

	/**
	 * Maximum number of jobs the queue may hold before the node aggressively
	 * relays to peers (doubles the relay probability).
	 */
	private int maxJobs;

	/** Maximum number of peer {@link Connection}s this node will maintain simultaneously. */
	private final int maxPeers;

	/**
	 * Maximum number of failed job encodings to retain for retry.
	 * A value of {@code 0} disables the failed-job retry mechanism.
	 */
	private int maxFailedJobs;

	/**
	 * Base relay probability ({@code relay}) and base connect probability ({@code connect})
	 * used in the activity loop.  Both are values in [0.0, 1.0].
	 */
	private double relay, connect;

	/** Set of active {@link Connection} objects representing peer nodes. */
	private final Set peers;

	/** Ordered queue of jobs waiting to be executed or relayed by this node. */
	protected final List<Job> jobs;

	/** List of {@link ActivityListener} objects notified on state transitions. */
	protected List listeners;

	/**
	 * List of encoded (String) job representations that failed during execution.
	 * Jobs are decoded and retried when the queue is empty.
	 * {@code null} when the failed-job retry mechanism is disabled.
	 */
	protected List failedJobs;

	/**
	 * {@code stop} signals the activity thread to exit its loop on the next iteration.
	 * {@code working} is {@code true} while the worker thread is executing a job.
	 */
	private boolean stop, working;

	/** Current sleep interval (milliseconds) used between activity-thread iterations. */
	private int sleep;

	/**
	 * Running sum of relay-probability samples ({@code relaySum}) and connect-probability
	 * samples ({@code connectSum}) accumulated since the last status snapshot.
	 */
	private double relaySum, connectSum;

	/**
	 * Number of samples accumulated into {@link #relaySum} ({@code relayDiv}) and
	 * {@link #connectSum} ({@code connectDiv}) respectively.
	 */
	private int relayDiv, connectDiv;

	/**
	 * Strip charts displayed in the status HTML: relay probability over time
	 * ({@code relayGraph}), connect probability over time ({@code connectGraph}),
	 * and sleep duration over time ({@code sleepGraph}).
	 */
	protected Chart relayGraph, connectGraph, sleepGraph;

	/**
	 * Running sum ({@code sleepSum}) and total sum ({@code totalSleepSum}) of sleep
	 * intervals recorded across iterations, used for computing average sleep time.
	 */
	protected double sleepSum, totalSleepSum;

	/**
	 * Sample count for the current sleep window ({@code sleepDiv}) and for the
	 * full lifetime of this node ({@code totalSleepDiv}).
	 */
	protected int sleepDiv, totalSleepDiv;

	/** Cumulative wall-clock time in milliseconds spent executing jobs. */
	private long totalWorkTime;

	/** Cumulative wall-clock time in milliseconds spent on relay and connection activity. */
	private long totalComTime;

	/** Total number of jobs successfully completed by the worker thread. */
	private int totalJobs;

	/** Total number of jobs that threw an exception during execution. */
	private int totalErrJobs;

	/** Total number of jobs relayed to peers or to the parent NodeGroup. */
	private int totalRelay;

	/** Counter used to assign unique names to threads created by {@link #newThread(Runnable)}. */
	private int threadCount;

	/** The activity thread: manages peer connections and periodic relay decisions. */
	private final Thread nodeThread;

	/**
	 * The worker thread: pulls jobs from the queue and executes them one at a time.
	 * Set to {@code null} when this node has no parent (i.e. it is a standalone coordinator
	 * that never executes jobs directly).
	 */
	private Thread worker;

	/**
	 * Single-thread {@link ExecutorService} provided to each {@link Job} for internal
	 * parallelism.  Threads are named via {@link #newThread(Runnable)}.
	 */
	private ExecutorService pool;

	/** The {@link Job} currently being executed by the worker thread, or {@code null} if idle. */
	private Job currentJob;

	/** Optional Swing label used to display the most recent status message in a UI. */
	private JLabel label;

	/** The last status message produced by {@link #displayMessage(String, String)}, used to clear the UI label. */
	private String lastMessage;

	/** Human-readable name for this node, used in log and status output. */
	private String name;

	/** File path for the RSS log file written by {@link #writeLogFile(int)}. */
	protected String rssfile;

	/** RSS feed used for structured log output; shared with the parent {@link NodeGroup}. */
	protected RSSFeed log;

	/**
	 * Capability labels assigned to this node (e.g. {@code "platform" -> "macos"}).
	 * Used by {@link #satisfies(Map)} to determine whether this node may execute a
	 * given job's requirements.  Insertion order is preserved.
	 */
	private final Map<String, String> labels = new LinkedHashMap<>();

	/**
	 * Sets a label describing a capability of this Node.
	 *
	 * @param key   the label key (e.g. "platform")
	 * @param value the label value (e.g. "macos")
	 */
	public void setLabel(String key, String value) {
		labels.put(key, value);
	}

	/**
	 * Returns an unmodifiable view of the labels assigned to this Node.
	 *
	 * @return the labels map
	 */
	public Map<String, String> getLabels() {
		return Collections.unmodifiableMap(labels);
	}

	/**
	 * Returns true if this Node's labels satisfy the given requirements.
	 * An empty requirements map is satisfied unless this Node has the
	 * {@code role:relay} label, which marks it as a queue-only Node
	 * that never executes jobs.
	 *
	 * @param requirements the required label key-value pairs
	 * @return true if every requirement is matched by this Node's labels
	 */
	public boolean satisfies(Map<String, String> requirements) {
		// Relay nodes never execute jobs — they only queue and forward
		if ("relay".equals(labels.get("role"))) {
			return false;
		}

		if (requirements == null || requirements.isEmpty()) {
			return true;
		}
		for (Map.Entry<String, String> entry : requirements.entrySet()) {
			String value = labels.get(entry.getKey());
			if (value == null || !value.equals(entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Constructs a new Node object using the specified parent and id.
	 * 
	 * @param parent  Parent of this Node object. If set to null this node
	 *                will not start a thread for completing jobs.
	 * @param id  Integer id unique for the specified parent.
	 * @param maxJobs  Maximum number of jobs stored by this node before it starts sending jobs to peers.
	 * @param maxPeers  Maximum number of peers before this node stops adding connections.
	 */
	public Node(NodeGroup parent, int id, int maxJobs, int maxPeers) {
		this.parent = parent;
		this.id = id;
		this.maxJobs = maxJobs;
		this.maxPeers = maxPeers;
		
		this.peers = new HashSet();
		this.jobs = new ArrayList();
		this.listeners = new ArrayList();
		
		this.relayGraph = new Chart();
		this.relayGraph.setDivisions(40);
		this.connectGraph = new Chart();
		this.connectGraph.setDivisions(40);
		
		Client c = Client.getCurrentClient();
		ThreadGroup g = null;
		if (c != null) g = c.getServer().getThreadGroup();
		this.nodeThread = new Thread(g, this, "Node " + this.id + " Activity Thread");
		this.nodeThread.setPriority(Server.MODERATE_PRIORITY);
		
		this.worker = new Thread(() -> {
				while (true) {
					Job j = Node.this.nextJob();
					Node.this.currentJob = j;

					if (j != null) {
						// If this Node cannot satisfy the job's label
						// requirements, relay the job to the parent
						// NodeGroup so it can route it to a capable node.
						// Do NOT re-queue to self: the worker thread would
						// spin at thousands of iterations per second with
						// only Thread.yield() as back-pressure.
						if (!Node.this.satisfies(j.getRequiredLabels())) {
							Node.this.displayMessage("Relaying job "
								+ j.getTaskId()
								+ " -- labels mismatch");
							if (Node.this.parent != null) {
								Node.this.parent.addJob(j);
							}
							continue;
						}

						long start = System.currentTimeMillis();

						synchronized (Node.this.listeners) {
							Node.this.working = true;
							Node.this.startedWorking();
						}

						Node.this.working = true;

						boolean complete = false;

						try {
							j.setExecutorService(pool);
							j.run();
							complete = true;
						} catch (Exception e) {
							if (e instanceof RuntimeException)
								Node.this.displayMessage("Exception while working -- "
															+ e.getMessage());
							else
								Node.this.displayMessage("Exception while working -- ");

							e.printStackTrace();
							
							if (e instanceof NullPointerException) e.printStackTrace();
							Node.this.totalErrJobs++;
							Node.this.addFailedJob(j.encode());
						}
						
						Node.this.working = false;
						
						synchronized (Node.this.listeners) {
							Iterator itr = Node.this.listeners.iterator();
							while (itr.hasNext()) ((ActivityListener)itr.next()).stoppedWorking();
							
							if (Node.this.parent != null) {
								itr = Node.this.parent.listeners.iterator();
								while (itr.hasNext()) ((ActivityListener)itr.next()).stoppedWorking();
							}
						}
						
						Node.this.totalJobs++;
						
						long end = System.currentTimeMillis();
						long tot = end - start;
						Node.this.totalWorkTime += tot;
						
						if (complete) {
							Node.this.displayMessage("Completed job " + j + " in " +
											tot / 1000.0 + " seconds.");
						}
					} else {
						try {
							Thread.sleep(5000);
						} catch (InterruptedException ie) {
							System.out.println("Node " + Node.this.id + ": " + ie);
						}
					}
				}
		}, "Node " + this.id + " Worker Thread");
		this.worker.setPriority(Server.HIGH_PRIORITY);
		this.worker.setDaemon(true);

		this.pool = Executors.newFixedThreadPool(1, this);
		
		this.setSleep((int)(1.5 * this.minSleep));
		this.setRelayProbability(1.0);
		this.setConnectProbability(0.5);
		this.setMinJobs((int)(this.maxJobs * this.minJobP));
		
		if (this.parent != null) {
			this.rssfile = this.parent.rssfile;
			this.log = this.parent.log;
		} else {
			this.worker = null;
		}
		
		this.setName("Node " + this.id);
	}
	
	/**
	 * Starts the thread that manages the activity of this node.
	 */
	public Node start() {
		this.stop = false;
		this.nodeThread.start();
		return this;
	}
	
	/**
	 * Stops the thread that manages the activity of this node.
	 */
	public void stop() { this.stop = true; }
	
	/**
	 * @return  True if the thread that manages the activity of this node is alive.
	 */
	public boolean isAlive() { return this.nodeThread.isAlive(); }
	
	/**
	 * Registers an {@link ActivityListener} to receive callbacks for iteration,
	 * worker-start, worker-stop, and isolation events on this node.
	 *
	 * @param l the listener to add; must not be {@code null}
	 */
	public synchronized void addActivityListener(ActivityListener l) { this.listeners.add(l); }
	
	/**
	 * Tests wether a connection is maintained by this node that uses the specified
	 * NodeProxy object.
	 * 
	 * @param p  NodeProxy object to check for.
	 * @return  True if the NodeProxy is used, false otherwise.
	 */
	public boolean isConnected(NodeProxy p) {
		Iterator itr = this.peers.iterator();
		
		while (itr.hasNext()) {
			if (((Connection)itr.next()).getNodeProxy() == p) {
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Adds the specified Connection object to the list stored by this node.
	 * 
	 * @param c  The Connection object to add.
	 * @return  True if the Connection object was added, false if it was rejected as a duplicate.
	 */
	public boolean connect(Connection c) {
		if (this.peers.size() >= this.maxPeers) return false;
		
		Iterator itr = this.peers.iterator();
		
		while (itr.hasNext()) {
			Connection pc = (Connection) itr.next();
			
			if (c.getRemoteNodeId() == pc.getRemoteNodeId() &&
					c.getNodeProxy() == pc.getNodeProxy()) return false;
		}
		
		c.start();
		this.peers.add(c);
		
		this.displayMessage("Established connection " + c);
		
		return true;
	}
	
	/**
	 * Removes the specified Connection object from the list of peers stored by this node.
	 * 
	 * @param c  The Connection object to disconnect from.
	 * @return  The number of connections dropped.
	 */
	public int disconnect(Connection c) {
		int i = 0;
		
		synchronized (this.peers) {
			Iterator itr = this.peers.iterator();
			
			while (itr.hasNext()) {
				if (itr.next() == c) {
					itr.remove();
					i++;
				}
			}
		}
		
		return i;
	}
	
	/**
	 * Removes all peer connections from this node that are connected using the specified NodeProxy object.
	 * 
	 * @param p  NodeProxy object to disconnect from.
	 * @return  The number of connections dropped.
	 */
	public int disconnect(NodeProxy p) {
		int i = 0;
		
		synchronized (this.peers) {
			Iterator itr = this.peers.iterator();
			
			while (itr.hasNext()) {
				if (((Connection)itr.next()).getNodeProxy() == p) {
					itr.remove();
					i++;
				}
			}
		}
		
		return i;
	}
	
	/**
	 * @return Returns the name of this node.
	 */
	public String getName() { return name; }
	
	/**
	 * @param name The new name for this node.
	 */
	public void setName(String name) { this.name = name; }
	
	/**
	 * @return  The unique ID number of this Node object.
	 */
	public int getId() { return this.id; }
	
	/**
	 * @return  The NodeGroup object that is the parent for this Node object.
	 */
	public NodeGroup getParent() { return this.parent; }
	
	/**
	 * @return  An array of Connection objects that correspond to the peers
	 *          stored by this Node object.
	 */
	public Connection[] getPeers() {
		synchronized (this.peers) {
			return (Connection[])this.peers.toArray(new Connection[0]);
		}
	}
	
	
	/**
	 * Returns an internal object by name.  Supported keys are:
	 * <ul>
	 *   <li>{@code "jobs"}      — the job queue ({@link List}&lt;{@link Job}&gt;)</li>
	 *   <li>{@code "failed"}    — the failed-job list ({@link List})</li>
	 *   <li>{@code "listeners"} — the activity-listener list ({@link List})</li>
	 *   <li>{@code "current"}   — the job currently being executed ({@link Job})</li>
	 *   <li>{@code "parent"}    — the parent {@link NodeGroup}</li>
	 *   <li>{@code "name"}      — the node name ({@link String})</li>
	 *   <li>{@code "thread"}    — the activity thread ({@link Thread})</li>
	 *   <li>{@code "worker"}    — the worker thread ({@link Thread})</li>
	 *   <li>{@code "isWorking"} — whether the worker is active ({@link Boolean})</li>
	 * </ul>
	 *
	 * @param key the name of the object to retrieve
	 * @return the corresponding internal object, or {@code null} if the key is unknown
	 */
	// TODO Add jobs, failed, listeners, current, parent,
	//      name, thread, worker, and isWorking object
	//      values to documentation.
	public Object getObject(String key) {
		if (key.equals("jobs")) {
			return this.jobs;
		} else if (key.equals("failed")) {
			return this.failedJobs;
		} else if (key.equals("listeners")) {
			return this.listeners;
		} else if (key.equals("current")) {
			return this.currentJob;
		} else if (key.equals("parent")) {
			return this.parent;
		} else if (key.equals("name")) {
			return this.name;
		} else if (key.equals("thread")) {
			return this.nodeThread;
		} else if (key.equals("worker")) {
			return this.worker;
		} else if (key.equals("isWorking")) {
			return Boolean.valueOf(this.working);
		} else {
			return null;
		}
	}
	
	/**
	 * @return  The maximum number of peers that this Node object will maintain connections for.
	 */
	public int getMaxPeers() { return this.maxPeers; }
	
	/**
	 * Sets the minimum number of jobs that should remain in the queue before
	 * relay is considered.  Jobs will not be forwarded to peers when the queue
	 * size is at or below this threshold.
	 *
	 * @param min the minimum job count; must be non-negative
	 */
	public void setMinJobs(int min) { this.minJobs = min; }

	/**
	 * Returns the minimum queue size below which relay to peers is suppressed.
	 *
	 * @return the minimum job count
	 */
	public int getMinJobs() { return this.minJobs; }
	
	/**
	 * @return  The maximum number of jobs that this Node object will keep in the queue.
	 */
	public int getMaxJobs() { return this.maxJobs; }
	
	/**
	 * Sets the maximum number of jobs the queue may hold.  When the queue exceeds
	 * this limit the relay probability is doubled to encourage off-loading jobs to
	 * peers more aggressively.
	 *
	 * @param m the maximum job count; must be positive
	 */
	public void setMaxJobs(int m) { this.maxJobs = m; }

	/**
	 * Returns the maximum sleep-interval coefficient.  The effective maximum sleep
	 * is {@code minSleep * maxSleepC}.  Delegates to the parent {@link NodeGroup}
	 * when one is present so that all child nodes share the same cap.
	 *
	 * @return the maximum sleep coefficient
	 */
	public double getMaxSleepC() {
		if (getParent() != null) return getParent().getMaxSleepC();
		return maxSleepC;
	}

	/**
	 * Overrides the maximum sleep-interval coefficient for this node.
	 * Has no effect when a parent {@link NodeGroup} is present, because
	 * {@link #getMaxSleepC()} delegates to the parent in that case.
	 *
	 * @param msc the new maximum sleep coefficient
	 */
	public void setMaxSleepC(double msc) { this.maxSleepC = msc; }

	/**
	 * Sets the numerator coefficient of the activity-based sleep formula.
	 * Higher values cause the node to reduce its sleep interval more aggressively
	 * when the job queue grows.
	 *
	 * @param acs the activity sleep coefficient
	 */
	public void setActivitySleepC(double acs) { this.activitySleepC = acs; }

	/**
	 * Sets the contribution of the parent NodeGroup's peer-activity ratio to the
	 * sleep adjustment.  A value of {@code 0.0} disables the peer-activity influence.
	 *
	 * @param pacs the peer-activity sleep coefficient
	 */
	public void setPeerActivitySleepC(double pacs) { this.peerActivitySleepC = pacs; }

	/**
	 * Sets the probability that a relay attempt will route the job to the parent
	 * {@link NodeGroup} rather than to a random peer connection.
	 * Use values close to {@code 1.0} to strongly prefer parent relay,
	 * and {@code 0.0} to relay exclusively to peers.
	 *
	 * @param parp the parental relay probability, in [0.0, 1.0]
	 */
	public void setParentalRelayP(double parp) { this.parentalRelayP = parp; }

	/**
	 * Sets the coefficient that scales the relay probability contribution from
	 * peer connectivity.  When set to a positive value, a node with many peer
	 * connections will relay more frequently than a less-connected node.
	 *
	 * @param prc the peer relay coefficient
	 */
	public void setPeerRelayC(double prc) { this.peerRelayC = prc; }

	/**
	 * Sets the minimum job-queue fill ratio as a fraction of {@link #maxJobs}.
	 * Also resets the activity-sleep offset ({@code activitySleepO}) and
	 * the {@link #minJobs} threshold to be consistent with the new ratio.
	 *
	 * @param mjp the minimum job proportion, in (0.0, 1.0]
	 */
	public void setMinimumJobP(double mjp) {
		this.minJobP = mjp;
		this.activitySleepO = - this.minJobP;
		this.setMinJobs((int)(this.maxJobs * this.minJobP));
	}
	
	/**
	 * @param mfj  The maximum number of failed jobs to store. These jobs will be
	 *             reintialized and run again during periods of inactivity.
	 */
	public void setMaxFailedJobs(int mfj) {
		this.maxFailedJobs = mfj;
		
		if (this.maxFailedJobs > 0 && this.failedJobs == null)
			this.failedJobs = new ArrayList();
	}
	
	/**
	 * @param w  True if peers to relay should be chosen using weighted probability.
	 */
	public void setWeightPeers(boolean w) { this.weightPeers = w; }
	
	/**
	 * @return  [peers] / [max peers].
	 */
	public double getConnectivityRating() {
		return ((double)this.peers.size()) / ((double)this.maxPeers);
	}
	
	/**
	 * @return  1.0 + ([jobs in queue] - [min jobs]) / [max jobs].
	 */
	public double getActivityRating() {
		return 1 + (this.jobs.size() - this.minJobs) / (this.activityC * this.maxJobs);
	}
	
	/**
	 * @return  The number of jobs that have been completed by this node.
	 */
	public int getCompletedJobCount() { return this.totalJobs; }
	
	/**
	 * @return  The time that this node has spent working in msecs.
	 */
	public double getTimeWorked() { return this.totalWorkTime; }
	
	/**
	 * @return  The time that this node has spent communicating in msecs.
	 */
	public double getTimeCommunicated() { return this.totalComTime; }
	
	/**
	 * @return  The RSSFeed object stored by this node for logging.
	 */
	public RSSFeed getLog() { return this.log; }

	/**
	 * @return  A named thread for parallelizing work by {@link Job}s of this {@link Node}.
	 */
	@Override
	public Thread newThread(Runnable r) {
		return new Thread(r, getName() + " Parallelism Thread " + (threadCount++));
	}

	/**
	 * @return  True if this node is currently working on a job, false otherwise.
	 */
	public boolean isWorking() {
		if (this.worker == null || !this.worker.isAlive())
			return false;
		else
			return this.working;
	}
	
	/**
	 * Adds the specified job to the queue stored by this node. If this node
	 * is not currently working, this method will start the worker thread.
	 * 
	 * @param j  Job to be added.
	 * @return  The index in the queue of the job added.
	 */
	public int addJob(Job j) {
		if ("relay".equals(labels.get("role")) && this.parent != null) {
			this.displayMessage("Forwarding received job " + j + " to parent NodeGroup");
			return this.parent.addJob(j);
		}

		int id = -1;

		synchronized (this.jobs) {
			if (j == null) {
				this.displayMessage("Rejecting null job.");
				return -1;
			} else if (this.jobs.contains(j)) {
				this.displayMessage("Rejecting duplicate job " + j.getTaskId() + "--" + j);
				return -1;
			}

			if (this.jobs.add(j))
				id = this.jobs.size() - 1;
			else
				return id;
		}

		if (this.worker != null && !"relay".equals(labels.get("role"))) {
			synchronized (this.worker) {
				if (!this.working &&
						!this.worker.isAlive()) this.worker.start();
			}
		}
		
		if (this.parent != null) this.parent.addCachedTask(j.getTaskString());
		
		return id;
	}
	
	/**
	 * Adds a job to the list of failed jobs to be retried during inactivity.
	 * The String value will be converted to a Job instance using the
	 * Server.instantiateJobClass method.
	 * 
	 * @param jobData  String containing the encoded job.
	 * @return  The number of failed jobs stored by this node. -1 if this node
	 *          does not store failed jobs.
	 */
	protected synchronized int addFailedJob(String jobData) {
		if (this.failedJobs == null) {
			return -1;
		} else {
			this.failedJobs.add(jobData);
			int s = this.failedJobs.size();
			if (s > this.maxFailedJobs) this.failedJobs.remove(s - 1);
			return this.failedJobs.size();
		}
	}
	
	/**
	 * @return  Next job in the queue stored by this Node. If this node maintains
	 *          a list of failed jobs and there are no jobs left in the queue, the
	 *          last failed job will be instantiated (using Server.instantiateJobClass)
	 *          and returned by this method.
	 */
	public Job nextJob() {
		synchronized (this.jobs) {
			if (this.jobs.size() > 0)
				return prepareJob(this.jobs.remove(0));
		}
		
		if (this.failedJobs == null) return null;
		
		synchronized (this.failedJobs) {
			if (this.failedJobs.size() > 0)
				return prepareJob(Server.instantiateJobClass((String) this.failedJobs.remove(0)));
		}
		
		return null;
	}

	/**
	 * Prepares a job for execution by wiring it to the current {@link OutputServer}.
	 * This must be called on every job before it is handed to the worker thread or
	 * returned to any caller.
	 *
	 * @param j the job to prepare; must not be {@code null}
	 * @return the same job instance after configuration
	 */
	private Job prepareJob(Job j) {
		j.setOutputConsumer(OutputServer.getCurrentServer());
		return j;
	}
	
	/**
	 * Returns the {@link Job} currently being executed by the worker thread,
	 * or {@code null} if the worker is idle.
	 *
	 * @return the active job, or {@code null}
	 */
	public Job getCurrentJob() { return this.currentJob; }

	/**
	 * Removes all queued jobs with the specified task ID from this node's queue
	 * and broadcasts a {@link Message#Kill} message to all peer connections,
	 * propagating the kill signal through the network up to {@code relay} hops.
	 *
	 * @param task  the task ID string of the jobs to cancel
	 * @param relay the number of additional hops to relay the kill signal
	 */
	public void sendKill(String task, int relay) {
		synchronized (this.jobs) {
			this.jobs.removeIf(o -> o.getTaskId().equals(task));
		}
		
		Connection[] p = this.getPeers();
		
		try {
			Message msg = new Message(Message.Kill, this.id);
			msg.setString(task + JobFactory.ENTRY_SEPARATOR + relay);

			for (Connection connection : p) connection.sendMessage(msg);
		} catch (IOException ioe) {
			this.displayMessage("IO error sending kill signal (" + ioe.getMessage() + ")");
		}
	}
	
	/**
	 * @return  A Connection object randomly selected from the peer connections
	 *          maintained by this node. The selection will be weighted based on
	 *          the activity rating reported by the remote node group for each
	 *          connection. The least busy peer is the most likely to be returned,
	 *          assuming that the activity rating reported is less than 1. If all
	 *          connection report activity ratings greater than 1, a connection
	 *          will be selected randomly without weighting.
	 */
	public Connection getRandomPeer() {
		if (this.peers == null || this.peers.size() <= 0) return null;
		
		Connection[] c = (Connection[]) this.peers.toArray(new Connection[0]);
		int index = -1;
		
		double r = Math.random();
		
		if (this.weightPeers) {
			double[] w = new double[c.length];
			double sum = 0.0;
			
			for (int i = 0; i < c.length; i++) {
				w[i] = Math.max(0, 1 - c[i].getActivityRating());
				sum += w[i];
			}
			
			double t = 0.0;
			
			for (int i = 0; i < w.length; i++) {
				w[i] = w[i] / sum;
				t += w[i];
				
				if (r < t) {
					if (this.verbose || this.parent.verbose)
						this.displayMessage("Selected " + i + " = " + c[i] +
											" with weight " + w[i] + " (" +
											r + ", " + t + ")");
					
					return c[i];
				}
			}
			
			index = (int) (r * c.length);
			
			if (this.verbose || this.parent.verbose)
				this.displayMessage("Selected " + index + " = " + c[index] +
									" with sum = " + sum + " (" + r + ")");
		} else {
			index = (int) (r * c.length);
			
			if (this.verbose || this.parent.verbose)
				this.displayMessage("Randomly selected " + index + " = " +
									c[index] + " (" + r + ")");
		}
		
		if (index >= 0)
			return c[index];
		else
			return null;
	}
	
	/**
	 * Sets the time waited between attempts to work and/or connect by this node.
	 * 
	 * @param millis  Sleep time in milliseconds.
	 */
	public void setSleep(int millis) {
		this.sleep = (int) Math.max(this.minSleep, millis);
		double max = this.minSleep * getMaxSleepC();
		if (this.sleep > max) this.sleep = (int) max;
		
		if (this.verbose)
			System.out.println(this + ": Sleep = " + this.sleep);
	}
	
	/**
	 * Returns the current sleep interval used between activity-thread iterations.
	 *
	 * @return sleep duration in milliseconds
	 */
	public int getSleep() { return this.sleep; }
	
	/**
	 * Sets the probability that a connection will be requested during an iteration.
	 * This probability is multiplied by 1 - (peers / maxPeers) at run time.
	 * 
	 * @param p  Value between 0.0 and 1.0, with 1.0 corresponding to attempting connection every iteration.
	 */
	public void setConnectProbability(double p) { this.connect = p; }
	
	/**
	 * @return  A value between 0.0 and 1.0 corresponding to the probability that a connection is requested
	 *          during each iteration.
	 */
	public double getConnectProbability() { return this.connect; }
	
	/**
	 * Sets the probability that a job will be relayed during an iteration.
	 * If the job queue is full this probability is doubled.
	 * 
	 * @param p  Value between 0.0 and 1.0, with 1.0 corresponding to relaying every iteration.
	 */
	public void setRelayProbability(double p) { this.relay = p; }
	
	/**
	 * @return  A value between 0.0 and 1.0 corresponding to the probability that a job is relayed
	 *          during each iteration.
	 */
	public double getRelayProbability() { return this.relay; }
	
	/**
	 * @return  The JobFactory object stored by the parent of this node.
	 */
	public JobFactory getJobFactory() { return this.parent.getJobFactory(); }
	
	/**
	 * Displays the status specified message.
	 * 
	 * @param message  Message to display.
	 * @param image  URL of image.
	 */
	protected void displayMessage(String message, String image) {
		String s = Instant.now() + " [" + this + "]: " + message;
		
		this.lastMessage = s;
		
		System.out.println(s);
		
		if (this.log != null) {
			StringBuffer b = new StringBuffer();
			
			b.append(RSSFeed.startHtml);
			b.append("<h1>");
			b.append(this);
			b.append("</h1>");
			b.append("<br>\n");
			b.append("<h2>");
			b.append(message);
			b.append("</h2>");
			
			if (this.verboseNews) {
				b.append("<br>\n");
				b.append(this.getStatus("<br>\n"));
			}
			
			b.append(RSSFeed.endHtml);
			
			RSSFeed.Item item = new RSSFeed.Item(this.name, b.toString());
			item.setImage(image);
			
			this.log.postItem(item);
		}
		
		if (this.label != null) {
			this.label.setText("  " + s);
		}
	}
	
	/**
	 * Displays the specified status message.
	 * 
	 * @param message  Message to display.
	 */
	protected void displayMessage(String message) {
		this.displayMessage(message, null);
	}
	
	/**
	 * Clears the last message displayed.
	 */
	protected void clearMessage() {
		if (this.lastMessage == null) return;
		
		if (this.label != null && this.label.getText().endsWith(this.lastMessage))
			this.label.setText("  Status: Waiting...");
	}
	
	/**
	 * Sets the component which will display the last status message printed by this node.
	 * 
	 * @param label  JLabel component to display status messages.
	 */
	public void setStatusLabel(JLabel label) { this.label = label; }
	
	/**
	 * Prints the status of this network node to standard out.
	 */
	public void printStatus() { this.printStatus(System.out); }
	
	/**
	 * Prints the status of this network node using the specified PrintStream object.
	 * 
	 * @param out  PrintStream to use.
	 */
	public void printStatus(PrintStream out) {
		out.println(this.getStatus("<br>\n"));
	}
	
	/**
	 * Builds an HTML status report for this node, using {@code nl} as the
	 * line-break separator between sections (e.g. {@code "<br>\n"} for HTML or
	 * {@code "\n"} for plain text).  The report includes uptime fractions,
	 * job counts, communication time, peer list, and mini strip-chart graphs
	 * for relay and connect probabilities.
	 *
	 * @param nl the newline/separator string to use between status lines
	 * @return the HTML status string
	 */
	public String getStatus(String nl) {
		StringBuffer buf = new StringBuffer();
		
		buf.append("<h3>" + this + "</h3>");
		
		buf.append("<p>");
		buf.append("Sleep time: ");
		buf.append(Node.formatTime(this.sleep));
		buf.append(nl);
		
		if (this.isAlive())
			buf.append("Currently alive and ");
		else
			buf.append("Currently dead and ");
		
		if (this.isWorking())
			buf.append("working." + nl);
		else
			buf.append("not working." + nl);
		
		double workP = -1.0, comP = -1;
		Client c = Client.getCurrentClient();
		if (c != null) { // TODO  Should be able to do this even without a current client
			double up = c.getServer().getUptime();
			workP = this.totalWorkTime / up;
			comP = this.totalComTime / up;
		}
		
		buf.append("Worked for " + Node.formatTime(this.totalWorkTime));
		if (workP > 0.0) {
			buf.append(" (");
			buf.append(pFormat.format(workP));
			buf.append(")");
		}
		buf.append(" and completed " + this.totalJobs + " jobs");
		if (this.totalErrJobs > 0) {
			buf.append(" (");
			buf.append(this.totalErrJobs);
			buf.append(" errors).");
			buf.append(nl);
		} else {
			buf.append(".");
			buf.append(nl);
		}
		
		buf.append("Communicated for " + Node.formatTime(this.totalComTime));
		if (comP > 0.0) {
			buf.append(" (");
			buf.append(pFormat.format(comP));
			buf.append(")");
		}
		buf.append(" and relayed " + this.totalRelay + " jobs.");
		
		buf.append("</p>");
		
		if (this.peers != null) {
			Iterator itr = this.peers.iterator();
			int i = 0;
			
			while (itr.hasNext())
				buf.append("\t <b>Peer "  + i++ + "</b>: " + itr.next().toString() + nl);
		}
		
		double a = 0.0;
		
		if (this.relayGraph != null && this.relayDiv > 0) {
			a = this.relaySum / this.relayDiv;
			this.relaySum = 0.0;
			this.relayDiv = 0;
		}
		
		buf.append("<table><tr>");
		
		if (this.relayGraph != null) {
			this.relayGraph.addEntry(a);
			buf.append("<td><pre>");
			buf.append("<font size=\"-4\">" + nl);
			this.relayGraph.print(buf);
			buf.append("</font></pre></td>");
		}
		
		a = 0.0;
		
		if (this.connectGraph != null && this.connectDiv > 0) {
			a = this.connectSum / this.connectDiv;
			this.connectSum = 0.0;
			this.connectDiv = 0;
		}
		
		if (this.connectGraph != null) {
			this.connectGraph.addEntry(a);
			buf.append("<td><pre>");
			buf.append("<font size=\"-4\">" + nl);
			this.connectGraph.print(buf);
			buf.append("</font></pre></td>");
		}
		
		buf.append("</tr></table>");
		
		return buf.toString();
	}
	
	/**
	 * Writes the RSS log maintained by this node to the file at {@link #rssfile},
	 * trimming entries older than {@code ttl} items.  Does nothing if either
	 * {@code rssfile} or {@code log} is {@code null}.
	 *
	 * @param ttl the maximum number of RSS items to retain in the output file
	 */
	public void writeLogFile(int ttl) {
		if (this.rssfile == null || this.log == null) return;
		
		try (PrintStream p = new PrintStream(new FileOutputStream(this.rssfile))) {
			this.log.write(p, ttl);
			
			p.flush();
		} catch (FileNotFoundException fnf) {
			fnf.printStackTrace(System.out);
		}
	}
	
	/**
	 * Performs one activity-thread iteration for the given node: adjusts its sleep
	 * interval based on queue activity (or keeps it at {@link #minSleep} for relay
	 * nodes), then notifies all registered {@link ActivityListener}s.
	 * <p>
	 * Relay nodes are exempted from the activity-based sleep adjustment because
	 * they never execute jobs; applying the formula would cause sleep to grow
	 * unboundedly and slow down relay throughput.
	 * </p>
	 *
	 * @param n the node whose iteration is being performed; typically {@code this}
	 */
	public void iteration(Node n) {
		// Relay nodes should not adapt their sleep based on job
		// activity — they never execute jobs, so the activity
		// rating is meaningless and would cause sleep to grow
		// unboundedly. Keep sleep at minSleep for fast relay.
		if ("relay".equals(n.labels.get("role"))) {
			n.setSleep((int) n.minSleep);
		} else {
			double a = n.getActivityRating();
			double s = n.getSleep() * (
						(this.activitySleepC / (a + this.activitySleepO)) -
						(this.peerActivitySleepC *
						(Math.max(1.0 - n.getParent().getPeerActivityRatio(), 0.0)))
						);
			n.setSleep((int) s);
		}

		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).iteration(this);
		}
	}

	/**
	 * Notifies all registered {@link ActivityListener}s that this node's worker
	 * thread has begun executing a job, then propagates the event to the parent
	 * {@link NodeGroup} if one exists.
	 */
	public void startedWorking() {
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).startedWorking();

			if (this.parent != null) this.parent.startedWorking();
		}
	}

	/**
	 * Notifies all registered {@link ActivityListener}s that this node's worker
	 * thread has finished executing a job, then propagates the event to the parent
	 * {@link NodeGroup} if one exists.
	 */
	public void stoppedWorking() {
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).stoppedWorking();

			if (this.parent != null) this.parent.stoppedWorking();
		}
	}

	/**
	 * Notifies all registered {@link ActivityListener}s that this node has no
	 * remaining peer connections and is isolated from the network, then propagates
	 * the event to the parent {@link NodeGroup} if one exists.
	 */
	public void becameIsolated() {
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).becameIsolated();

			if (this.parent != null) this.parent.becameIsolated();
		}
	}
	
	/**
	 * Formats a duration in milliseconds as a human-readable string of the form
	 * {@code "M minutes and S.SSS seconds (N)"}, omitting the minutes component
	 * when the duration is less than one minute.
	 *
	 * @param msec the duration to format, in milliseconds
	 * @return the formatted time string
	 */
	public static String formatTime(double msec) {
		int min = (int) Math.floor(msec / 60000);
		double sec = Math.floor(msec % 60000);
		sec = sec / 1000.0;
		
		StringBuffer b = new StringBuffer();
		
		if (min > 0) {
			b.append(min);
			b.append(" minutes and ");
		}
		
		b.append(sec);
		b.append(" seconds (");
		b.append(msec);
		b.append(")");
		
		return b.toString();
	}
	
	/**
	 * Entry point for the activity thread.  On each iteration the thread:
	 * <ol>
	 *   <li>Calls {@link #iteration(Node)} to adjust sleep and notify listeners.</li>
	 *   <li>Sleeps for the computed interval (plus a parental contribution).</li>
	 *   <li>Probabilistically attempts to establish a new peer {@link Connection}
	 *       via the parent {@link NodeGroup}.</li>
	 *   <li>Probabilistically relays a job — either to a randomly selected peer
	 *       or to the parent {@link NodeGroup}, depending on {@link #parentalRelayP}
	 *       and peer availability.  Relay nodes always relay when jobs are present;
	 *       worker nodes relay only when the queue exceeds {@link #minJobs}.</li>
	 * </ol>
	 * The loop exits when {@link #stop()} has been called.
	 */
	public void run() {
		while (!this.stop) {
			this.iteration(this);
			
			try {
				int s = this.sleep;
				if (this.parent != null) s += (int)(this.parent.getSleep() * this.parentalSleepP);
				
				Thread.sleep(s);
			} catch (InterruptedException ie) {
				System.out.println("Node " + id + ": " + ie);
			}
			
			long start = System.currentTimeMillis();
			
			double r = this.connect * (1.0 - ((double)this.peers.size()) / this.maxPeers);
			
			this.connectSum += r;
			this.connectDiv++;
			
			if (this.parent != null && this.peers.size() < this.maxPeers && Math.random() < r) {
				Connection c = this.parent.getConnection(this.id);
				
				if (c != null) this.connect(c);
			}
			
			r = 0.0;

			int js = this.jobs.size();
			boolean isRelay = "relay".equals(labels.get("role"));

			if (isRelay) {
				// Relay nodes always relay when jobs are present
				r = js > 0 ? 1.0 : 0.0;
			} else {
				if (js > this.minJobs)
					r = this.relay;
				else if (js > this.maxJobs)
					r = this.relay * 2.0;

				r *= ((double) js) / (this.maxJobs - 1.0);
				r += this.peerRelayC * (((double) this.peers.size()) / this.maxPeers);
			}

			this.relaySum += r;
			this.relayDiv++;

			r: if ((isRelay ? js > 0 : js > this.minJobs) && Math.random() < r) {
				Connection c;

				if (Math.random() < this.parentalRelayP ||
					(c = this.getRandomPeer()) == null) {
					if (this.parent != null) {
						Job j = this.nextJob();
						this.displayMessage("Relaying job " + j
							+ " to parent NodeGroup");
						this.parent.addJob(j);
						break r;
					} else {
						this.displayMessage("No parent and no peers"
							+ " -- cannot relay");
					}
				} else {
					try {
						Job j = this.nextJob();

						if (j != null) {
							this.displayMessage("Relaying job "
								+ j.getTaskId()
								+ " to peer " + c);
							c.sendJob(j);
							this.totalRelay++;

							if (totalRelay % 20 == 0)
								this.displayMessage("Relayed 20 jobs (" + r + ").");
							else if (totalRelay == 1)
								this.displayMessage("Relayed first job (" + r + ").");

							break r;
						}
					} catch (SocketException se) {
						System.out.println("Node " + id + ": " + se.getMessage());
						this.peers.remove(c);
						this.displayMessage("Dropped peer connection " + c);
					} catch (IOException ioe) {
						System.out.println("Node " + id + ": " + ioe);
					}
				}
			}
			
			long end = System.currentTimeMillis();
			long tot = end - start;
			this.totalComTime += tot;
		}
	}
	
	/**
	 * @return  A String containing the number of peer connections and jobs queued by this network node.
	 */
	public String toString() {
		return "Network Node " + this.id + " -- " +
				this.peers.size() + "(" + this.maxPeers + ")" + " peers  " +
				this.jobs.size() + "(" + this.maxJobs + ")" + " jobs in queue";
	}
}
