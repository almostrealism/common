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
 * @author  Michael Murray
 */
// TODO  Implement JobQueue
public class Node implements Runnable, ThreadFactory {
	public static Random random = new Random();

	protected NumberFormat pFormat = NumberFormat.getPercentInstance();
	protected NumberFormat dFormat = new DecimalFormat("#.000");
	protected boolean verboseNews = true;
	protected boolean verbose = false;
	protected boolean weightPeers = false;
	
	protected double minSleep = 5000;
	private double maxSleepC = 300;
	private double activitySleepC = 1.2, activitySleepO = -0.4;
	private double peerActivitySleepC = 0.0;
	private final double activityC = 2.0;
	private double minJobP = 0.4;
	private double peerRelayC = 0.2;
	private double parentalRelayP = 0.0;
	private final double parentalSleepP = 0.0;
	
	public interface ActivityListener {
		void iteration(Node n);
		void startedWorking();
		void stoppedWorking();
		void becameIsolated();
	}
	
	protected NodeGroup parent;
	private final int id;
	
	private int minJobs;
	private int maxJobs;
	private final int maxPeers;
	private int maxFailedJobs;
	private double relay, connect;
	private final Set peers;
	protected final List<Job> jobs;
	protected List listeners;
	protected List failedJobs;
	
	private boolean stop, working;
	private int sleep;
	private double relaySum, connectSum;
	private int relayDiv, connectDiv;
	protected Chart relayGraph, connectGraph, sleepGraph;
	
	protected double sleepSum, totalSleepSum;
	protected int sleepDiv, totalSleepDiv;
	private long totalWorkTime, totalComTime;
	private int totalJobs, totalErrJobs, totalRelay;

	private int threadCount;
	private final Thread nodeThread;
	private Thread worker;
	private ExecutorService pool;
	private Job currentJob;
	
	private JLabel label;
	private String lastMessage;
	
	private String name;
	
	protected String rssfile;
	protected RSSFeed log;
	
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
	
	public void setMinJobs(int min) { this.minJobs = min; }
	
	public int getMinJobs() { return this.minJobs; }
	
	/**
	 * @return  The maximum number of jobs that this Node object will keep in the queue.
	 */
	public int getMaxJobs() { return this.maxJobs; }
	
	public void setMaxJobs(int m) { this.maxJobs = m; }

	public double getMaxSleepC() {
		if (getParent() != null) return getParent().getMaxSleepC();
		return maxSleepC;
	}
	
	public void setMaxSleepC(double msc) { this.maxSleepC = msc; }
	
	public void setActivitySleepC(double acs) { this.activitySleepC = acs; }
	
	public void setPeerActivitySleepC(double pacs) { this.peerActivitySleepC = pacs; }
	
	public void setParentalRelayP(double parp) { this.parentalRelayP = parp; }
	
	public void setPeerRelayC(double prc) { this.peerRelayC = prc; }
	
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
		
		if (this.worker != null) synchronized (this.worker) {
			if (!this.working &&
					!this.worker.isAlive()) this.worker.start();
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

	private Job prepareJob(Job j) {
		j.setOutputConsumer(OutputServer.getCurrentServer());
		return j;
	}
	
	public Job getCurrentJob() { return this.currentJob; }
	
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
	
	public void writeLogFile(int ttl) {
		if (this.rssfile == null || this.log == null) return;
		
		try (PrintStream p = new PrintStream(new FileOutputStream(this.rssfile))) {
			this.log.write(p, ttl);
			
			p.flush();
		} catch (FileNotFoundException fnf) {
			fnf.printStackTrace(System.out);
		}
	}
	
	public void iteration(Node n) {
		double a = n.getActivityRating();
		double s = n.getSleep() * (
					(this.activitySleepC / (a + this.activitySleepO)) -
					(this.peerActivitySleepC *
					(Math.max(1.0 - n.getParent().getPeerActivityRatio(), 0.0)))
					);
		
		n.setSleep((int) s);
		
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).iteration(this);
		}
	}

	public void startedWorking() {
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).startedWorking();
			
			if (this.parent != null) this.parent.startedWorking();
		}
	}

	public void stoppedWorking() {
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).stoppedWorking();
			
			if (this.parent != null) this.parent.stoppedWorking();
		}
	}

	public void becameIsolated() {
		synchronized (this.listeners) {
			Iterator itr = this.listeners.iterator();
			while (itr.hasNext()) ((ActivityListener)itr.next()).becameIsolated();
			
			if (this.parent != null) this.parent.becameIsolated();
		}
	}
	
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
			
			if (js > this.minJobs)
				r = this.relay;
			else if (js > this.maxJobs)
				r = this.relay * 2.0;
			
			r *= ((double)js) / (this.maxJobs - 1.0);
			r += this.peerRelayC * (((double)this.peers.size()) / this.maxPeers);
			
			this.relaySum += r;
			this.relayDiv++;

			r: if (js > this.minJobs && Math.random() < r) {
				Connection c;
				
				if (Math.random() < this.parentalRelayP ||
					(c = this.getRandomPeer()) == null) {
					if (this.parent != null) {
						Job j = this.nextJob();
						this.parent.addJob(j);
						break r;
					}
				} else {
					try {
						Job j = this.nextJob();
						
						if (j != null) {
							c.sendJob(j);
							this.totalRelay++;
							
							if (totalRelay % 20 == 0)
								this.displayMessage("Relayed 20 jobs (" + r + ").");
							else if (totalRelay == 1)
								this.displayMessage("Relayed a job (" + r + ").");
							
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
