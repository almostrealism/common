/*
 * Copyright 2021 Michael Murray
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

package io.flowtree.cli;

import io.almostrealism.db.Query;
import io.almostrealism.db.QueryHandler;
import io.almostrealism.resource.Resource;
import io.flowtree.Server;
import io.flowtree.behavior.ServerBehavior;
import io.flowtree.fs.DistributedResource;
import io.flowtree.fs.OutputServer;
import io.flowtree.fs.ResourceDistributionTask;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.Client;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import io.flowtree.python.JythonJob;
import io.flowtree.ui.NetworkDialog;
import org.almostrealism.color.RGB;
import org.almostrealism.io.OutputHandler;
import org.almostrealism.io.Storable;
import org.almostrealism.util.Help;
import org.almostrealism.util.KeyUtils;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

// TODO  Add cd and pwd commands.
// TODO  mkdir does not update nfs

/**
 * The primary server-side command interpreter for FlowTree. {@link FlowTreeCliServer}
 * accepts raw TCP connections (default port {@value #defaultPort}), presents a
 * text prompt, and dispatches each line to the static {@link #runCommand} method
 * which implements the full FlowTree command vocabulary.  It simultaneously acts
 * as an optional Swing tray-bar UI that visualises node activity.
 *
 * <p>Commands are prefixed with {@code ::} (e.g. {@code ::sendtask},
 * {@code ::ls}, {@code ::peers}).  Any unrecognised input is forwarded to the
 * Jython interpreter when {@link #enableJython} is {@code true}, allowing
 * ad-hoc Python scripting against the running server.
 *
 * <p>On startup, {@link #start(String[])} loads a {@code node.conf} properties
 * file (from a URL argument, classpath, or the current directory), initialises
 * the {@link io.flowtree.node.Client}, and optionally starts an
 * {@link HttpCommandServer} that exposes the same command set over HTTP.
 *
 * @author  Michael Murray
 */
public class FlowTreeCliServer implements Runnable, NodeProxy.EventListener, Node.ActivityListener {

	/**
	 * Extension point that allows external code to contribute custom commands
	 * to the CLI without modifying {@link FlowTreeCliServer}. Instances are
	 * registered via {@link FlowTreeCliServer#register(String, Command)}.
	 */
	public interface Command {
		/**
		 * Executes the command and returns a result string to be displayed to
		 * the connected client.
		 *
		 * @param command the full command string as typed by the user
		 * @param out     print stream of the current terminal connection
		 * @return result text to display; may be an empty string but not null
		 */
		String run(String command, PrintStream out);
	}

	/**
	 * When {@code true}, unrecognised commands are forwarded to the embedded
	 * Jython interpreter, enabling ad-hoc Python scripting.
	 */
	public static final boolean enableJython = true;

	/** Classpath resource name for the default server configuration file. */
	private static final String internalConfig = "node.conf";

	/**
	 * Fully-qualified class name of the PLY scene loader, used when rendering
	 * PLY geometry through the {@code ::plyrender} command.
	 */
	private static final String plySceneLoaderClass = "com.almostrealism.raytracer.loaders.PlySceneLoader";

	/**
	 * Fully-qualified class name of the GTS scene loader, used when rendering
	 * GTS geometry through the {@code ::gtsrender} command.
	 */
	private static final String gtsSceneLoaderClass = "com.almostrealism.raytracer.loaders.GtsSceneLoader";

	/** Default TCP port on which the terminal server listens. */
	private static final int defaultPort = 6767;

	/** HTML color tag applied to directory entries by the {@code ::ls color} command. */
	private static final String dirColor = "<font color=\"red\">";

	/** HTML color tag applied to resource entries by the {@code ::ls color} command. */
	private static final String resColor = "<font color=\"blue\">";

	/** HTML closing tag to terminate a colored entry in {@code ::ls} output. */
	private static final String endColor = "</font>";

	/** Base URL of the HTTP www root used by scene-rendering commands. */
	private String httpwww;

	/** Job tile size used when submitting rendering tasks. */
	private int jobSize;

	/** The singleton instance created and managed by {@link #start(String[])}. */
	private static FlowTreeCliServer current;

	/** Server socket accepting raw TCP terminal connections. */
	private final ServerSocket socket;

	/** Input stream of the currently active terminal connection. */
	private InputStream in;

	/** Output stream of the currently active terminal connection. */
	private OutputStream out;

	/** US-ASCII print stream wrapping the current terminal connection's output. */
	private PrintStream ps;

	/** Registry of custom {@link Command} implementations keyed by command name. */
	private final Hashtable commands;

	/** Swing dialog for configuring and monitoring network topology, shown in GUI mode. */
	private NetworkDialog dialog;

	// private GraphDisplay activityGraph, sleepGraph;

	/** Tray button whose icon reflects current node activity (active/inactive/sleeping). */
	private JButton button;

	// private ScrollingTextDisplay display;

	/** Icon displayed on the tray button when at least one node is working. */
	private ImageIcon activeIcon;

	/** Icon displayed on the tray button when nodes are idle and connected. */
	private ImageIcon inactiveIcon;

	/** Icon displayed on the tray button when all nodes are sleeping. */
	private ImageIcon sleepIcon;

	/** Icon for the tray close button. */
	private ImageIcon closeIcon;

	/**
	 * Application entry point. Delegates immediately to {@link #start(String[])}.
	 *
	 * @param args command-line arguments forwarded to {@link #start(String[])}
	 */
	public static void main(String[] args) { FlowTreeCliServer.start(args); }

	/** Parses command-line arguments, loads configuration, and starts the FlowTree server. */
	public static void start(String[] args) {
		Properties p = new Properties();
		
		String configFile = null;
		
		t: try {
			InputStream in = null;
			
			if (args.length >= 1) {
				try {
					in = new URL(args[0]).openStream();
					p.load(in);
					System.out.println("FlowTreeCliServer: Loaded config from " + args[0]);
					break t;
				} catch (FileNotFoundException fnfe) {
					System.out.println("Config file not found: " + args[0]);
				} catch (IOException ioe) {
					System.out.println("IO error loading config file: " + args[0]);
				}
			}
			
			URL r = FlowTreeCliServer.class.getClassLoader().getResource(internalConfig);
			configFile = "node.conf (internal)";
			if (r != null) {
				in = r.openStream();
				System.out.println("FlowTreeCliServer: Loaded config from internal resource.");
			} else {
				configFile = "node.conf";
				in = new FileInputStream("node.conf");
				System.out.println("FlowTreeCliServer: Loaded config from local node.conf");
			}
			
			p.load(in);
		} catch (MalformedURLException e) {
			System.out.println("Client: Malformed properties URL");
			System.exit(1);
		} catch (FileNotFoundException fnf) {
			System.out.println("Config file not found: " + configFile);
		} catch (IOException ioe) {
			System.out.println("IO error loading config file: " + configFile);
			System.exit(3);
		}
		
		String httpwww = p.getProperty("http.www", "http://localhost/");
		
		int jobSize = Integer.parseInt(p.getProperty("render.jobsize", "4"));
		
		int port = Integer.parseInt(p.getProperty("server.terminal.port",
					String.valueOf(FlowTreeCliServer.defaultPort)));

		if (OutputServer.getCurrentServer() == null) {
			System.out.println("Starting Server...");
			
			String user = p.getProperty("client.user", "public");
			String passwd = p.getProperty("client.passwd", "public");
			
			try {
				Client.setCurrentClient(new Client(p, user, passwd, null));
			} catch (IOException ioe) {
				System.out.println("IO error starting network client: " + ioe.getMessage());
			}
		}
		
		if ("on".equals(p.getProperty("server.terminal", "on"))) {
			try {
				System.out.print("Terminal: ");
				
				String cgui = p.getProperty("client.gui", "false");
				boolean gui = cgui.equals("on") || cgui.equals("true");
				
				OutputServer os = OutputServer.getCurrentServer();
				ThreadGroup g = null;
				if (os != null) g = os.getNodeServer().getThreadGroup();
				new FlowTreeCliServer(httpwww, jobSize, port, gui);
				Thread t = new Thread(g, FlowTreeCliServer.current);
				t.setName("Server Terminal");
				t.start();
				
				System.out.println("Started");
			} catch (IOException ioe) {
				System.out.println("RingsClient: IO error starting client (" +
									ioe.getMessage() + ")");
			}
		}
		
		if ("on".equals(p.getProperty("server.http", "off"))) {
			String httpport = p.getProperty("server.http.port", "6780");
			try {
				HttpCommandServer.main(new String[] {httpport});
			} catch (IOException ioe) {
				System.out.println("RingsClient: Unable to start HTTP server (" +
									ioe.getMessage() + ")");
			}
		}
		
		OutputServer.getCurrentServer().getNodeServer().setParam(p);
		
		if (FlowTreeCliServer.current == null) return;
		
		int i = 0;
		String prop = "server.terminal.script." + i;
		String script;
		
		while ((script = p.getProperty(prop)) != null) {
			try {
				FlowTreeCliServer.current.execute(new BufferedReader(new FileReader(script)));
			} catch (FileNotFoundException e) {
				System.out.println("Terminal: " + script + " not found.");
			} catch (IOException e) {
				System.out.println("Terminal: IO error parsing " + script +
									" (" + e.getMessage() + ")");
			}
			
			i++;
			prop = "server.terminal.script." + i;
		}
	}

	/**
	 * Constructs a new {@link FlowTreeCliServer} on the default port
	 * {@value #defaultPort} without a GUI tray.
	 *
	 * @param httpwww base URL of the HTTP content root used by render commands
	 * @param jobSize tile size used when submitting rendering jobs
	 * @throws IOException if the server socket cannot be bound
	 */
	public FlowTreeCliServer(String httpwww, int jobSize) throws IOException {
		this(httpwww, jobSize, defaultPort);
	}

	/**
	 * Constructs a new {@link FlowTreeCliServer} on the specified port without
	 * a GUI tray.
	 *
	 * @param httpwww base URL of the HTTP content root used by render commands
	 * @param jobSize tile size used when submitting rendering jobs
	 * @param port    TCP port to listen on for terminal connections
	 * @throws IOException if the server socket cannot be bound
	 */
	public FlowTreeCliServer(String httpwww, int jobSize, int port) throws IOException {
		this(httpwww, jobSize, port, false);
	}

	/**
	 * Constructs a new {@link FlowTreeCliServer}, binds a server socket on the
	 * given port, and optionally creates and displays a Swing tray window with
	 * a network-configuration dialog and activity icon.
	 *
	 * <p>When {@code gui} is {@code true} the constructor also registers itself
	 * as an {@link io.flowtree.node.Node.ActivityListener} to keep the tray icon
	 * current, and starts a daemon thread that periodically refreshes the
	 * {@link NetworkDialog} status panel.
	 *
	 * @param httpwww base URL of the HTTP content root used by render commands
	 * @param jobSize tile size used when submitting rendering jobs
	 * @param port    TCP port to listen on for terminal connections
	 * @param gui     {@code true} to create and show the Swing tray UI
	 * @throws IOException if the server socket cannot be bound
	 */
	public FlowTreeCliServer(String httpwww, int jobSize, int port, boolean gui) throws IOException {
		FlowTreeCliServer.current = this;
		this.httpwww = httpwww;
		this.jobSize = jobSize;
		
		this.commands = new Hashtable();
		
		this.socket = new ServerSocket(port);
		
		if (gui) {
			JFrame graphFrame = new JFrame("Graphs");
			graphFrame.setSize(100, 200);
			
			Container c = graphFrame.getContentPane();
			c.setLayout(new GridLayout(0, 1));
			
//			this.activityGraph = new GraphDisplay();
//			this.sleepGraph = new GraphDisplay();
//
//			c.add(this.activityGraph);
//			c.add(this.sleepGraph);
			// c.add(this.relayPGraph);
			// c.add(this.connectPGraph);
			
			// graphFrame.setVisible(true);

			OutputServer.getCurrentServer().getNodeServer().getNodeGroup().addActivityListener(this);
			
			this.loadIcons();
			this.dialog = new NetworkDialog();
			
			Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
			
			JWindow f = new JWindow();
			f.setSize(300, 40);
			f.setLocation(d.width - 300, d.height - 60);
			
			this.button = new JButton(this.activeIcon);
			this.button.addActionListener(e -> FlowTreeCliServer.this.dialog.showDialog());
			
			JButton closeButton = new JButton(closeIcon);
			closeButton.addActionListener(e -> {
					try {
						Server s = OutputServer.getCurrentServer().getNodeServer();
						
						if (s.getNodeGroup().getServers().length > 0 &&
							JOptionPane.showConfirmDialog(null, "Are you sure?", "Quit",
										JOptionPane.YES_NO_OPTION)
										!= JOptionPane.YES_OPTION) {
							return;
						}
					} catch (Exception ignored) { }
					
					System.exit(0);
				});

//			TODO  Separate UI elsewhere
//			ScrollingTextDisplay.TextProducer producer = new ScrollingTextDisplay.TextProducer() {
//				private int last = 0;
//
//				@Override
//				public String nextPhrase() {
//					if (OutputServer.getCurrentServer() == null) return "";
//
//					Server s = Client.getCurrentClient().getServer();
//					if (s == null) return "";
//
//					double g = s.getNodeGroup().getActivityRating() / 2;
//					double r = 0.5 + s.getNodeGroup().getAverageConnectivityRating();
//					double b = s.getNodeGroup().getAverageConnectivityRating() / 2.0;
//
//					final RGB color = new RGB(r, g, b);
//
//					try {
//						SwingUtilities.invokeAndWait(() -> {
//							if (FlowTreeCliServer.this.display == null) return;
//
//							FlowTreeCliServer.this.display.setBackground(
//									GraphicsConverter.convertToAWTColor(color));
//							FlowTreeCliServer.this.display.repaint();
//						});
//					} catch (InterruptedException ignored) {
//					} catch (InvocationTargetException e) {
//						e.printStackTrace();
//					}
//
//					if (last == 0) {
//						last = 1;
//						return "Running " + s.getNodeGroup().getNodes().length + " nodes at " +
//								s.getLocalSocketAddress() + ".";
//					} else if (last == 1) {
//						last = 2;
//						return "Connected to " + s.getNodeGroup().getServers().length + " servers.";
//					} else if (last == 2) {
//						last = 3;
//						return "Worked for " + Node.formatTime(s.getNodeGroup().getTimeWorked()) +
//								" and completed " + s.getNodeGroup().getCompletedJobCount() + " jobs.";
//					} else {
//						last = 0;
//						return "Communicated for " +
//								Node.formatTime(s.getNodeGroup().getTimeCommunicated()) + ".";
//					}
//				}
//			};
//
//			this.display = new ScrollingTextDisplay(producer, 30);
//			this.display.setOpaque(true);
//
//			f.getContentPane().setLayout(new BorderLayout());
//			f.getContentPane().add(this.button, BorderLayout.WEST);
//			f.getContentPane().add(this.display, BorderLayout.CENTER);
//			f.getContentPane().add(closeButton, BorderLayout.EAST);
//
//			f.setVisible(true);
			
			Thread t = new Thread(() -> {
				while (true) {
					try {
						Thread.sleep(60000);

						Client c1 = Client.getCurrentClient();
						Server s = c1.getServer();
						NodeGroup g = s.getNodeGroup();

//						TODO  Separate UI elsewhere
//						if (FlowTreeCliServer.this.activityGraph != null) {
//							FlowTreeCliServer.this.activityGraph.addEntry(
//									(int)(g.getAverageActivityRating() * 10));
//						}
//
//						if (FlowTreeCliServer.this.sleepGraph != null) {
//							FlowTreeCliServer.this.sleepGraph.addEntry(
//									g.getSleep() / 10000);
//						}

						if (FlowTreeCliServer.this.dialog.isVisible() && c1 != null) {
							SwingUtilities.invokeAndWait(() -> FlowTreeCliServer.this.dialog.updateStatus());
						}
					} catch (InterruptedException ignored) {
					} catch (InvocationTargetException ite) {
						ite.printStackTrace();
					}
				}
			});
			
			t.setName("Rendering Client Status Update Thread");
			t.setDaemon(true);
			t.start();
		}
	}
	
	/**
	 * Loads the four tray icon images ({@code active.gif}, {@code inactive.gif},
	 * {@code sleep.gif}, {@code close.gif}) from the classpath. If a resource
	 * cannot be located, an empty {@link ImageIcon} is used as a fallback so
	 * that GUI operation continues without errors.
	 */
	protected void loadIcons() {
		URL activeIconUrl = FlowTreeCliServer.class.getClassLoader().getResource("active.gif");
		if (activeIconUrl != null)
			this.activeIcon = new ImageIcon(activeIconUrl);
		else
			this.activeIcon = new ImageIcon();
		
		URL inactiveIconUrl = FlowTreeCliServer.class.getClassLoader().getResource("inactive.gif");
		if (inactiveIconUrl != null)
			this.inactiveIcon = new ImageIcon(inactiveIconUrl);
		else
			this.inactiveIcon = new ImageIcon();
		
		URL sleepIconUrl = FlowTreeCliServer.class.getClassLoader().getResource("sleep.gif");
		if (sleepIconUrl != null)
			this.sleepIcon = new ImageIcon(sleepIconUrl);
		else
			this.sleepIcon = new ImageIcon();
		
		URL closeIconUrl = FlowTreeCliServer.class.getClassLoader().getResource("close.gif");
		if (closeIconUrl != null)
			this.closeIcon = new ImageIcon(closeIconUrl);
		else
			this.closeIcon = new ImageIcon();
	}
	
	/**
	 * Runs the terminal accept loop. For each accepted TCP connection the server
	 * presents a welcome banner and a {@code [----]>} prompt, reads commands
	 * one at a time, dispatches them via {@link #runCommand(String)}, and writes
	 * the output back to the client. The loop exits when the client sends
	 * {@code exit}, the connection goes idle for more than three minutes, or an
	 * I/O error occurs. The Jython interpreter is always closed after each
	 * connection via {@link io.flowtree.python.JythonJob#closeInterpreter()}.
	 */
	@Override
	public void run() {
		while (true) {
			try (Socket connection = this.socket.accept()) {
				System.out.println("FlowTreeCliServer: Accepted connection...");
				
				this.in = connection.getInputStream();
				this.out = connection.getOutputStream();
				System.out.println("FlowTreeCliServer: Got IO streams...");
				
				this.ps = new PrintStream(this.out, false, StandardCharsets.US_ASCII);
				System.out.println("FlowTreeCliServer: Constructed print stream...");
				
				this.write("Welcome to FlowTree.io\n");
				System.out.println("FlowTreeCliServer: Wrote welcome message...");
				
				w: while(true) {
					this.write("[----]> ");
					
					String s = this.read();
					
					if (s == null)
						break;
					else
						s = s.substring(0, s.length() - 2);
					
					if (s.equals("exit")) break;
					
					this.write(this.runCommand(s.trim()));
					this.write("\n");
				}
				
				this.in.close();
				this.out.close();
				connection.close();
			} catch (IOException ioe) {
				System.out.println("FlowTreeCliServer: IO error accepting connection (" +
									ioe.getMessage() + ")");
			} catch (Exception e) {
				System.out.println("FlowTreeCliServer: " + e);
			} finally {
				JythonJob.closeInterpreter();
			}
		}
	}
	
	/**
	 * Writes a US-ASCII string to the current terminal connection's output
	 * stream. IO errors are logged to standard output and swallowed so that the
	 * accept loop can continue.
	 *
	 * @param s the string to write
	 */
	public void write(String s) {
		try {
			this.out.write(s.getBytes(StandardCharsets.US_ASCII));
		} catch (IOException ioe) {
			System.out.println("FlowTreeCliServer: IO error writing message (" + ioe.getMessage() + ")");
		}
	}
	
	/**
	 * Reads available bytes from the current terminal connection's input stream,
	 * blocking in 500 ms intervals until at least one byte arrives. Returns
	 * {@code null} if no data has arrived within three minutes (180 000 ms) or
	 * if an I/O error occurs.
	 *
	 * @return the bytes decoded as a US-ASCII string, or {@code null} on timeout
	 *         or error
	 */
	public String read() {
		long s = System.currentTimeMillis();
		
		try {
			while (true) {
				if (this.in.available() >= 1) {
					byte[] b = new byte[in.available()];
					this.in.read(b);

					return new String(b, StandardCharsets.US_ASCII);
				}

				try {Thread.sleep(500);} catch (InterruptedException ignored) {}

				if (System.currentTimeMillis() - s > 180000) return null;
			}
		} catch (IOException ioe) {
			System.out.println("FlowTreeCliServer: IO error reading message (" + ioe.getMessage() + ")");
		}
		
		return null;
	}
	
	/**
	 * Returns the {@link PrintStream} wrapping the current connection's output.
	 * If the stream has not yet been created it is constructed lazily from
	 * {@link #out}.
	 *
	 * @return the current US-ASCII print stream
	 */
	public PrintStream getPrintStream() {
		if (this.ps == null) {
			this.ps = new PrintStream(this.out, false, StandardCharsets.US_ASCII);
		}

		return this.ps;
	}

	/**
	 * Registers a custom {@link Command} implementation under the given name so
	 * that it is recognised by {@link #runCommand(String, PrintStream, java.util.Hashtable)}.
	 *
	 * @param name the command name token (without the {@code ::} prefix)
	 * @param c    the {@link Command} implementation to associate with the name
	 */
	public void register(String name, Command c) { this.commands.put(name, c); }

	/**
	 * Dispatches a command string using the registered custom commands and the
	 * built-in command vocabulary, writing output to the current connection's
	 * print stream.
	 *
	 * @param c the command string
	 * @return the command result text
	 */
	public String runCommand(String c) { return FlowTreeCliServer.runCommand(c, this.ps); }

	/**
	 * Reads and executes every line from the supplied {@link BufferedReader},
	 * printing each result to standard output. Used to run startup scripts
	 * specified in the {@code server.terminal.script.*} configuration properties.
	 *
	 * @param in the script reader
	 * @throws IOException if a line cannot be read
	 */
	public void execute(BufferedReader in) throws IOException {
		String line = null;

		while ((line = in.readLine()) != null)
			System.out.println("Terminal: " + this.runCommand(line));
	}

	/**
	 * Returns the singleton {@link FlowTreeCliServer} created by
	 * {@link #start(String[])}, or {@code null} if none has been started.
	 *
	 * @return the current instance, or {@code null}
	 */
	public static FlowTreeCliServer getCurrentInstance() { return FlowTreeCliServer.current; }

	/**
	 * Parses and dispatches a CLI command using the built-in command vocabulary
	 * and writing output to the given print stream. This is a convenience
	 * overload that passes a {@code null} custom command map.
	 *
	 * @param c  the command string
	 * @param ps print stream for command output
	 * @return the command result text
	 */
	public static String runCommand(String c, PrintStream ps) {
		return FlowTreeCliServer.runCommand(c, ps, null);
	}
	
	/** Parses and dispatches a CLI command, writing output to the given print stream. */
	// TODO  Add more help for commands and config file parameters.
	public static String runCommand(String c, final PrintStream ps, Hashtable commands) {
		String inc = c;
		int in = c.indexOf(" ");
		if (in > 0) inc = c.substring(0, in);
		
		try {
			if (c.startsWith("::help")) {
				if (c.endsWith(" sendtask")) {
					return "sendtask <host index> <JobFactory class name> [[key]=[value]]...\n" +
							"Using -1 for host index sends the task to localhost.\n" +
							"As many key=value pairs may be included as needed.";
				} else if (c.endsWith(" plyrender")) {
					return "plyrender <uri> [dimensions] [sDimensions] " +
					  		"[pDimensions] [focalLength] [cameraLocation] [cameraDirection]\n" +
					  		"dimensions, sDimensions, and pDimensions take the form wxh IE 1.0x1.0" +
					  		"dimensions is in pixels (integer values only)" +
					  		"cameraLocation and cameraDirection are vector quantites\n" +
					  		"which must take the form x,y,z IE 0.0,0.0,0.0\n" +
					  		"plyrender uses the PlySceneLoader (raytracer.loaders).";
				} else if (c.endsWith(" ls")) {
					return "ls <uri> [color]\n" +
							"Lists the contents of the directory at <uri>.\n" +
							"If the word color is append to the command, ls will\n" +
							"produce rich HTML output with colors.";
				} else if (c.endsWith(" dbupdate")) {
					return "dbupdate <relay> <table> <task>";
				} else if (c.endsWith(" dbs")) {
					return "dbs {start, add}";
				} else if (c.endsWith(" dbs start")) {
					return "dbs start";
				} else if (c.endsWith(" dbs add")) {
					return "dbs add <classname>";
				} else if (c.endsWith(" set")) {
					return "The set command is used to set runtime variables.\n" +
							"Usage: set <varName> <value>\n" +
							"Variables:\n" +
							"\tserver.hostname\n" +
							"\tserver.resource.verbose\n" +
							"\tdb.verbose\n" +
							"\tserver.resource.io.verbose\n" +
							"\tservers.output.host\n" +
							"\tservers.output.port\n";
				} else {
					return "No help info.";
				}
			} else if (c.startsWith("::classhelp")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				Object o = Class.forName(s[0]).newInstance();
				
				if (o instanceof Help) {
					return ((Help)o).getHelpInfo();
				} else {
					return s[0] + " does not provide help.";
				}
			} else if (c.startsWith("::confighelp")) {
				if (c.endsWith("confighelp")) {
					BufferedReader bufIn = new BufferedReader(
								new InputStreamReader(
								(FlowTreeCliServer.class).
								getClassLoader().
								getResource(FlowTreeCliServer.internalConfig).
								openStream()));
					StringBuffer buf = new StringBuffer();
					
					w: while (true) {
						String st = bufIn.readLine();
						if (st == null) break;
						buf.append(st + "\n");
					}
					
					return "# Usage: confighelp <entry>\n" +
							"# Below is a sample config file representing the default\n" +
							"# configuration (a different config file may have\n" +
							"# been specified or values may have been changed since\n" +
							"# the time that rings started, meaning this is not\n" +
							"# necessarily the configuration that is loaded).\n" +
							"# Use the confighelp command to learn more about each value.\n\n" +
							buf;
				} else if (c.endsWith("server.port")) {
					return "# The port that the NodeGroup server accepts connections on.\n" +
							"# This port is the port that should be opened by other Rings\n" +
							"# servers to establish a connection with this server.\n" +
							"# Default: " + FlowTreeCliServer.defaultPort + "\n" +
							"# See also:\n" +
							"# \t open command (help open)\n" +
							"# server.port = [on,off]";
				} else {
					return "Unknown parameter.";
				}
			} else if (c.startsWith("::render")) {
				String sceneName = "scene.xml";
				String dim = "100x100";
				String sdim = "1x1";
				String pdim = "-1.0x-1.0";
				String fl = "-1.0";
				String cloc = "0.0,0.0,0.0";
				String cdir = "0.0,0.0,0.0";
				String pri = "1.0";
				
				String[] s = FlowTreeCliServer.parseCommand(c);
				
				if (s.length > 0) sceneName = s[0];
				if (s.length > 1) dim = s[1];
				if (s.length > 2) sdim = s[2];
				if (s.length > 3) pdim = s[3];
				if (s.length > 4) fl = s[4];
				if (s.length > 5) cloc = s[5];
				if (s.length > 6) cdir = s[6];
				if (s.length > 7) pri = s[7];
				
				long id = System.currentTimeMillis();
				
				String[] args = {
						current.httpwww + sceneName,
						dim, sdim, pdim, fl, cloc, cdir,
						String.valueOf(current.jobSize),
						String.valueOf(id), pri};

				// TODO  Load producer by reflection
				/*
				final JobProducer p = new JobProducer(args);
				String host = p.getHost();
				
				Client cl = Client.getCurrentClient();
				ThreadGroup g = null;
				if (cl != null) g = cl.getServer().getThreadGroup();
				Thread t = new Thread(g, new Runnable() {
					public void run() {
						p.sendTask();
					}
				});
				
				t.setName("Network Client Job Producer Thread");
				
				t.start();
				*/
				
				return "Started render thread: " + id; // + "@" + host;
			} else if (c.startsWith("::plyrender")) {
				String sceneName = "scene.xml";
				String dim = "100x100";
				String sdim = "1x1";
				String pdim = "-1.0x-1.0";
				String fl = "-1.0";
				String cloc = "0.0,0.0,0.0";
				String cdir = "0.0,0.0,0.0";
				String pri = "1.0";
				
				String[] s = FlowTreeCliServer.parseCommand(c);
				
				if (s.length <= 0)
					return FlowTreeCliServer.runCommand("help plyrender", ps, commands);
				else
					sceneName = s[0];
				
				if (s.length > 1) dim = s[1];
				if (s.length > 2) sdim = s[2];
				if (s.length > 3) pdim = s[3];
				if (s.length > 4) fl = s[4];
				if (s.length > 5) cloc = s[5];
				if (s.length > 6) cdir = s[6];
				if (s.length > 7) pri = s[7];
				
				long id = System.currentTimeMillis();
				
				String[] args = {sceneName,
						dim, sdim, pdim, fl, cloc, cdir,
						String.valueOf(current.jobSize),
						String.valueOf(id), pri};

				// TODO  Load Job Producer using reflection
				/*
				final JobProducer p = new JobProducer(args);
//				p.setSceneLoader(FlowTreeCliServer.plySceneLoaderClass);
				String host = p.getHost();
				
				Client cl = Client.getCurrentClient();
				ThreadGroup g = null;
				if (cl != null) g = cl.getServer().getThreadGroup();
				Thread t = new Thread(g, new Runnable() {
					public void run() {
						p.sendTask();
					}
				});
				
				t.setName("Network Client Job Producer Thread");
				
				t.start();
				*/
				
				return "Started render thread: " + id; // + "@" + host;
			} else if (c.startsWith("::gtsrender")) {
				String sceneName = "scene.xml";
				String dim = "100x100";
				String sdim = "1x1";
				String pdim = "-1.0x-1.0";
				String fl = "-1.0";
				String cloc = "0.0,0.0,0.0";
				String cdir = "0.0,0.0,0.0";
				String pri = "1.0";
				
				String[] s = FlowTreeCliServer.parseCommand(c);
				
				if (s.length <= 0)
					return FlowTreeCliServer.runCommand("help gtsrender", ps, commands);
				else
					sceneName = s[0];
				
				if (s.length > 1) dim = s[1];
				if (s.length > 2) sdim = s[2];
				if (s.length > 3) pdim = s[3];
				if (s.length > 4) fl = s[4];
				if (s.length > 5) cloc = s[5];
				if (s.length > 6) cdir = s[6];
				if (s.length > 7) pri = s[7];
				
				long id = System.currentTimeMillis();
				
				String[] args = {sceneName,
						dim, sdim, pdim, fl, cloc, cdir,
						String.valueOf(current.jobSize),
						String.valueOf(id), pri};

				// TODO  Load Job Producer using reflection
				/*
				final JobProducer p = new JobProducer(args);
//				p.setSceneLoader(FlowTreeCliServer.gtsSceneLoaderClass);
				String host = p.getHost();
				
				Client cl = Client.getCurrentClient();
				ThreadGroup g = null;
				if (cl != null) g = cl.getServer().getThreadGroup();
				Thread t = new Thread(g, new Runnable() {
					public void run() {
						p.sendTask();
					}
				});
				
				t.setName("Network Client Job Producer Thread");
				
				t.start();
				 */
				
				return "Started render thread: " + id; // + "@" + host;
			} else if (c.equals("::suicide")) {
				System.out.println("Terminal: Received suicide");
				System.exit(9);
				return "suicide";
			} else if (c.startsWith("::sendtask")) {
				final String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Please specify peer to send to.";
				if (s.length <= 1) return "Please specify class name.";
				
				Object o = Class.forName(s[1]).newInstance();
				
				if (!(o instanceof JobFactory))
					return s[1] + " is not a JobFactory.";
				
				final JobFactory f = (JobFactory) o;
				
				final int h = Integer.parseInt(s[0]);
				String id = KeyUtils.generateKey();
				String[] peers = Client.getCurrentClient().getServer().getPeers();
				String host;
				
				if (h == -1)
					host = "localhost";
				else if (h < peers.length)
					host = peers[h];
				else
					throw new IndexOutOfBoundsException("No peer with index " + h);
				
				f.set("id", id);
				
				for (int i = 2; i < s.length; i++) {
					String key = s[i].substring(0, s[i].indexOf(":="));
					String value = s[i].substring(s[i].indexOf(":=") + 2);
					
					f.set(key, value);
				}
				
				Client cl = Client.getCurrentClient();
				ThreadGroup g = null;
				if (cl != null) g = cl.getServer().getThreadGroup();
				Thread t = new Thread(g, () -> Client.getCurrentClient().getServer().sendTask(f, h));
				
				t.setName("Send Task Thread");
				t.start();
				
				return "Started sendtask thread: " + id + "@" + host;
			} else if (c.startsWith("::threads")) {
				String[] s = Client.getCurrentClient().getServer().getThreadList();
				for (int i = 0; i < s.length; i++) ps.println(s[i]);
				return s.length + " active threads.";
			} else if (c.startsWith("::kill")) {
				int index;
				String s = c;
				
				index = s.indexOf(" ");
				s = s.substring(index + 1);
				index = s.indexOf(" ");
				String task = s.substring(0, index);
				
				index = s.indexOf(" ");
				int relay = Integer.parseInt(s.substring(index + 1));
				
				Client.getCurrentClient().getServer().sendKill(task, relay);
				
				return "Send kill signal for task " + task +
						" with relay count of " + relay + ".";
			} else if (c.startsWith("::work")) {
				// TODO Add work command to documentation.
				
				String[] w = Client.getCurrentClient().getServer().getCurrentWork();
				int tot = 0;
				
				for (int i = 0; i < w.length; i++) {
					if (w[i] == null) {
						ps.println("Node " + i + ": Not working.");
					} else {
						ps.println("Node " + i + ": " + w[i]);
						tot++;
					}
				}
				
				String jobs = " running job";
				if (tot == 1)
					jobs = jobs + " on ";
				else
					jobs = jobs + "s on ";
				
				String nodes = " node";
				if (w.length == 1)
					nodes = nodes + ".";
				else
					nodes = nodes + "s.";
				
				return tot + jobs + w.length + nodes;
			} else if (c.startsWith("::loadimage")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				RGB[][] rgb = Client.getCurrentClient().getServer().loadImage(s[0]);
				
				if (rgb == null)
					return "No image data loaded.";
				else
					return "Loaded " + rgb.length + "x" + rgb[0].length + " image.";
			} else if (c.startsWith("::ccache")) {
				int index;
				String s = c;
				
				index = s.indexOf(" ");
				s = s.substring(index + 1);
				index = s.indexOf(" ");
				String name = s.substring(0, index);
				
				if (name.equals("scene")) {
					// TODO  Move removeSceneCache method to flowtree

					/*
					index = s.indexOf(" ");
					s = s.substring(index + 1);
					String scene = s;
					
					if (RayTracingJob.removeSceneCache(scene))
						return "Removed " + scene + " from RayTracingJob cache.";
					else
						return "Not in RayTracingJob cache: " + scene;
					*/

					return "not implemented";
				} else {
					return "Unknown cache: " + name;
				}
			} else if (c.startsWith("::export")) {
				String s = c;
				s = s.substring(s.indexOf(" ") + 1);
				
				Resource r = DistributedResource.createDistributedResource(s);
				try (InputStream ins =
						Client.getCurrentClient().getServer().loadResource(r).getInputStream();
						FileOutputStream fout = new FileOutputStream("~" + s)) {
					w: while (true) {
						int i = ins.read();
						if (i < 0) break;
						fout.write(i);
					}
					
					fout.flush();
				}
				
				return "Wrote ~" + s;
			} else if (c.startsWith("::status")) {
				int index = c.indexOf(" ");
				
				if (index > 0) {
					String file = c.substring(index + 1);
					Client.getCurrentClient().getServer().writeStatus(file);
					return "Wrote status file.";
				} else {
					Client.getCurrentClient().getServer().printStatus(ps);
					return "\n";
				}
			} else if (c.startsWith("::jobtime")) {
				return String.valueOf(
						Client.getCurrentClient().getServer().
						getNodeGroup().getAverageJobTime());
			} else if (c.startsWith("::inputrate")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				int peer = Integer.parseInt(s[0]);
				return String.valueOf(Client.getCurrentClient().getServer().getInputRate(peer));
			} else if (c.startsWith("::paratio")) {
				return String.valueOf(Client.getCurrentClient().getServer().getPeerActivityRatio());
			} else if (c.startsWith("::parating")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				int peer = Integer.parseInt(s[0]);
				return String.valueOf(Client.getCurrentClient().getServer().getActivityRating(peer));
			} else if (c.startsWith("::behave")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Please specify a ServerBehavior.";
				
				Object o = null;
				boolean v = Message.verbose;
				
				if (s[0].equals("-v")) {
					Message.verbose = true;
					o = Class.forName(s[1]).newInstance();
				} else {
					o = Class.forName(s[0]).newInstance();
				}
				
				if (!(o instanceof ServerBehavior))
					return s[0] + " is not a ServerBehavior.";
				
				((ServerBehavior) o).behave(Client.getCurrentClient().getServer(), ps);
				
				Message.verbose = v;
				
				return "Executed behavior.";
			} else if (c.startsWith("::sbehave")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Please specify a ServerBehavior.";
				
				Object o = null;
				boolean v = Message.verbose;
				
				if (s[0].equals("-v")) {
					Message.verbose = true;
					o = Class.forName(s[1]).newInstance();
				} else {
					o = Class.forName(s[0]).newInstance();
				}
				
				if (!(o instanceof ServerBehavior))
					return s[0] + " is not a ServerBehavior.";
				
				final Object oo = o;
				
				Thread t = new Thread() {
					public void run() {
						((ServerBehavior) oo).behave(Client.getCurrentClient().getServer(), ps);
					}
				};
				
				Message.verbose = v;
				
				t.start();
				
				return "Executed behavior in thread " + t + ".";
			} else if (c.startsWith("::output")) {
				int index = c.indexOf(" ");
				
				if (index > 0) {
					String f = c.substring(index + 1);
					
					if (f.endsWith("-image")) {
						// TODO  Image output should be general purpose
						/*
						long task = Long.parseLong(f.substring(0, f.indexOf("-image")));
						RayTracingJob.getDefaultOutputHandler().getHandler(task).writeImage();
						return "Wrote image file for task " + task;
						*/
						return "not implemented";
					} else {
						return "Unknown resource type.";
					}
				} else {
					return "Must specify output resource.";
				}
			} else if (c.startsWith("::print")) {
				// TODO  Add print command to doumentation.
				
				String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Specify an object.";
				
				Server server = Client.getCurrentClient().getServer();
				Object o = server.getObject(s[0]);
				
				if (o == null) {
					return "null";
				} else if (o instanceof Collection) {
					Collection col = (Collection) o;
					Iterator itr = col.iterator();
					while (itr.hasNext()) ps.println(itr.next());
					return col + " is a collection of " +
							col.size() + " elements.";
				} else if (o instanceof Object[]) {
					Object[] ob = (Object[]) o;
					for (int i = 0; i < ob.length; i++) ps.println(ob[i]);
					return ob + " is an array of " +
							ob.length + " elements.";
				} else {
					ps.println(o);
					return "An instance of " + o.getClass().getName();
				}
			} else if (c.startsWith("::ls")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				String file = "/";
				boolean useColor = false;
				if (s.length > 0 && !s[0].equals("ls")) file = s[0];
				if (s.length > 1 && s[1].equals("color"))
					useColor = true;
				
				ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
				if (t == null) return "No running ResourceDistributionTask.";
				
				DistributedResource res = t.getResource(file);
				
				if (t.isDirectory(file)) {
					String[] list = t.getChildren(file);
					if (list == null) return "Null";
					
					double tot = 0.0;
					
					for (int i = 0; i < list.length; i++) {
						if (useColor) {
							if (t.isDirectory(list[i]))
								ps.print(FlowTreeCliServer.dirColor);
							else
								ps.print(FlowTreeCliServer.resColor);
						}
						
						ps.print(list[i]);
						
						if (useColor)
							ps.print(FlowTreeCliServer.endColor);
						
						DistributedResource d = t.getResource(list[i]);
						
						d: if (d != null) {
							double l = d.getTotalBytes() / 1000.0;
							
							if (l < 0) {
								ps.print(" ?");
								break d;
							}
							
							tot += l;
							
							ps.print(" ");
							ps.print(l);
							ps.print(" kb");
						}
						
						ps.println();
					}
					
					return list.length + " files.\n" +
							"Resources total " + tot + " kilobytes.";
				} else if (res != null) {
					double kilo = res.getTotalBytes() / 1000.0;
					return file + " is " + kilo + " kilobytes.";
				} else {
					return file + " not found.";
				}
			} else if (c.startsWith("::mkdir")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Specify a URI.";
				
				ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
				if (t == null) return "No running ResourceDistributionTask.";
				
				String l = t.createDirectory(s[0]);
				
				if (l == null)
					return "Could not create " + s[0];
				else
					return "Created " + l;
			} else if (c.startsWith("::rmdir")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Specify a URI.";
				
				ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
				if (t == null) return "No running ResourceDistributionTask.";
				
				boolean d = t.deleteDirectory(s[0]);
				
				if (d)
					return "Deleted " + s[0];
				else
					return "Some files could not be deleted.";
			} else if (c.startsWith("::rm ")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Specify a URI.";
				
				ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
				if (t == null) return "No running ResourceDistributionTask.";
				
				boolean d = t.deleteResource(s[0]);
				
				if (d)
					return "Deleted " + s[0];
				else
					return "Could not delete " + s[0];
			} else if (c.startsWith("::store")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				if (s.length <= 0) return "Specify an object.";
				if (s.length <= 1) return "Specify a URI.";
				
				Server server = Client.getCurrentClient().getServer();
				Object o = server.getObject(s[0]);
				
				if (o == null)
					return s[0] + " is null.";
				else if (!(o instanceof Storable))
					return s[0] + " is not storable.";
				
				
				try (OutputStream out = server.getOutputStream(s[1])) {
					if (out == null) return "Could not get out stream to " + s[1];
					((Storable)o).store(out);
					return o + " stored to " + s[1];
				}
			} else if (c.startsWith("::peers")) {
				String[] s = Client.getCurrentClient().getServer().getPeers();
				StringBuffer b = new StringBuffer();
				for (int i = 0; i < s.length; i++) b.append(s[i] + "\n");
				return b.toString();
			} else if (c.startsWith("::pping")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				
				int peer = 0;
				int size = 1;
				int n = 1;
				int t = 5;
				
				if (s.length > 0) peer = Integer.parseInt(s[0]);
				if (s.length > 1) size = Integer.parseInt(s[1]);
				if (s.length > 2) n = Integer.parseInt(s[2]);
				if (s.length > 3) t = Integer.parseInt(s[3]);
				
				int opened = 0;
				long time = 0;
				
				for (int i = 0; i < n; i++) {
					ps.print("Sending peer " + peer +
								" a message containing " + size +
								" chars of data + header in " + t +
								"s: ");
					try {
						Thread.sleep(t * 1000L);
					} catch (InterruptedException ie) {}
					
					long l = Client.getCurrentClient().getServer().ping(peer, size, t * 1000);
					
					if (l < 0) {
						ps.println("Timeout.");
					} else {
						ps.println(l + " msecs.");
						opened++;
						time += l;
					}
				}
				
				if (opened > 0) {
					return "Recieved " + opened + " messages averaging " + time / opened + " msecs each.";
				} else {
					return "No messages recieved.";
				}
			} else if (c.startsWith("::open")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				String host = s[0];
				int port = Server.defaultPort;
				if (s.length > 1) port = Integer.parseInt(s[1]);
				
				if (Client.getCurrentClient().getServer().open(host, port)) {
					return "Opened host " + host;
				} else {
					return "Host was not opened.";
				}
			} else if (c.startsWith("::close")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				int h = Integer.parseInt(s[0]);
				String p = Client.getCurrentClient().getServer().getPeers()[h];
				int d = Client.getCurrentClient().getServer().close(h);
				return "Dropped " + d + " node connections to " + p;
			} else if (c.startsWith("::uptime")) {
				double min = Client.getCurrentClient().getServer().getUptime() / 60000.0;
				return "Client up for " + min + " minutes.";
			} else if (c.startsWith("::date")) {
				return new Date().toString();
			} else if (c.startsWith("::set")) {
				int index;
				String s = c;
				
				index = s.indexOf(" ");
				s = s.substring(index + 1);
				index = s.indexOf(" ");
				String param = s.substring(0, index);
				
				index = s.indexOf(" ");
				s = s.substring(index + 1);
				String value = s;
				
				boolean b = Client.getCurrentClient().getServer().setParam(param, value);
				
				if (b)
					return "Set " + param + " to " + value;
				else
					return "Unknown paramater: " + param;
			} else if (c.startsWith("::giterate")) {
				Node n = Client.getCurrentClient().getServer().getNodeGroup();
				n.iteration(n);
				return n + " iteration performed.";
			} else if (c.startsWith("::run")) {
				int index;
				String s = c;
				
				index = s.indexOf(" ");
				s = s.substring(index + 1);
				index = s.indexOf(" ");
				String prg = s.substring(0, index);
				
				if (prg.equals("test")) {
					index = s.indexOf(" ");
					int sleep = Integer.parseInt(s.substring(index + 1));

					// TODO
//					Client.getCurrentClient().getServer().sendTask(new TestJobFactory(sleep).encode(), 0);
//					return "Sent test job factory.";
					return "not implemented";
				} else if (prg.equals("runnable")) {
					index = s.indexOf(" ");
					Runnable r = (Runnable) Class.forName(s.substring(index + 1)).newInstance();
					
					long start = System.currentTimeMillis();
					r.run();
					long end = System.currentTimeMillis();
					
					return r.getClass().getName() + ".run() completed in " +
							(end - start) + " msecs.";
				} else if (prg.equals("command")) {
					index = s.indexOf(" ");
					Command r = (Command) Class.forName(s.substring(index + 1)).newInstance();
					
					long start = System.currentTimeMillis();
					r.run(c, ps);
					long end = System.currentTimeMillis();
					
					return "Executed command " + r.getClass().getName() +
							" in " + (end - start) + " msecs.";
				}
				
				return "Unknown task type \"" + prg + "\"";
			} else if (c.startsWith("::tasks")) {
				String[] s = Client.getCurrentClient().getServer().getNodeGroup().taskList();
				StringBuffer r = new StringBuffer();
				
				for(int i = 0; i < s.length; i++) r.append(s[i] + "\n");
				
				return r.toString();
			} else if (c.startsWith("::dbs")) {
				String[] s = FlowTreeCliServer.parseCommand(c);
				
				if (s.length <= 0) {
					return "Usage: dbs <command>";
				} else if (s[0].equals("start")) {
					Properties p = new Properties();
					p.setProperty("db.test", "true");
					
					OutputServer server =
						new OutputServer(p);
					
					return "Started DBS.";
				} else if (s[0].equals("create")) {
					OutputServer server =
						OutputServer.getCurrentServer();
					if (server == null) return "No DBS running.";

					if (server.getDatabaseConnection().createOutputTable())
						return "Created DB tables.";
					else
						return "Could not create DB tables.";
				} else if (s[0].equals("add")) {
					OutputServer server =
						OutputServer.getCurrentServer();
					if (server == null) return "No DBS running.";
					
					Object o = Class.forName(s[1]).newInstance();
					
					boolean out = false, que = false;
					
					if (o instanceof OutputHandler) {
						server.getDatabaseConnection().addOutputHandler(
								(OutputHandler)o);
						out = true;
					}
					
					if (o instanceof QueryHandler) {
						server.getDatabaseConnection().addQueryHandler(
								(QueryHandler)o);
						que = true;
					}
					
					if (out && que) {
						return "Added " + o + " as a handler for output and queries.";
					} else if (out) {
						return "Added " + o + " as a handler for output.";
					} else if (que) {
						return "Added " + o + " as a handler for queries.";
					} else {
						return "Could not add " + o + " as a DB handler.";
					}
				} else {
					return "Unknown DBS command: " + s[0] + "\nTry start, create, or add.";
				}
			} else if (c.startsWith("::dbnotify")) {
				OutputServer server =
					OutputServer.getCurrentServer();
				if (server == null) return "No DBS running.";
				
				String[] s = FlowTreeCliServer.parseCommand(c);
				server.storeOutput();
				
				return "Output from " + s[0] + " passed to output handlers.";
			} else if (c.startsWith("::dbupdate")) {
				OutputServer dbs =
					OutputServer.getCurrentServer();
				if (dbs == null) return "No DBS running.";
				
				String[] s = FlowTreeCliServer.parseCommand(c);
				int depth = 1;
				String table = "output";
				String con = "true";
				
				if (s.length > 0 && !s[0].equals("dbupdate"))
					depth = Integer.parseInt(s[0]);
				
				if (s.length > 1) table = s[1];
				if (s.length > 2) con = s[2];
				
				if (!con.equals("true")) con = "data like '%:" + con + ":%'";
				
				Query q = new Query(table, con);
				q.setRelay(depth);
				
				Server server = Client.getCurrentClient().getServer();
				Message m = server.executeQuery(q, null, NodeProxy.queryTimeout);
				
				Hashtable h = new Hashtable();
				Query.fromString(m.getData(), h);
				
				ps.println("Network Query returned " + h.size() + " items.");
				dbs.storeOutput(h);
				return "Passed " + h.size() + " items to output handlers.";
			} else if (commands != null && commands.containsKey(inc)) {
				Command r = (Command) commands.get(inc);
				return r.run(c, ps);
			} else if (enableJython) {
				return JythonJob.execute(c);
			} else {
				return "Command not found.";
			}
		} catch (NumberFormatException n) {
			return "An error occurred while parsing a number.";
		} catch (IndexOutOfBoundsException ob) {
			return ob.getMessage();
		} catch (UnknownHostException h) {
			return "Host unknown (" + h.getMessage() + ").";
		} catch (ConnectException ce) {
			return "Could not connect to host: " + ce.getMessage();
		} catch (Exception e) {
			e.printStackTrace();
			return "Exception while running command: " + e;
		}
	}
	
	/**
	 * Splits a CLI command string into its space-delimited argument tokens,
	 * discarding the leading command verb. For example,
	 * {@code "::sendtask 0 com.example.MyFactory"} returns
	 * {@code ["0", "com.example.MyFactory"]}.
	 *
	 * @param c the full command string including the leading verb
	 * @return an array of argument strings following the first token
	 */
	public static String[] parseCommand(String c) {
		int index = c.indexOf(" ");
		String s = c;
		
		List l = new ArrayList();
		
		w: while (true) {
			s = s.substring(index + 1);
			index = s.indexOf(" ");
			
			if (index < 0) {
				l.add(s);
				break;
			} else {
				l.add(s.substring(0, index));
			}
		}
		
		return (String[]) l.toArray(new String[0]);
	}

	/**
	 * Called when a new peer connection is established. Updates the tray button
	 * icon to {@link #activeIcon} to indicate connectivity.
	 *
	 * @param p the newly connected {@link NodeProxy}
	 * @see NodeProxy.EventListener#connect(NodeProxy)
	 */
	public void connect(NodeProxy p) { this.button.setIcon(this.activeIcon); }

	/**
	 * Called when a peer connection is dropped. If no peer connections remain,
	 * updates the tray button icon to {@link #inactiveIcon}.
	 *
	 * @param p the disconnected {@link NodeProxy}
	 * @return {@code 0}
	 * @see NodeProxy.EventListener#disconnect(NodeProxy)
	 */
	public int disconnect(NodeProxy p) {
		if (Client.getCurrentClient().getServer().getNodeGroup().getServers().length == 0)
			this.button.setIcon(this.inactiveIcon);

		return 0;
	}

	/**
	 * Called when a message is received from a peer. This implementation always
	 * returns {@code false} to indicate the message was not handled and should
	 * be processed by other listeners.
	 *
	 * @param m        the received message
	 * @param reciever the index of the receiving node
	 * @return {@code false}
	 * @see NodeProxy.EventListener#recievedMessage(Message, int)
	 */
	public boolean recievedMessage(Message m, int reciever) { return false; }

	/**
	 * Called when a node starts processing a job. Updates the tray button icon
	 * to reflect whether any peer connections exist: {@link #activeIcon} if
	 * peers are connected, {@link #inactiveIcon} otherwise.
	 *
	 * @see Node.ActivityListener#startedWorking()
	 */
	public void startedWorking() {
		if (this.button == null) return;
		if (Client.getCurrentClient() == null) return;
		if (Client.getCurrentClient().getServer() == null) return;
		if (Client.getCurrentClient().getServer().getNodeGroup() == null) return;
		
		if (Client.getCurrentClient().getServer().getNodeGroup().getServers().length > 0)
			this.button.setIcon(this.activeIcon);
		else
			this.button.setIcon(this.inactiveIcon);
	}

	/**
	 * Called when all nodes have stopped working. If the node group is no
	 * longer working, updates the tray button icon to {@link #sleepIcon}.
	 *
	 * @see Node.ActivityListener#stoppedWorking()
	 */
	public void stoppedWorking() {
		if (Client.getCurrentClient().getServer().getNodeGroup().isWorking()) return;
		this.button.setIcon(this.sleepIcon);
	}
	
	/**
	 * Called when the local node group becomes isolated (loses all peer
	 * connections). This implementation is a no-op; a dialog prompt has been
	 * left commented out pending a proper UI separation.
	 *
	 * @see Node.ActivityListener#becameIsolated()
	 */
	public void becameIsolated() {
//			TODO  Separate UI elsewhere
//		if (this.display != null) {
//			JOptionPane.showMessageDialog(null, "Please restart the client.",
//									"Restart", JOptionPane.INFORMATION_MESSAGE);
//			System.exit(0);
//		}
	}

	/**
	 * Called at each iteration of the node activity loop. This implementation
	 * takes no action.
	 *
	 * @param n the node whose iteration just completed
	 */
	public void iteration(Node n) { }
}
