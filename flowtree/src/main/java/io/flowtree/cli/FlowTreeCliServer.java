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

import io.flowtree.Server;
import io.flowtree.fs.OutputServer;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.Client;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import io.flowtree.python.JythonJob;
import io.flowtree.ui.NetworkDialog;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Date;
import java.util.LinkedHashMap;
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

	/** Default TCP port on which the terminal server listens. */
	private static final int defaultPort = 6767;

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
	private final LinkedHashMap commands;

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
		
		this.commands = new LinkedHashMap();
		
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
						c1.getServer();

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
						System.err.println("FlowTreeCliServer: Invocation error during status update: " + ite);
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
	public static String runCommand(String c, final PrintStream ps, LinkedHashMap commands) {
		String inc = c;
		int in = c.indexOf(" ");
		if (in > 0) inc = c.substring(0, in);

		try {
			if (c.startsWith("::help")) {
				return FlowTreeCliCommands.help(c);
			} else if (c.startsWith("::classhelp")) {
				return FlowTreeCliCommands.classHelp(c);
			} else if (c.startsWith("::confighelp")) {
				return FlowTreeCliCommands.configHelp(c);
			} else if (c.startsWith("::render")) {
				return FlowTreeCliCommands.render(c, current.httpwww, current.jobSize);
			} else if (c.startsWith("::plyrender")) {
				return FlowTreeCliCommands.plyRender(c, ps, current.jobSize, commands);
			} else if (c.startsWith("::gtsrender")) {
				return FlowTreeCliCommands.gtsRender(c, ps, current.jobSize, commands);
			} else if (c.equals("::suicide")) {
				System.out.println("Terminal: Received suicide");
				System.exit(9);
				return "suicide";
			} else if (c.startsWith("::sendtask")) {
				return FlowTreeCliCommands.sendTask(c, ps);
			} else if (c.startsWith("::threads")) {
				return FlowTreeCliCommands.threads(ps);
			} else if (c.startsWith("::kill")) {
				return FlowTreeCliCommands.kill(c);
			} else if (c.startsWith("::work")) {
				return FlowTreeCliCommands.work(ps);
			} else if (c.startsWith("::loadimage")) {
				return FlowTreeCliCommands.loadImage(c);
			} else if (c.startsWith("::ccache")) {
				return FlowTreeCliCommands.ccache(c);
			} else if (c.startsWith("::export")) {
				return FlowTreeCliCommands.export(c);
			} else if (c.startsWith("::status")) {
				return FlowTreeCliCommands.status(c, ps);
			} else if (c.startsWith("::jobtime")) {
				return String.valueOf(
						Client.getCurrentClient().getServer()
								.getNodeGroup().getAverageJobTime());
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
				return FlowTreeCliCommands.behave(c, ps);
			} else if (c.startsWith("::sbehave")) {
				return FlowTreeCliCommands.sBehave(c, ps);
			} else if (c.startsWith("::output")) {
				return FlowTreeCliCommands.output(c);
			} else if (c.startsWith("::print")) {
				return FlowTreeCliCommands.print(c, ps);
			} else if (c.startsWith("::ls")) {
				return FlowTreeCliCommands.ls(c, ps);
			} else if (c.startsWith("::mkdir")) {
				return FlowTreeCliCommands.mkdir(c);
			} else if (c.startsWith("::rmdir")) {
				return FlowTreeCliCommands.rmdir(c);
			} else if (c.startsWith("::rm ")) {
				return FlowTreeCliCommands.rm(c);
			} else if (c.startsWith("::store")) {
				return FlowTreeCliCommands.store(c);
			} else if (c.startsWith("::peers")) {
				return FlowTreeCliCommands.peers();
			} else if (c.startsWith("::pping")) {
				return FlowTreeCliCommands.pping(c, ps);
			} else if (c.startsWith("::open")) {
				return FlowTreeCliCommands.open(c);
			} else if (c.startsWith("::close")) {
				return FlowTreeCliCommands.close(c);
			} else if (c.startsWith("::uptime")) {
				double min = Client.getCurrentClient().getServer().getUptime() / 60000.0;
				return "Client up for " + min + " minutes.";
			} else if (c.startsWith("::date")) {
				return new Date().toString();
			} else if (c.startsWith("::set")) {
				return FlowTreeCliCommands.set(c);
			} else if (c.startsWith("::giterate")) {
				Node n = Client.getCurrentClient().getServer().getNodeGroup();
				n.iteration(n);
				return n + " iteration performed.";
			} else if (c.startsWith("::run")) {
				return FlowTreeCliCommands.run(c, ps);
			} else if (c.startsWith("::tasks")) {
				return FlowTreeCliCommands.tasks();
			} else if (c.startsWith("::dbs")) {
				return FlowTreeCliCommands.dbs(c);
			} else if (c.startsWith("::dbnotify")) {
				return FlowTreeCliCommands.dbNotify(c);
			} else if (c.startsWith("::dbupdate")) {
				return FlowTreeCliCommands.dbUpdate(c, ps);
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
	@Override
	public void connect(NodeProxy p) { this.button.setIcon(this.activeIcon); }

	/**
	 * Called when a peer connection is dropped. If no peer connections remain,
	 * updates the tray button icon to {@link #inactiveIcon}.
	 *
	 * @param p the disconnected {@link NodeProxy}
	 * @return {@code 0}
	 * @see NodeProxy.EventListener#disconnect(NodeProxy)
	 */
	@Override
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
	@Override
	public boolean recievedMessage(Message m, int reciever) { return false; }

	/**
	 * Called when a node starts processing a job. Updates the tray button icon
	 * to reflect whether any peer connections exist: {@link #activeIcon} if
	 * peers are connected, {@link #inactiveIcon} otherwise.
	 *
	 * @see Node.ActivityListener#startedWorking()
	 */
	@Override
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
	@Override
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
	@Override
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
	@Override
	public void iteration(Node n) { }
}
