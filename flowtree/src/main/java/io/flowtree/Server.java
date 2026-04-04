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
import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Resource;
import io.flowtree.aws.CognitoLogin;
import io.flowtree.aws.Encryptor;
import io.flowtree.fs.DistributedResource;
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
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputHandler;

import javax.swing.*;
import java.io.DataInputStream;
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
public class Server implements JobFactory, Runnable, ConsoleFeatures {
	/**
	 * Provides a mechanism for loading {@link Resource} objects by URI, optionally
	 * excluding a particular host from consideration. Implementations are registered
	 * with the {@link ResourceServer} and consulted when a resource request arrives.
	 */
	public interface ResourceProvider {
		/**
		 * Loads the resource identified by the given URI.
		 *
		 * @param uri  URI identifying the resource to load.
		 * @return  The loaded {@link Resource}, or {@code null} if this provider cannot
		 *          satisfy the request.
		 */
		Resource loadResource(String uri);

		/**
		 * Loads the resource identified by the given URI, excluding responses that
		 * originate from the specified host.
		 *
		 * @param uri      URI identifying the resource to load.
		 * @param exclude  Hostname to exclude when resolving the resource.
		 * @return  The loaded {@link Resource}, or {@code null} if this provider cannot
		 *          satisfy the request.
		 */
		Resource loadResource(String uri, String exclude);
	}

	/** Shared set of {@link ResourceProvider} instances consulted when serving resource requests. */
	protected static Set providers = new HashSet();
	
	/**
	 * A lightweight TCP server that listens for resource requests and dispatches
	 * each accepted connection to a {@link ResourceServerThread} for fulfillment.
	 * Registered {@link ResourceProvider} instances are queried in turn until one
	 * can satisfy the requested URI.
	 */
	private class ResourceServer implements Runnable {
		/** Default port on which the resource server listens for incoming connections. */
		public static final int defaultPort = 7767;

		/** Server socket that accepts incoming resource-request connections. */
		private final ServerSocket serv;

		/** Flag indicating that the accept loop should terminate. */
		private boolean end = false;

		/**
		 * Constructs a {@link ResourceServer} bound to the {@link #defaultPort}.
		 *
		 * @throws IOException  If the server socket cannot be opened.
		 */
		public ResourceServer() throws IOException { this(defaultPort); }

		/**
		 * Constructs a {@link ResourceServer} bound to the specified port.
		 *
		 * @param port  Port number to listen on.
		 * @throws IOException  If the server socket cannot be opened.
		 */
		public ResourceServer(int port) throws IOException {
			this.serv = new ServerSocket(port);
		}

		/**
		 * Signals the accept loop to stop accepting new connections.
		 */
		public void end() { this.end = true; }

		/**
		 * Returns a {@code resource://} URI that remote peers can use to request
		 * the resource at the given path from this server.
		 *
		 * @param uri  Path component of the resource URI.
		 * @return  A fully-qualified {@code resource://} URI string.
		 */
		public String getUri(String uri) {
			Server.this.log("ResourceServer: Received request for " + uri);
			
			if (!uri.startsWith("/")) uri = "/" + uri;
			
			return "resource://" + Server.this.getLocalSocketAddress() +
					ENTRY_SEPARATOR + this.serv.getLocalPort() + uri;
		}
		
		/**
		 * Registers an additional {@link ResourceProvider} that will be consulted
		 * when handling incoming resource requests.
		 *
		 * @param p  The provider to register.
		 */
		public void addProvider(ResourceProvider p) { providers.add(p); }

		/**
		 * Runs the accept loop, blocking on the server socket and spawning a new
		 * {@link ResourceServerThread} for each accepted connection.
		 */
		@Override
		public void run() {
			Server.this.log("ResourceServer: Awaiting connections.");

			while (!end) {
				try {
					IOStreams io = new IOStreams(this.serv.accept());
					ResourceServerThread t = new ResourceServerThread(io);
					t.start();
					Server.this.log("ResourceServer: Started " + t);
				} catch (IOException ioe) {
					Server.this.warn("IO error sending resource (" +
										ioe.getMessage() + ")");
				}
			}
		}
	}
	
	/**
	 * Handles a single resource request on its own thread.  After reading the requested
	 * URI from the client, the thread checks the server-side cache and then each registered
	 * {@link ResourceProvider} in turn.  If a matching {@link Resource} is found it is
	 * transmitted over the connection; otherwise a {@code -1} sentinel is written.
	 */
	protected class ResourceServerThread extends Thread implements Runnable {
		/** IO streams for communicating with the requesting client. */
		private final IOStreams io;

		/**
		 * Constructs a {@link ResourceServerThread} that will fulfil a request over the
		 * given IO streams.
		 *
		 * @param io  The IO streams wrapping the accepted client socket.
		 */
		public ResourceServerThread(IOStreams io) { this.io = io; }

		/**
		 * Reads the requested URI, resolves the resource through the cache and registered
		 * providers, and writes the result back to the client.
		 */
		public void run() {
			try {
				int hi = io.host.lastIndexOf("/");
				if (hi >= 0) io.host = io.host.substring(hi + 1);
				
				String uri = io.in.readUTF();
				
				if (Server.resourceVerbose)
					Server.this.log("ResourceServer: Received request for " + uri);
				
				Object citem = Server.this.resourceManager.getCache().get(uri);
				
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
								Server.this.log("ResourceServer: Found link " +
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
				Server.this.warn("IO error sending resource (" +
									ioe.getMessage() + ")");
			}
		}
	}
	
	/** Default TCP port on which the server listens for incoming peer connections. */
	public static final int defaultPort = 7766;

	/** Thread priority constant for high-importance server threads. */
	public static final int HIGH_PRIORITY = 1;

	/** Thread priority constant for moderately-important server threads such as the resource server. */
	public static final int MODERATE_PRIORITY = 2;

	/** Thread priority constant for low-importance threads such as the network accept loop. */
	public static final int LOW_PRIORITY = 4;

	/** When {@code true}, verbose logging is emitted for resource requests and transfers. */
	public static boolean resourceVerbose = true;

	/** Manages the in-memory resource cache and all resource loading operations. */
	private final ServerResourceManager resourceManager;

	/** Executes distributed database queries and relays them to peers. */
	private final ServerQueryExecutor queryExecutor;

	/** Writes periodic status HTML files and uploads them to the distributed file system. */
	private final ServerStatusWriter statusWriter;

	/** Index mapping URI prefixes to redirect targets for resource look-ups. */
	private final Map cIndex;

	/** Keyed collection of objects to include in the server status log output. Values are the display titles. */
	private final Map logItems;

	/** Login handlers (e.g. AWS Cognito) used to authenticate users. */
	private final List<Login> logins;

	/** The {@link NodeGroup} that manages the local compute nodes and peer connections. */
	private final NodeGroup group;

	/** Server socket that accepts incoming peer connections. {@code null} when the port is disabled. */
	private ServerSocket socket;

	/** Optional embedded resource server that serves cached resources to remote peers. */
	private ResourceServer rserver;

	/** A {@link CompletableFuture} that can be used to observe the lifetime of this server. */
	private CompletableFuture<Void> future;

	/** Optional override for the local hostname returned by {@link #getLocalSocketAddress()}. */
	private String hostname;

	/** Wall-clock time (milliseconds) when this server was constructed. */
	private final long startTime;

	/** Job priority value associated with this server when treated as a {@link io.flowtree.job.JobFactory}. */
	private double p;

	/** Flag that signals the accept loop to stop. */
	private boolean stop;

	/** Thread group that owns all server-managed threads. */
	private final ThreadGroup threads;

	/** The main accept-loop thread. */
	private final Thread thread;

	/** The resource server thread, or {@code null} if the resource server is disabled. */
	private Thread rthread;

	/** Optional Swing label that displays the last status message. */
	private JLabel label;
	
	/**
	 * Main method which initializes the server.
	 * The full class name of the JobFactory to use
	 * can be replaced with "-p" to indicate that
	 * the server should operate in passive mode.
	 *
	 * @param args  {path to properties file, full classname for JobFactory}
	 */
	public static void main(String[] args) {
		ServerLauncher.launch(args);
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
		
		this.cIndex = Collections.synchronizedMap(new Hashtable());
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
			log("Starting CognitoLogin...");

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
				warn("CognitoLogin initialization failed: " + nsa.getMessage());
			}
		}
		
		int maxCache = 10;
		String cs = p.getProperty("server.cache.max");
		if (cs != null) maxCache = Integer.parseInt(cs);

		this.resourceManager = new ServerResourceManager(this, maxCache,
				p.getProperty("server.cache.logdir"));
		this.queryExecutor = new ServerQueryExecutor(this);
		this.statusWriter = new ServerStatusWriter(this);
		
		if (p.getProperty("server.resource", "off").equals("on")) { // TODO  Default maybe should be "on"
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
			log("Added resource link " + key + " --> " + value);
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
	
	/**
	 * Applies all key-value pairs in the given {@link Properties} object to this
	 * server by delegating to {@link #setParam(String, String)} for each entry.
	 *
	 * @param p  Properties whose entries will be applied.
	 */
	public void setParam(Properties p) {
		Iterator itr = p.entrySet().iterator();
		while (itr.hasNext()) {
			Map.Entry e = (Map.Entry) itr.next();
			this.setParam((String) e.getKey(), (String) e.getValue());
		}
	}

	/**
	 * Applies a single named configuration parameter to this server or to the
	 * underlying {@link NodeGroup}.  Recognised parameter names include
	 * {@code db.verbose}, {@code server.hostname}, {@code server.resource.verbose},
	 * {@code server.resource.io.verbose}, {@code servers.output.host},
	 * {@code servers.output.port}, and any name starting with {@code resource://}.
	 *
	 * @param name   Parameter name.
	 * @param value  Parameter value.
	 * @return  {@code true} if the parameter was recognised and applied,
	 *          {@code false} otherwise.
	 */
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
			log("Added resource link " + name + " --> " + value);
		} else {
			return false;
		}
		
		return true;
	}
	
	/**
	 * Opens an HTTP connection to the specified URI and returns an {@link IOStreams}
	 * instance whose input stream is backed by the response body.
	 *
	 * @param uri  Full HTTP URL to open.
	 * @return  An {@link IOStreams} with its input stream connected to the URI.
	 * @throws IOException  If the connection cannot be established.
	 */
	public static IOStreams getHttpIOStreams(String uri) throws IOException {
		IOStreams io = new IOStreams();
		io.in = new DataInputStream(new URL(uri).openStream());
		return io;
	}

	/**
	 * Returns a named object from this server or its underlying {@link NodeGroup}.
	 * The special key {@code "server"} returns this {@link Server} instance itself;
	 * all other keys are delegated to {@link NodeGroup#getObject(String)}.
	 *
	 * @param key  Name of the object to retrieve.
	 * @return  The object associated with the given key, or {@code null} if not found.
	 */
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
	
	/**
	 * Returns the collection of {@link Node} instances managed by the underlying
	 * {@link NodeGroup}.
	 *
	 * @return  A live {@link Collection} of {@link Node} objects.
	 */
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
			warn("IO error closing socket (" + ioe.getMessage() + ")");
		}
		
		this.displayMessage("Stopped");
	}
	
	/**
	 * Returns the {@link ThreadGroup} that owns all threads managed by this server.
	 *
	 * @return  The server's {@link ThreadGroup}.
	 */
	public ThreadGroup getThreadGroup() { return this.threads; }

	/**
	 * Returns the names of all currently active threads in the server's
	 * {@link ThreadGroup}.
	 *
	 * @return  An array of thread name strings.
	 */
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
	
	/**
	 * Returns the optional hostname override used in status filenames and URIs,
	 * or {@code null} if no override has been configured.
	 *
	 * @return  The configured hostname, or {@code null}.
	 */
	String getHostname() { return this.hostname; }

	/**
	 * Starts a background thread that periodically writes a status HTML file and
	 * activity/sleep graphs. Also starts the {@link NodeGroup} activity monitor.
	 *
	 * @param file   Base path for status output files (e.g. {@code "/var/log/server"}).
	 *               The HTML file will be written to {@code file + "-stat.html"}.
	 * @param sleep  Interval between writes, in seconds.
	 * @param r      Number of monitor samples taken per {@code sleep} interval.
	 */
	public void startWritingStatus(String file, int sleep, int r) {
		this.statusWriter.startWritingStatus(file, sleep, r);
	}

	/**
	 * Writes the current server status as an HTML file and, if a
	 * {@link ResourceDistributionTask} is active, also uploads the status to the
	 * distributed file system.
	 *
	 * @param file   Base path for the output file. The HTML file is written to
	 *               {@code file + "-stat.html"}.
	 * @throws IOException  If writing the status file fails.
	 */
	public void writeStatus(String file) throws IOException {
		this.statusWriter.writeStatus(file);
	}
	
	/**
	 * Adds a {@link JobFactory} task to the underlying {@link NodeGroup}. If the task also
	 * implements {@link ResourceProvider} and a resource server is running, the task is
	 * additionally registered as a resource provider.
	 *
	 * @param task  The {@link JobFactory} to add.
	 * @return  {@code true} if the task was successfully added to the group.
	 */
	public boolean addTask(JobFactory task) {
		if (this.rserver != null && task instanceof ResourceProvider) {
			this.addResourceProvider((ResourceProvider) task);
			log("Added resource provider " + task);
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
			if (j > 0) { sum += j; peers++; }
		}
		return peers > 0 ? sum / peers : 0.0;
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
	
	/**
	 * Constructs a {@link Message} of type {@link Message#ServerStatus} containing
	 * the current average job time and activity rating of this server's node group.
	 *
	 * @return  A {@link Message} encoding the server status.
	 * @throws IOException  If an IO error occurs while building the message.
	 */
	public Message getStatusMessage() throws IOException {
		String stat = "jobtime:" + this.group.getAverageJobTime()
				+ ";activity:" + this.group.getAverageActivityRating();
		Message m = new Message(Message.ServerStatus, -1);
		m.setString(stat);
		return m;
	}

	/**
	 * Sets the maximum number of resources to retain in the in-memory cache.
	 *
	 * @param max  Maximum cache size.
	 */
	public void setMaxCache(int max) { this.resourceManager.setMaxCache(max); }

	/**
	 * Returns the maximum number of resources retained in the in-memory cache.
	 *
	 * @return  Maximum cache size.
	 */
	public int getMaxCache() { return this.resourceManager.getMaxCache(); }

	/**
	 * Retrieves a resource from the cache by URI.  If the resource is currently
	 * being loaded by another thread, this method blocks with an exponential
	 * back-off until the load completes and the entry appears in the cache.
	 *
	 * @param uri  URI of the resource to look up.
	 * @return  The cached resource object, or {@code null} if the URI is not in the cache.
	 */
	public Object loadFromCache(String uri) {
		return this.resourceManager.loadFromCache(uri);
	}

	/**
	 * Returns {@code true} if the in-memory cache contains an entry for the given URI.
	 *
	 * @param uri  URI to test.
	 * @return  {@code true} if the URI is cached, {@code false} otherwise.
	 */
	public boolean cacheContains(String uri) { return this.resourceManager.cacheContains(uri); }
	
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
	
	/**
	 * Registers the given {@link ResourceProvider} with the embedded resource server
	 * so that it will be consulted when handling incoming resource requests.
	 *
	 * @param p  The provider to register.
	 */
	public void addResourceProvider(ResourceProvider p) { this.rserver.addProvider(p); }

	/**
	 * Returns {@code true} if the given URI represents a directory in the distributed
	 * file system managed by the current {@link ResourceDistributionTask}.
	 *
	 * @param uri  URI to test.
	 * @return  {@code true} if the URI is a directory, {@code false} if not or if no
	 *          distribution task is active.
	 */
	public boolean isDirectory(String uri) {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		if (t == null)
			return false;
		else
			return t.isDirectory(uri);
	}

	/**
	 * Returns the child URIs of the given directory URI in the distributed file system
	 * managed by the current {@link ResourceDistributionTask}.
	 *
	 * @param uri  URI of the directory to list.
	 * @return  An array of child URI strings, or {@code null} if no distribution task
	 *          is active or the URI does not represent a directory.
	 */
	public String[] getChildren(String uri) {
		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		if (t == null)
			return null;
		else
			return t.getChildren(uri);
	}
	
	/**
	 * Loads the resource identified by the given URI via the distributed file system.
	 * The resource stream is obtained from a connected peer and the result is cached.
	 *
	 * @param uri  URI of the resource to load.
	 * @return  The loaded {@link Resource}.
	 * @throws IOException  If an IO error occurs while loading the resource.
	 */
	public Resource loadResource(String uri) throws IOException {
		return this.resourceManager.loadResource(uri);
	}

	/**
	 * Loads the resource identified by the given URI from the distributed file system,
	 * optionally falling back to the local filesystem.
	 *
	 * @param uri       URI of the resource to load.
	 * @param tryLocal  If {@code true} and the resource is not found remotely, attempts
	 *                  to load it from the local filesystem via its URI.
	 * @return  The loaded {@link Resource}, or {@code null} if not found and
	 *          {@code tryLocal} is {@code false}.
	 */
	public Resource loadResource(String uri, boolean tryLocal) {
		return this.resourceManager.loadResource(uri, tryLocal);
	}

	/**
	 * Loads the given {@link Resource}, checking the cache first and fetching from
	 * a remote peer if necessary. The result is added to the cache.
	 *
	 * @param r  The resource descriptor to load.
	 * @return  The loaded {@link Resource}.
	 * @throws IOException  If an IO error occurs while loading the resource.
	 */
	public Resource loadResource(Resource r) throws IOException {
		return this.resourceManager.loadResource(r);
	}

	/**
	 * Loads the given {@link Resource}, optionally skipping the cache.
	 *
	 * @param r        The resource descriptor to load.
	 * @param noCache  If {@code true}, the loaded resource will not be stored in the cache.
	 * @return  The loaded {@link Resource}.
	 * @throws IOException  If an IO error occurs while loading the resource.
	 */
	public Resource loadResource(Resource r, boolean noCache) throws IOException {
		return this.resourceManager.loadResource(r, noCache);
	}

	/**
	 * Loads the given {@link Resource}, excluding a specific host when searching for the
	 * resource stream among connected peers.
	 *
	 * @param r        The resource descriptor to load.
	 * @param exclude  Hostname of a peer to skip when querying for the resource stream.
	 * @param noCache  If {@code true}, the loaded resource will not be stored in the cache.
	 * @return  The loaded {@link Resource}, or {@code null} if already in progress.
	 * @throws IOException  If an IO error occurs while loading the resource.
	 */
	public Resource loadResource(Resource r, String exclude, boolean noCache)
						throws IOException {
		return this.resourceManager.loadResource(r, exclude, noCache);
	}

	/**
	 * Loads a resource using the provided {@link IOStreams}, or from the resource's own
	 * URI if the streams are {@code null}. After loading, the resource is optionally
	 * placed in the in-memory cache and persisted to the log-cache directory.
	 *
	 * @param r        The resource descriptor to populate.
	 * @param io       IO streams to read the resource data from, or {@code null} to load
	 *                 directly from the resource's URI.
	 * @param noCache  If {@code true}, the loaded resource will not be stored in the cache.
	 * @return  The loaded {@link Resource}.
	 * @throws IOException  If an IO error occurs while loading the resource.
	 */
	protected Resource loadResourceFromIO(Resource r, IOStreams io, boolean noCache) throws IOException {
		return this.resourceManager.loadResourceFromIO(r, io, noCache);
	}

	/**
	 * Loads the full image at the given URI and returns it as an {@link RGB} array.
	 *
	 * @param uri  URI of the image to load.
	 * @return  A two-dimensional {@link RGB} array containing the image data, or
	 *          {@code null} if the image could not be loaded.
	 */
	public RGB[][] loadImage(String uri) {
		return this.resourceManager.loadImage(uri);
	}

	/**
	 * Loads the image at the given URI, optionally suppressing the return value.
	 * This can be used to pre-populate the cache without the overhead of converting
	 * image data to an {@link RGB} array.
	 *
	 * @param uri       URI of the image to load.
	 * @param noReturn  If {@code true}, loads the image into the cache but returns
	 *                  {@code null} instead of the pixel data.
	 * @return  A two-dimensional {@link RGB} array, or {@code null} if loading fails
	 *          or {@code noReturn} is {@code true}.
	 */
	public RGB[][] loadImage(String uri, boolean noReturn) {
		return this.resourceManager.loadImage(uri, noReturn);
	}

	/**
	 * Loads a rectangular sub-region of the image at the given URI.
	 *
	 * @param uri  URI of the image to load.
	 * @param x    X offset of the sub-region within the image.
	 * @param y    Y offset of the sub-region within the image.
	 * @param w    Width of the sub-region in pixels.
	 * @param h    Height of the sub-region in pixels.
	 * @return  A two-dimensional {@link RGB} array for the requested sub-region, or
	 *          {@code null} if the image could not be loaded.
	 */
	public RGB[][] loadImage(String uri, int x, int y, int w, int h) {
		return this.resourceManager.loadImage(uri, x, y, w, h);
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
	 * <p>An image can be loaded as a resource from a resource server running on
	 * a remote Server instance. To achieve this, preface the uri to load with
	 * resource://. For example:
	 *   resource://10.0.0.1/http://asdf.com/image.jpeg
	 * Would load the image found at http://asdf.com/image.jpeg from the resource
	 * server running on 10.0.0.1, instead of from the actual site.
	 *
	 * @param uri       URI of resource (starting with http://, scp://, or resource://).
	 * @param ix        X offset of the sub-region within the image.
	 * @param iy        Y offset of the sub-region within the image.
	 * @param iw        Width of the sub-region in pixels.
	 * @param ih        Height of the sub-region in pixels.
	 * @param noReturn  Do not convert data to RGB and return it. Simply load data to cache.
	 * @param noCache   Do not cache the data loaded by this method. Simply load, convert, and return.
	 * @return  An RGB[][] containing the image data.
	 */
	public RGB[][] loadImage(String uri, int ix, int iy, int iw, int ih,
							boolean noReturn, boolean noCache) {
		return this.resourceManager.loadImage(uri, ix, iy, iw, ih, noReturn, noCache);
	}

	/**
	 * Queries all connected peers for a resource stream matching the given URI.
	 *
	 * @param uri  URI of the resource to request.
	 * @return  An open {@link IOStreams} from the first peer that has the resource,
	 *          or {@code null} if no peer can satisfy the request.
	 */
	public IOStreams getResourceStream(String uri) {
		return this.resourceManager.getResourceStream(uri);
	}

	/**
	 * Queries all connected peers (except the excluded host) for a resource stream
	 * matching the given URI.
	 *
	 * @param uri      URI of the resource to request.
	 * @param exclude  Hostname of the peer to skip, or {@code null} to query all peers.
	 * @return  An open {@link IOStreams} from the first qualifying peer, or {@code null}
	 *          if no peer can satisfy the request.
	 */
	public IOStreams getResourceStream(String uri, String exclude) {
		return this.resourceManager.getResourceStream(uri, exclude);
	}

	/**
	 * Opens a direct TCP connection to a resource server at the specified host and port
	 * and requests the resource at the given URI path.
	 *
	 * @param host  Hostname or IP address of the resource server.
	 * @param port  Port number of the resource server.
	 * @param uri   URI path of the resource to request.
	 * @return  An open {@link IOStreams} if the server has the resource, or {@code null}
	 *          if the host is localhost/empty or the server reports the resource is absent.
	 * @throws IOException  If a network error occurs while connecting or communicating.
	 */
	public IOStreams getResourceStream(String host, int port, String uri) throws IOException {
		return this.resourceManager.getResourceStream(host, port, uri);
	}

	/**
	 * Parses a {@code resource://} URI and opens an {@link IOStreams} connection to the
	 * resource server it refers to.  The URI format is:
	 * {@code resource://<host>[<ENTRY_SEPARATOR><port>]/<path>}.
	 *
	 * @param uri  A {@code resource://} URI as returned by {@link ResourceServer#getUri(String)}.
	 * @return  An open {@link IOStreams} connected to the resource server, or {@code null}
	 *          if the server does not have the resource.
	 * @throws IOException  If a network error occurs.
	 */
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
		return this.resourceManager.getResourceStream(host, port, uri.substring(index + 1));
	}

	/**
	 * Returns a {@code resource://} URI for the given resource path that remote peers
	 * can use to request the resource from the embedded {@link ResourceServer}.
	 *
	 * @param uri  Path component of the resource.
	 * @return  A fully-qualified {@code resource://} URI, or {@code null} if no resource
	 *          server is running.
	 */
	public String getResourceUri(String uri) {
		if (this.rserver == null)
			return null;
		else
			return this.rserver.getUri(uri);
	}

	/**
	 * Executes the given {@link Query} locally and, if the query's relay count is
	 * greater than zero, relays it to all connected peers. Uses the default query timeout.
	 *
	 * @param q  The query to execute.
	 * @return  A {@link Message} containing the aggregated query result string.
	 * @throws IOException  If an IO error occurs while relaying the query.
	 */
	public Message executeQuery(Query q) throws IOException {
		return this.queryExecutor.executeQuery(q);
	}

	/**
	 * Executes the given {@link Query} locally and, if the query's relay count is
	 * greater than zero, relays it to all connected peers using the specified timeout.
	 *
	 * @param q        The query to execute.
	 * @param timeout  Maximum time in milliseconds to wait for each peer's response.
	 * @return  A {@link Message} containing the aggregated query result string.
	 * @throws IOException  If an IO error occurs while relaying the query.
	 */
	public Message executeQuery(Query q, long timeout) throws IOException {
		return this.queryExecutor.executeQuery(q, timeout);
	}

	/**
	 * Executes the given {@link Query} against the local output server and optionally
	 * relays it to all connected peers except the one that originated the query.
	 *
	 * @param q        The query to execute.
	 * @param p        The peer {@link NodeProxy} that sent the query (excluded from relay),
	 *                 or {@code null} to relay to all peers.
	 * @param timeout  Maximum time in milliseconds to wait for each peer's response.
	 * @return  A {@link Message} containing the aggregated query result string.
	 * @throws IOException  If an IO error occurs while relaying the query.
	 */
	public Message executeQuery(Query q, NodeProxy p, long timeout) throws IOException {
		return this.queryExecutor.executeQuery(q, p, timeout);
	}

	/**
	 * Starts a {@link ResourceDistributionTask} with default concurrency settings
	 * (10 jobs, 10-second sleep interval) if one is not already running.
	 *
	 * @param server  The {@link OutputServer} to distribute resources through.
	 * @return  The active (possibly pre-existing) {@link ResourceDistributionTask}.
	 */
	public ResourceDistributionTask startResourceDist(OutputServer server) {
		return startResourceDist(server, 10, 10000);
	}

	/**
	 * Starts a {@link ResourceDistributionTask} if one is not already running.
	 *
	 * @param server  The {@link OutputServer} to distribute resources through.
	 * @param jobs    Maximum number of concurrent distribution jobs.
	 * @param jsleep  Sleep interval between distribution cycles, in milliseconds.
	 * @return  The active (possibly pre-existing) {@link ResourceDistributionTask}.
	 */
	public ResourceDistributionTask startResourceDist(OutputServer server, int jobs, int jsleep) {
		if (ResourceDistributionTask.getCurrentTask() != null) return ResourceDistributionTask.getCurrentTask();
		ResourceDistributionTask rtask = new ResourceDistributionTask(server, jobs, jsleep);
		addTask(rtask);
		log("Added task " + rtask);
		return rtask;
	}

	/**
	 * Runs the connection accept loop. Blocks on the server socket and hands each
	 * accepted connection to the {@link NodeGroup} for peer registration. The loop
	 * exits when {@link #stop()} is called.
	 */
	public void run() {
		if (socket == null) return;

		this.displayMessage("Awaiting connections.");
		
		w: while (!this.stop) {
			try {
				Socket s = this.socket.accept();
				this.displayMessage("Accepted connection from " + s.getInetAddress());
				this.group.addServer(s, true);
			} catch (IOException ioe) {
				warn(ioe.getMessage());
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
	 * @return  Instance of {@link Job} created, or {@code null} if instantiation fails.
	 */
	public static Job instantiateJobClass(String data) {
		return JobClassLoader.instantiateJobClass(data);
	}
	
	/**
	 * Sets the job priority for this server when it is treated as a
	 * {@link io.flowtree.job.JobFactory}.
	 *
	 * @param p  Priority value to assign.
	 */
	public void setPriority(double p) { this.p = p; }

	/**
	 * Returns the job priority for this server when it is treated as a
	 * {@link io.flowtree.job.JobFactory}.
	 *
	 * @return  The current priority value.
	 */
	public double getPriority() { return this.p; }

	/**
	 * Returns the {@link CompletableFuture} associated with this server's lifetime,
	 * allowing callers to be notified when the server completes.
	 *
	 * @return  The server's {@link CompletableFuture}, or {@code null} if not set.
	 */
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
		log(message);
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
