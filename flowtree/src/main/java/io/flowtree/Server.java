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

package io.flowtree;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import io.almostrealism.db.DatabaseConnection;
import io.almostrealism.db.Query;
import io.almostrealism.persist.LocalResource;
import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import io.flowtree.airflow.AirflowJobFactory;
import io.flowtree.aws.CognitoLogin;
import io.flowtree.aws.Encryptor;
import io.flowtree.fs.DistributedResource;
import io.flowtree.fs.ImageResource;
import io.flowtree.fs.OutputServer;
import io.flowtree.fs.ResourceDistributionTask;
import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.Client;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import io.flowtree.www.TomcatNode;
import org.almostrealism.auth.Login;
import org.almostrealism.color.RGB;
import org.almostrealism.io.OutputHandler;
import org.almostrealism.texture.GraphicsConverter;

import javax.swing.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

// TODO Consider performing routine tasks (eg Garbage Collector, delete unused db rows, etc.)
//      during time when activity rating is low.

/**
 * A {@link Server} encapsulates a {@link NodeGroup} and manages a {@link Thread}
 * that waits for client connections.
 * 
 * @author  Michael Murrays
 */
public class Server implements JobFactory, Runnable {
	public interface ResourceProvider {
		Resource loadResource(String uri);
		Resource loadResource(String uri, String exclude);
	}
	

	protected static Set providers = new HashSet();
	
	private class ResourceServer implements Runnable {
		public static final int defaultPort = 7767;
		
		private final ServerSocket serv;
		private boolean end = false;
		
		public ResourceServer() throws IOException { this(defaultPort); }
		
		public ResourceServer(int port) throws IOException {
			this.serv = new ServerSocket(port);
		}
		
		public void end() { this.end = true; }
		
		public String getUri(String uri) {
			System.out.println("ResourceServer: Received request for " + uri);
			
			if (!uri.startsWith("/")) uri = "/" + uri;
			
			return "resource://" + Server.this.getLocalSocketAddress() +
					ENTRY_SEPARATOR + this.serv.getLocalPort() + uri;
		}
		
		public void addProvider(ResourceProvider p) { providers.add(p); }

		@Override
		public void run() {
			System.out.println("ResourceServer: Awaiting connections.");
			
			while (!end) {
				try {
					IOStreams io = new IOStreams(this.serv.accept());
					ResourceServerThread t = new ResourceServerThread(io);
					t.start();
					System.out.println("ResourceServer: Started " + t);
				} catch (IOException ioe) {
					System.out.println("Server: IO error sending resource (" +
										ioe.getMessage() + ")");
					ioe.printStackTrace();
				}
			}
		}
	}
	
	protected class ResourceServerThread extends Thread implements Runnable {
		private final IOStreams io;
		
		public ResourceServerThread(IOStreams io) { this.io = io; }
		
		public void run() {
			try {
				int hi = io.host.lastIndexOf("/");
				if (hi >= 0) io.host = io.host.substring(hi + 1);
				
				String uri = io.in.readUTF();
				
				if (Server.resourceVerbose)
					System.out.println("ResourceServer: Received request for " + uri);
				
				Object citem = Server.this.cache.get(uri);
				
				i: if (citem == null) {
//					s: synchronized (Server.this.cIndex) {
					s: {
						Iterator itr = Server.this.cIndex.entrySet().iterator();
						
						while (itr.hasNext()) {
							Map.Entry ent = (Map.Entry) itr.next();
							String key = (String) ent.getKey();
							
							if (uri.startsWith(key)) {
								String value = (String) ent.getValue();
								String nuri = value + uri.substring(key.length());
								System.out.println("ResourceServer: Found link " +
													uri + " --> " + nuri);
								citem = DistributedResource.createDistributedResource(uri);
								((DistributedResource)citem).loadFromStream(new URL(nuri).openStream());
								
	//							uri = nuri;
								
								break s;
							}
						}
						
						break i;
					}
					
	//				ImageResource res = new ImageResource(uri, null);
	//				citem = Server.this.loadResource(res, false);
				}
				
				Iterator itr = providers.iterator();
				
				while (citem == null && itr.hasNext())
					citem = ((ResourceProvider) itr.next()).loadResource(uri, io.host);
				
				if (!(citem instanceof Resource)) {
					io.out.writeInt(-1);
				} else {
					io.out.writeInt(1);
					Resource r = (Resource) citem;
					r.send(io);
				}
				
				io.close();
			} catch (IOException ioe) {
				System.out.println("Server: IO error sending resource (" +
									ioe.getMessage() + ")");
				ioe.printStackTrace();
			}
		}
	}
	
	public static final int defaultPort = 7766;
	
	public static final int HIGH_PRIORITY = 1;
	public static final int MODERATE_PRIORITY = 2;
	public static final int LOW_PRIORITY = 4;
	
	public static boolean resourceVerbose = true;
	
	private int maxCache = 10;
	private final String logCache;
	private final Map cache;
	private final Map cIndex;
	private final Map logItems;
	private final List loading;

	private final List<Login> logins;
	
	private final NodeGroup group;
	private ServerSocket socket;
	private ResourceServer rserver;

	private CompletableFuture<Void> future;
	
	private String hostname;
	private final long startTime;
	
	private double p;
	
	private boolean stop;
	private final ThreadGroup threads;
	private final Thread thread;
	private Thread rthread;
	
	private JLabel label;
	
	/**
	 * Main method which initializes the server.
	 * The full class name of the JobFactory to use
	 * can be replaced with "-p" to indicate that
	 * the server should opperate in passive mode.
	 * 
	 * @param args  {path to properties file, full classname for JobFactory}
	 */
	public static void main(String[] args) {
		Properties p = new Properties();

		if (args.length > 0) {
			try {
				p.load(new FileInputStream(args[0]));
			} catch (FileNotFoundException fnf) {
				System.out.println("Server: Properties file not found.");
				System.exit(1);
			} catch (IOException ioe) {
				System.out.println("Server: IO error loading properties file.");
				System.exit(2);
			}
		}
		
		JobFactory j = null;

		if (args.length < 2) {
			j = new AirflowJobFactory();
		} else if (!args[1].equals("-p")) {
			try {
				j = (JobFactory) Class.forName(args[1]).newInstance();
			} catch (InstantiationException ie) {
				System.out.println("Server: " + ie);
				System.exit(3);
			} catch (IllegalAccessException ia) {
				System.out.println("Server: " + ia);
				System.exit(4);
			} catch (ClassNotFoundException cnf) {
				System.out.println("Server: " + cnf);
				System.exit(5);
			} catch (ClassCastException cc) {
				System.out.println("Server: " + cc);
				System.exit(6);
			}
		}
		
		try {
			Server s = new Server(p, j);
			s.start();
		} catch (IOException ioe) {
			System.out.println("Server: " + ioe);
			System.exit(7);
		}
	}

	/**
	 * Constructs a new Server object. The server
	 * will operate in passive mode.
	 *
	 * @throws IOException  If an IO error occurs while opening a server socket.
	 */
	public Server() throws IOException {
		this(new Properties(), null);
	}

	/**
	 * Constructs a new Server object. The server
	 * will operate in passive mode.
	 *
	 * @param p  Properties to use for Server and NodeGroup.
	 * @throws IOException  If an IO error occurs while opening a server socket.
	 */
	public Server(Properties p) throws IOException {
		this(p, null);
	}
	
	/**
	 * Constructs a new Server object. If j is null, the server
	 * will operate in passive mode.
	 * 
	 * @param p  Properties to use for Server and NodeGroup.
	 * @param j  JobFactory to use for NodeGroup.
	 * @throws IOException  If an IO error occurs while opening a server socket.
	 */
	public Server(Properties p, JobFactory j) throws IOException {
		this.threads = new ThreadGroup("Network Server");
		
		this.cache = Collections.synchronizedMap(new Hashtable());
		this.cIndex = Collections.synchronizedMap(new Hashtable());
		this.loading = Collections.synchronizedList(new ArrayList());
		this.logItems = Collections.synchronizedMap(new Hashtable());
		
		if (j == null) {
			this.group = new NodeGroup(p, this);
		} else {
			this.group = new NodeGroup(p, j);
		}

		if (p.getProperty("nodes.tomcat.enabled", "false").equalsIgnoreCase("true")) {
			this.group.nodes().add(new TomcatNode(group, nodes().size()));
		}
		
		int port = Integer.parseInt(p.getProperty("server.port",
					String.valueOf(Server.defaultPort)));

		if (port > 0) {
			this.displayMessage("Opening server socket on port " + port);
			this.socket = new ServerSocket(port);
		}

		this.thread = new Thread(this.threads, this);
		this.thread.setName("Network Server");
		this.thread.setPriority(Server.LOW_PRIORITY);
		this.thread.setDaemon(true);

		this.logins = new ArrayList<>();

		if (System.getenv("AWS_ACCESS_KEY_ID") != null) {
			System.out.println("Server: Starting CognitoLogin...");

			try {
				Encryptor e = new Encryptor(System.getenv("AWS_ACCESS_KEY_ID"),
											System.getenv("AWS_SECRET_ACCESS_KEY"));

				final BasicAWSCredentials c = new BasicAWSCredentials(System.getenv("AWS_ACCESS_KEY_ID"),
																System.getenv("AWS_SECRET_ACCESS_KEY"));

				this.logins.add(new CognitoLogin(e, new AWSCredentialsProvider() {
					@Override public AWSCredentials getCredentials() {
							return c;
						}
					@Override public void refresh() { }
				}));
			} catch (NoSuchAlgorithmException nsa) {
				nsa.printStackTrace();
			}
		}
		
		String cs = p.getProperty("server.cache.max");
		if (cs != null) this.setMaxCache(Integer.parseInt(cs));
		
		this.logCache = p.getProperty("server.cache.logdir");
		
		if (p.getProperty("server.resource", "off").equals("on")) { // TODO  Default maybe should be "on"
			ResourceServer rs;
			
			String rsp = p.getProperty("server.resource.port");
			
			if (rsp == null)
				this.rserver = new ResourceServer();
			else
				this.rserver = new ResourceServer(Integer.parseInt(rsp));
			
			this.rthread = new Thread(this.threads, this.rserver);
			this.rthread.setName("Resource Server");
			this.rthread.setPriority(Server.MODERATE_PRIORITY);
			this.rthread.setDaemon(true);
		}
		
		Iterator<Entry<Object, Object>> itr = p.entrySet().iterator();
		w: while (itr.hasNext()) {
			Entry ent = itr.next();
			String key = (String) ent.getKey();
			if (!key.startsWith("resource://")) continue w;
			String value = (String) ent.getValue();
			key = key.substring(11);
			this.cIndex.put(key, value);
			System.out.println("Server: Added resource link " + key + " --> " + value);
		}
		
		if (!p.getProperty("server.resource.disableHttpRedirect", "no").equals("yes")) {
			this.cIndex.put("/http/", "http://");
		}

		this.startTime = System.currentTimeMillis();

		String s = p.getProperty("server.status.file");
		int sl = Integer.parseInt(p.getProperty("server.status.sleep", "1200"));
		int slr = Integer.parseInt(p.getProperty("server.status.samples", "4"));
		if (s != null) this.startWritingStatus(s, sl, slr);
	}
	
	public void setParam(Properties p) {
		Iterator itr = p.entrySet().iterator();
		while (itr.hasNext()) {
			Map.Entry e = (Map.Entry) itr.next();
			this.setParam((String) e.getKey(), (String) e.getValue());
		}
	}
	
	public boolean setParam(String name, String value) {
		if (this.group.setParam(name, value)) {
			//
		} else if (name.equals("db.verbose")) {
			DatabaseConnection.verbose = Boolean.parseBoolean(value);
		} else if (name.equals("server.hostname")) {
			this.hostname = value;
		} else if (name.equals("server.resource.verbose")) {
			// TODO  Add to documentation.
			boolean b = Boolean.parseBoolean(value);
			ResourceDistributionTask.verbose = b;
			DistributedResource.verbose = b;
			Server.resourceVerbose = b;
		} else if (name.equals("server.resource.io.verbose")) {
			// TODO  Add to documentation.
			boolean b = Boolean.parseBoolean(value);
			DistributedResource.ioVerbose = b;
		} else if (name.equals("servers.output.host")) {
			Client c = Client.getCurrentClient();
			if (c == null) return false;
			c.setOutputHost(value);
		} else if (name.equals("servers.output.port")){
			Client c = Client.getCurrentClient();
			if (c == null) return false;
			c.setOutputPort(Integer.parseInt(value));
		} else if (name.startsWith("resource://")) {
			name = name.substring(11);
			this.cIndex.put(name, value);
			System.out.println("Server: Added resource link " + name + " --> " + value);
		} else {
			return false;
		}
		
		return true;
	}
	
	public static IOStreams getHttpIOStreams(String uri) throws IOException {
		IOStreams io = new IOStreams();
		io.in = new DataInputStream(new URL(uri).openStream());
		return io;
	}
	
	public Object getObject(String key) {
		if (key.equals("server")) {
			return this;
		} else {
			return this.group.getObject(key);
		}
	}
	
	/**
	 * @return  The NodeGroup object stored by this Server object.
	 */
	public NodeGroup getNodeGroup() { return this.group; }
	
	protected Collection<Node> nodes() { return this.group.nodes(); }
	
	/**
	 * @return  The local address of this server. This seems to work in a different way
	 *          on all platforms. It may return "localhost", "127.0.0.1", or a valid hostname/ip.
	 */
	public String getLocalSocketAddress() {
		if (this.hostname != null) return this.hostname;
		
		try {
			return InetAddress.getLocalHost().toString();
		} catch (UnknownHostException e) {
			return "localhost";
		}
	}
	
	/**
	 * @return  The local port that this Server object accepts connections on.
	 */
	public int getPort() { return this.socket.getLocalPort(); }
	
	/**
	 * @return  An array of Strings containing the hostnames of the peers of this server.
	 */
	public String[] getPeers() {
		NodeProxy[] np = this.group.getServers();
		String[] names = new String[np.length];
		
		for (int i = 0; i < np.length; i++) names[i] = np[i].toString();
		
		return names;
	}
	
	/**
	 * Checks the input rate of a proxy maintained this server.
	 * 
	 * @param peer  Index in peers list of host to check input rate for.
	 * @return  The average number of messages recieved per minute from the specified
	 *          peer since the last time the input rate was checked.
	 */
	public double getInputRate(int peer) {
		NodeProxy p = this.group.getServers()[peer];
		return p.getInputRate();
	}
	
	/**
	 * Pings a host that is connected to this server.
	 * 
	 * @see  #getPeers()
	 * @param peer  Index of server.
	 * @param size  Size of packet in characters (pairs of bytes).
	 * @param timeout  Max time to wait for a response, in milliseconds.
	 * @return  The time, in milliseconds, to respond to the ping.
	 */
	public long ping(int peer, int size, int timeout) throws IOException {
		return this.group.ping(peer, size, timeout);
	}
	
	/**
	 * Pings a host that is connected to this server.
	 * 
	 * @see  #getPeers()
	 * @param peer  Index of server.
	 * @param size  Size of packet in characters (pairs of bytes).
	 * @param timeout  Max time to wait for a response, in milliseconds.
	 * @param n  The number of pings to perform.
	 * @return  {minimum, maximum, average, deviation, errors}.
	 *          The last value will be non zero if an error occured.
	 */
	public double[] ping(int peer, int size, int timeout, int n) {
		return this.group.getServers()[peer].ping(size, timeout, n);
	}
	
	/**
	 * Opens a socket connection to the specified host on the default port
	 * and adds the connection to the node group maintained by this Server object.
	 * 
	 * @param host  Hostname (or ip) to open.
	 * @return  True if the new connection was added, false otherwise.
	 * @throws UnknownHostException  If host is unknown.
	 * @throws IOException  If IO error occurs opening socket.
	 */
	public boolean open(String host) throws IOException {
		return this.group.addServer(new Socket(host, Server.defaultPort));
	}
	
	/**
	 * Opens a socket connection to the specified host on the specified port
	 * and adds the connection to the node group maintained by this Server object.
	 * 
	 * @param host  Hostname (or ip) to open.
	 * @param port  Port to use.
	 * @return  True if the new connection was added, false otherwise.
	 * @throws UnknownHostException  If host is unknown.
	 * @throws IOException  If IO error occurs opening socket.
	 */
	public boolean open(String host, int port) throws IOException {
		return this.group.addServer(new Socket(host, port));
	}
	
	/**
	 * Closes the connection maintained between this server and the specified peer.
	 * 
	 * @see  #getPeers()
	 * @param peer  Index of peer in peer list (See getPeers).
	 * @return  The total number of node connections dropped due to closing the group connection.
	 */
	public int close(int peer) { return this.group.removeServer(peer); }
	
	/**
	 * Prints the status of the NodeGroup object stored by this Server object.
	 */
	public void printStatus() { this.group.printStatus(); }
	
	/**
	 * Prints the status of the NodeGroup object stored by this Server object
	 * using the specified PrintStream object.
	 * 
	 * @param out  PrintStream object to use.
	 */
	public void printStatus(PrintStream out) {
		this.group.printStatus(out);
		
		Iterator itr = this.logItems.entrySet().iterator();
		
		while (itr.hasNext()) {
			Map.Entry ent = (Map.Entry) itr.next();
			
			out.print("<h3>");
			out.print(ent.getValue());
			out.print("</h3>");
			out.print("<pre>\n");
			out.print(ent.getKey());
			out.print("\n</pre>");
		}
	}
	
	/**
	 * Starts the thread that awaits and accepts connections and
	 * calls start on the underlying {@link NodeGroup} instance.
	 */
	public void start() {
		this.stop = false;
		this.thread.start();
		if (this.rthread != null) this.rthread.start();
		this.group.start();

		if (OutputServer.getCurrentServer() != null) {
			this.startResourceDist(OutputServer.getCurrentServer());
		}
	}
	
	/**
	 * Stops the thread that awaits and accepts connections and
	 * calls stop on the underlying {@link NodeGroup} instance.
	 */
	public void stop() {
		this.stop = true;
		this.group.stop();
		
		try {
			this.socket.close();
		} catch (IOException ioe) {
			System.out.println("Server: IO error closing socket (" + ioe.getMessage() + ")");
		}
		
		this.displayMessage("Stopped");
	}
	
	public ThreadGroup getThreadGroup() { return this.threads; }
	
	public String[] getThreadList() {
		Thread[] list = new Thread[this.threads.activeCount()];
		int j = threads.enumerate(list);
		
		String[] l = new String[list.length];
		for (int i = 0; i < j; i++) l[i] = list[i].getName();
		return l;
	}
	
	/**
	 * Adds the specified Object as a log item for this server. When the log file is printed,
	 * the toString method of the Object will be called and this information will be appended
	 * to the log file. This is useful for objects such as net.sf.j3d.util.Graph. The toString
	 * method of Graph prints the graph, so if a graph should be displayed in the log, the
	 * graph should be added using the addLogItem method.
	 * 
	 * @param title  String title for log item.
	 * @param o  The Object to add as a log item.
	 */
	public void addLogItem(String title, Object o) { this.logItems.put(o, title); }
	
	public void startWritingStatus(final String file, final int sleep, int r) {
		Server.this.getNodeGroup().startMonitor(Server.MODERATE_PRIORITY, (1000 * sleep) / r);
		
		Thread t = new Thread(this.threads, new Runnable() {
			public void run() {
				while (true) {
					try {
						Thread.sleep(sleep * 1000L);

						Server.this.writeStatus(file);
						Server.this.getNodeGroup().storeActivityGraph(new File(file + ".ac"));
						Server.this.getNodeGroup().storeSleepGraph(new File(file + ".sl"));
						Server.this.getNodeGroup().writeLogFile(sleep / 60);
					} catch (InterruptedException e) {
					} catch (IOException ioe) {
						System.out.println("Server: IO error writing status file (" +
								ioe.getMessage() + ").");
					}
				}
			}
		});
		
		t.setName("Status Output Thread");
		
		t.start();
	}
	
	public void writeStatus(String file) throws IOException {
		PrintStream p = new PrintStream(new FileOutputStream(file + "-stat.html"));
		this.printStatus(p);
		
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		
		if (t != null) {
			if (Message.verbose)
				System.out.println("Server: Writing status to distributed file system...");
			
			int index = file.lastIndexOf("/");
			if (index >= 0) file = file.substring(index + 1);
			
			if (this.hostname != null)
				file = file + "-" + this.hostname;
			
			long time = getStartTime() % 10000;
			
			OutputStream out = this.getOutputStream("/files/logs/" + file +
													"-" + time + "-stat.html");
			p = new PrintStream(out);
			this.printStatus(p);
			out.close();
		}
	}
	
	public boolean addTask(JobFactory task) {
		if (this.rserver != null && task instanceof ResourceProvider) {
			this.addResourceProvider((ResourceProvider) task);
			System.out.println("Server: Added resource provider " + task);
		}
		
		return this.group.addTask(task);
	}

	/**
	 * Send the task to this {@link Server}.
	 *
	 * @param data  Encoded JobFactory.
	 */
	public void sendTask(String data) {
		sendTask(data, -1);
	}

	/**
	 * Sends an encoded {@link JobFactory} instance to another server
	 * that this {@link Server} is connected to.
	 * 
	 * @param data  Encoded JobFactory.
	 * @param server  Server index.
	 */
	public void sendTask(String data, int server) {
		if (server == -1) {
			this.group.addTask(data);
		} else {
			this.group.sendTask(data, server);
		}
	}

	/**
	 * Sends an encoded {@link JobFactory} instance to this {@link Server}
	 * is connected to and adds its {@link OutputHandler}, if there is one,
	 * to the {@link OutputServer}.
	 *
	 * @param f  JobFactory to transmit.
	 */
	public void sendTask(JobFactory f) {
		sendTask(f, -1);
	}
	
	/**
	 * Sends an encoded {@link JobFactory} instance to a server that this Server object
	 * is connected to and adds its {@link OutputHandler}, if there is one, to the
	 * {@link OutputServer}.
	 * 
	 * @param f  JobFactory to transmit.
	 * @param server  Server index.
	 */
	public void sendTask(JobFactory f, int server) {
		if (server == -1) {
			this.group.addTask(f);
		} else {
			this.group.sendTask(f, server);
		}

		OutputHandler h = f.getOutputHandler();
		if (h != null) {
			OutputServer.getCurrentServer().addOutputHandler(h);
		}
	}
	
	/**
	 * Sends a kill signal to all servers connected to this one. Each server
	 * that receives a kill signal will remove any tasks or jobs that have
	 * the specified task id.
	 * 
	 * @param task  Task id to kill.
	 * @param relay  Relay count (depth in network).
	 */
	public void sendKill(String task, int relay) { this.group.sendKill(task, relay); }
	
	/**
	 * Queries each node of the node group for the current job being processed.
	 * 
	 * @return  A String representation of the current job for each node.
	 */
	public String[] getCurrentWork() { return this.group.getCurrentWork(); }
	
	/**
	 * Sends a ServerStatusQuery message requesting a peer list from the specified peer
	 * and returns an array of Strings containing the hostname/ip for each peer. The peer
	 * list should not include this server.
	 * 
	 * @param peer  The index of the peer to query.
	 * @return  The servers that the specified peer is connected to (not including this one).
	 * @throws IOException  If an {@link IOException} is thrown while sending the query.
	 */
	public String[] getPeerList(int peer) throws IOException {
		Message m = new Message(Message.ServerStatusQuery, -2,
								this.group.getServers()[peer]);
		m.setString("peers");
		List l = (List) m.send(-1);
		return (String[]) l.toArray(new String[0]);
	}
	
	/**
	 * Returns the last value a peer reported for average time to complete a job.
	 * 
	 * @param peer  Index of peer.
	 * @return  Average time for peer to complete a job.
	 */
	public double getJobTime(int peer) {
		return this.group.getServers()[peer].getJobTime();
	}

	/**
	 * Returns this time in milliseconds since the client was initialized.
	 */
	public long getUptime() { return System.currentTimeMillis() - this.startTime; }

	/**
	 * Returns the time in milliseconds (System.currentTimeMillis method) when the
	 * client was initialized.
	 */
	public long getStartTime() { return this.startTime; }
	
	/**
	 * Returns the last value a peer reported for group activity rating.
	 * 
	 * @param peer  Index of peer.
	 * @return  Activity rating of peer.
	 */
	public double getActivityRating(int peer) {
		return this.group.getServers()[peer].getActivityRating();
	}
	
	/**
	 * @return  The average time required to complete a job reported by
	 *          the peers connected to this server. (0.0 if no peers have
	 *          reported a job time measurement).
	 */
	public double getAveragePeerJobTime() {
		NodeProxy[] p = this.group.getServers();
		
		double sum = 0.0;
		int peers = 0;
		
		for (int i = 0; i < p.length; i++) {
			double j = p[i].getJobTime();
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
	 * @return  The average activity rating reported by the peers connected
	 *          to this server. (0.0 if no peers have reported an activity
	 *          rating measurement).
	 */
	public double getAveragePeerActivityRating() {
		return this.group.getAveragePeerActivityRating();
	}
	
	/**
	 * @return  The ratio of the average activity rating reported by the peers connected
	 *          to this server to the average activity rating of this server. (0.0 if no
	 *          peers have reported an activity rating measurement).
	 */
	public double getPeerActivityRatio() {
		return this.group.getPeerActivityRatio();
	}
	
	public Message getStatusMessage() throws IOException {

		String stat = "jobtime:" +
				this.group.getAverageJobTime() +
				";activity:" +
				this.group.getAverageActivityRating();
		
		Message m = new Message(Message.ServerStatus, -1);
		m.setString(stat);
		
		return m;
	}
	
	public void setMaxCache(int max) { this.maxCache = max; }
	public int getMaxCache() { return this.maxCache; }
	
	public Object loadFromCache(String uri) {
		Object s = null;

		for (int i = 0; ; ) {
			s = this.cache.get(uri);

			if (this.loading.contains(uri)) {
				try {
					int sleep = 1000;

					if (i == 0) {
						sleep = 1000;
						i++;
					} else if (i == 1) {
						sleep = 5000;
						i++;
					} else if (i == 2) {
						sleep = 10000;
						i++;
					} else if (i < 6) {
						sleep = 10000 * (int) Math.pow(2, i);
						i++;
					} else {
						sleep = 1200000;
					}

					Thread.sleep(sleep);

					System.out.println("Server: Waited " + sleep / 1000.0 +
							" seconds for " + uri);
				} catch (InterruptedException ie) {}
			} else if (s == null) {
				return null;
			} else {
				this.loading.remove(uri);
				return s;
			}
		}
	}
	
	public boolean cacheContains(String uri) { return this.cache.containsKey(uri); }
	
	/**
	 * Returns an OutputStream that can be used to write data to the specified uri on
	 * the distributed file system.
	 * 
	 * @param uri  URI to access.
	 * @return  An OutputStream to use.
	 * @throws IOException
	 */
	public OutputStream getOutputStream(String uri) throws IOException {
		return ResourceDistributionTask.getCurrentTask().getOutputStream(uri);
	}
	
	public void addResourceProvider(ResourceProvider p) { this.rserver.addProvider(p); }
	
	public boolean isDirectory(String uri) {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		if (t == null)
			return false;
		else
			return t.isDirectory(uri);
	}
	
	public String[] getChildren(String uri) {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		if (t == null)
			return null;
		else
			return t.getChildren(uri);
	}
	
	public Resource loadResource(String uri) throws IOException {
		IOStreams io = this.getResourceStream(uri, null);
		Resource res = ResourceDistributionTask.getCurrentTask().getResource(uri);
		if (res == null) res = DistributedResource.createDistributedResource(uri);
		return this.loadResourceFromIO(res, io, false);
		
		
//		return this.loadResource(uri, false);
	}
	
	public Resource loadResource(String uri, boolean tryLocal) {
		Resource res = ResourceDistributionTask.getCurrentTask().getResource(uri);
		if (res != null) return res;
		if (!tryLocal) return null;
		
		res = new LocalResource(uri);
		
		try {
			return this.loadResource(res);
		} catch (IOException e) {
			System.out.println("Server: IO error loading local resource (" +
								e.getMessage() + ")");
			return null;
		}
	}
	
	public Resource loadResource(Resource r) throws IOException {
		return this.loadResource(r, null, false);
	}
	
	public Resource loadResource(Resource r, boolean noCache) throws IOException {
		return this.loadResource(r, null, noCache);
	}
	
	public Resource loadResource(Resource r, String exclude, boolean noCache)
						throws IOException {
		if (r instanceof DistributedResource && this.loading.contains(r.getURI()))
			return null;
		
		Object o = this.loadFromCache(r.getURI());
		if (o != null) return (Resource) o;
		
		this.loading.add(r.getURI());
		
		IOStreams io = this.getResourceStream(r.getURI(), exclude);
		return this.loadResourceFromIO(r, io, noCache);
	}
	
	protected Resource loadResourceFromIO(Resource r, IOStreams io, boolean noCache) throws IOException {
		if (io != null) {
			r.load(io);
		} else if (r.getURI() != null && !r.getURI().startsWith("resource:")) {
			r.loadFromURI();
		} else {
			return DistributedResource.createDistributedResource(r.getURI());
		}
		
		if (!noCache) synchronized (this.cache) {
			Object c = null;
			
			if (this.cache.size() >= this.maxCache)
				c = this.cache.keySet().iterator().next();
			
			if (c != null) {
				this.cache.remove(c);
				System.out.println("Server: Removed cache of " + c);
			}
			
			this.cache.put(r.getURI(), r);
		}
		
		if (this.logCache != null) {
			try {
				String output = "cache/" + System.currentTimeMillis();
				
				System.out.print("Server: Writing " + output + ": ");
				r.saveLocal(output);
				System.out.println("Done");
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}
		
		this.loading.remove(r.getURI());
		
		return r;
	}
	
	public RGB[][] loadImage(String uri) {
		return this.loadImage(uri, 0, 0, 0, 0, false, false);
	}
	
	public RGB[][] loadImage(String uri, boolean noReturn) {
		return this.loadImage(uri, 0, 0, 0, 0, noReturn, false);
	}
	
	public RGB[][] loadImage(String uri, int x, int y, int w, int h) {
		return this.loadImage(uri, x, y, w, h, false, false);
	}
	
	/**
	 * Loads an image using the cache system managed by this Server object.
	 * To load an image using SCP, the uri must take the form:
	 *   scp://host|user|passwd/path
	 * Where host is the hostname or ip of the host to contact, user and passwd
	 * are the user and password to authenticate with, and path is the absolute
	 * path of the resource to load. For example:
	 *   scp://localhost|root|secure/usr/local/images/test.jpeg
	 * would log into localhost as root with password "secure" and download the
	 * jpeg image /usr/local/images/test.jpeg
	 * 
	 * An image can be loaded as a resource from a resource server running on
	 * remote Server instance. To achieve this, preface the uri to load with
	 * resource://. For example:
	 *   resource://10.0.0.1/http://asdf.com/image.jpeg
	 * Would load the image found at http://asdf.com/image.jpeg from the resource
	 * server running on 10.0.0.1, instead of from the actual site. This can be used
	 * with the scp:// prefix as well.
	 * 
	 * @param uri  URI of resource (starting with http://, scp://, or resource://).
	 * @param noReturn  Do not convert data to RGB and return it. Simply load data to cache.
	 * @param noCache  Do not cache the data loaded by this method. Simply load, convert, and return.
	 * @return  An RGB[][] containing the image data.
	 */
	public RGB[][] loadImage(String uri, int ix, int iy, int iw, int ih,
							boolean noReturn, boolean noCache) {
		ImageResource res = new ImageResource(uri, null, new Permissions());
		res.setWidth(iw);
		res.setHeight(ih);
		res.setX(ix);
		res.setY(iy);
		
		try {
			res = (ImageResource) this.loadResource(res, noCache);
		} catch (IOException ioe) {
			System.out.println("Server: Error loading image (" + ioe.getMessage() + ")");
			res = null;
		}
		
		if (res == null) return null;
		int[] data = (int[]) res.getData();
		
		if (!noReturn)
			return GraphicsConverter.convertToRGBArray(data, 2, 0, 0, data[0], data[1], data[0]);
		else
			return null;
	}

	
	public IOStreams getResourceStream(String uri) {
		return this.getResourceStream(uri, null);
	}
	
	public IOStreams getResourceStream(String uri, String exclude) {
		IOStreams io = null;
		
		NodeProxy[] p = this.getNodeGroup().getServers();
		
		i: for (int i = 0; i < p.length; i++) {
			String ad = p[i].toString();
			int adi = ad.lastIndexOf("/");
			if (adi > 0) ad = ad.substring(adi + 1);
			if (ad.equals(exclude)) continue i;
			
			try {
				Message m = new Message(Message.ResourceRequest, -2, p[i]);
				m.setString(uri);
				String s = (String) m.send(-1);
				
				if (s != null) {
					io = this.parseResourceUri(s);
					if (io != null) return io;
				}
			} catch (IOException ioe) {
				System.out.println("Server: Error making resource request (" +
									ioe.getMessage() + ")");
			}
		}
		
		return io;
	}
	
	public IOStreams getResourceStream(String host, int port, String uri) throws IOException {
		if (host == null || host.equals("") || host.equals("localhost"))
			return null;
		
		System.out.println("Server: Opening resource stream to " +
							host + " on " + port + " for " + uri);
		
		Socket s = new Socket(host, port);
		IOStreams io = new IOStreams();
		io.in = new DataInputStream(s.getInputStream());
		io.out = new DataOutputStream(s.getOutputStream());
		
		io.out.writeUTF(uri);
		
		System.out.println("Wrote request for " + uri);
		
		if (io.in.readInt() > 0)
			return io;
		else
			return null;
	}
	
	public IOStreams parseResourceUri(String uri) throws IOException {
		int index = uri.indexOf("/", 11);
		
		String serv = uri.substring(11, index);
		String host = serv;
		int port = ResourceServer.defaultPort;
		
		if (serv.contains(ENTRY_SEPARATOR)) {
			int cindex = serv.indexOf(ENTRY_SEPARATOR);
			host = serv.substring(0, cindex);
			port = Integer.parseInt(serv.substring(cindex + ENTRY_SEPARATOR.length()));
		}
		
		return this.getResourceStream(host, port, uri.substring(index + 1));
	}
	
	public String getResourceUri(String uri) {
		if (this.rserver == null)
			return null;
		else
			return this.rserver.getUri(uri);
	}
	
	public Message executeQuery(Query q) throws IOException {
		return executeQuery(q, NodeProxy.queryTimeout);
	}
	
	public Message executeQuery(Query q, long timeout) throws IOException {
		return executeQuery(q, null, timeout);
	}
	
	public Message executeQuery(Query q, NodeProxy p, long timeout) throws IOException {
		io.flowtree.fs.OutputServer dbs = io.flowtree.fs.OutputServer.getCurrentServer();
		
		StringBuffer result = new StringBuffer();
		
		if (dbs != null) {
			if (Message.verbose)
				System.out.println("Server: Executing " + q);
			
			Hashtable h = dbs.getDatabaseConnection().executeQuery(q);
			
			if (Message.verbose)
				System.out.println("Server: Received " + h.size() +
									" elements from query.");
			
			result.append(Query.toString(h));
			
			if (Message.verbose)
				System.out.println("Server: Query result contains " +
									result.length() + " characters.");
		}
		
		if (q.getRelay() > 0) {
			if (Message.verbose)
				System.out.println("Server: Relaying Query...");
			
			q.deincrementRelay();
			
			NodeProxy[] peers = this.group.getServers();
			
			i: for (int i = 0; i < peers.length; i++) {
				if (peers[i] == p) continue i;
				
				if (Message.verbose)
					System.out.println("Server: Writing " + q);
				
				peers[i].writeObject(q, -1);
				Message m = (Message) peers[i].waitForMessage(Message.StringMessage, null, timeout);
				
				if (Message.verbose)
					System.out.println("Server: Received " + m + " after waiting " +
										timeout + " msecs.");
				
				if (m != null && m.getData() != null && m.getData().length() > 0) {
					if (result.length() > 0) result.append(Query.sep);
					result.append(m.getData());
				}
			}
		}
		
		Message m = new Message(Message.StringMessage, -1);
		m.setString(result.toString());
		return m;
	}

	public ResourceDistributionTask startResourceDist(OutputServer server) {
		return startResourceDist(server, 10, 10000);
	}

	public ResourceDistributionTask startResourceDist(OutputServer server, int jobs, int jsleep) {
		if (ResourceDistributionTask.getCurrentTask() != null) return ResourceDistributionTask.getCurrentTask();
		ResourceDistributionTask rtask = new ResourceDistributionTask(server, jobs, jsleep);
		addTask(rtask);
		System.out.println("Server: Added task " + rtask);
		return rtask;
	}
	
	public void run() {
		if (socket == null) return;

		this.displayMessage("Awaiting connections.");
		
		w: while (!this.stop) {
			try {
				Socket s = this.socket.accept();
				this.displayMessage("Accepted connection from " + s.getInetAddress());
				this.group.addServer(s, true);
			} catch (IOException ioe) {
				System.out.println("Server: " + ioe);
				continue w;
			}
		}
		
		this.displayMessage("Thread stopped.");
	}
	
	/** @see io.flowtree.job.JobFactory#nextJob() */
	@Override
	public Job nextJob() { return this.group.nextJob(); }
	
	/** @see io.flowtree.job.JobFactory#createJob(java.lang.String) */
	public Job createJob(String data) { return Server.instantiateJobClass(data); }
	
	/** @return  0.0. */
	public double getCompleteness() { return 0.0; }
	
	/** @return  False. */
	public boolean isComplete() { return false; }
	
	/** @return  "Server". */
	public String getName() { return "Server"; }
	
	/** @return  null. */
	@Override
	public String getTaskId() { return null; }
	
	/** @return  The class name for this class. */
	public String encode() { return this.getClass().getName(); }
	
	/**
	 * Constructs a class of the type specified by full name in the first term
	 * of the specified data string and sets other properties accordingly.
	 * 
	 * @param data  Encoded job data.
	 * @return  Instance of Job created.
	 */
	public static Job instantiateJobClass(String data) {
		int index = data.indexOf(JobFactory.ENTRY_SEPARATOR);
		String className = data.substring(0, index);
		
		Job j = null;
		
		try {
			j = (Job)Class.forName(className).newInstance();
			
			boolean end = false;

			while (!end) {
				data = data.substring(index + JobFactory.ENTRY_SEPARATOR.length());
				index = data.indexOf(JobFactory.ENTRY_SEPARATOR);

				while (data.charAt(index + JobFactory.ENTRY_SEPARATOR.length()) == '/' || index > 0 && data.charAt(index - 1) == '\\') {
					index = data.indexOf(JobFactory.ENTRY_SEPARATOR, index + JobFactory.ENTRY_SEPARATOR.length());
				}

				String s = null;

				if (index <= 0) {
					s = data;
					end = true;
				} else {
					s = data.substring(0, index);
				}

				int k = s.indexOf(KEY_VALUE_SEPARATOR);
				int len = KEY_VALUE_SEPARATOR.length();

				if (k > 0) {
					String key = s.substring(0, k);
					String value = s.substring(k + len);
					j.set(key, value);
				} else {
					String key = s;
					String value = data.substring(index + len);
					j.set(key, value);
					end = true;
				}
			}
		} catch (Exception e) {
			System.out.println("Server: " + e);
		}
		
		return j;
	}
	
	public void setPriority(double p) { this.p = p; }
	public double getPriority() { return this.p; }

	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	/**
	 * Does nothing.
	 */
	public void set(String key, String value) { }
	
	/**
	 * Displays the specified status message.
	 * 
	 * @param message  Message to display.
	 */
	protected void displayMessage(String message) {
		System.out.println("Server: " + message);
		if (this.label != null) this.label.setText("Status: " + message);
	}
	
	/**
	 * Sets the component which will display the last status message printed by the server.
	 * 
	 * @param label
	 */
	public void setStatusLabel(JLabel label) {
		this.label = label;
		this.group.setStatusLabel(label);
	}
}
