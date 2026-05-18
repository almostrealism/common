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
import io.flowtree.python.JythonJob;
import org.almostrealism.color.RGB;
import org.almostrealism.io.OutputHandler;
import org.almostrealism.io.Storable;
import org.almostrealism.util.Help;
import org.almostrealism.util.KeyUtils;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Properties;

/**
 * Package-private collaborator of {@link FlowTreeCliServer} that contains the
 * individual command handler implementations extracted from the large
 * {@code runCommand} dispatch method.  Each handler method corresponds to one
 * or more related CLI commands and shares the same signature contract:
 * receives a full command string, a {@link PrintStream} for streaming output,
 * and returns the final result string to display to the connected client.
 *
 * <p>All methods are {@code static} because the underlying commands operate
 * against the singletons managed by {@link Client}, {@link OutputServer}, and
 * {@link FlowTreeCliServer#getCurrentInstance()} rather than against any
 * per-handler instance state.
 *
 * @author  Michael Murray
 */
class FlowTreeCliCommands {

	/** Prevent instantiation of this utility class. */
	private FlowTreeCliCommands() { }

	// -----------------------------------------------------------------------
	// Help commands
	// -----------------------------------------------------------------------

	/**
	 * Handles the {@code ::help} command family, returning usage text for a
	 * named command or a generic fallback message.
	 *
	 * @param c the full command string
	 * @return help text for the requested sub-topic
	 */
	static String help(String c) {
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
	}

	/**
	 * Handles the {@code ::classhelp} command, reflectively instantiating the
	 * named class and returning its {@link Help} text if available.
	 *
	 * @param c the full command string containing the class name as its first argument
	 * @return help text from the class or an explanatory message
	 * @throws Exception if the class cannot be loaded or instantiated
	 */
	static String classHelp(String c) throws Exception {
		String[] s = FlowTreeCliServer.parseCommand(c);
		Object o = Class.forName(s[0]).newInstance();

		if (o instanceof Help) {
			return ((Help) o).getHelpInfo();
		} else {
			return s[0] + " does not provide help.";
		}
	}

	/**
	 * Handles the {@code ::confighelp} command, returning the contents of the
	 * default {@code node.conf} resource or help for a specific configuration key.
	 *
	 * @param c the full command string
	 * @return the configuration file contents or per-key documentation
	 * @throws IOException if the internal configuration resource cannot be read
	 */
	static String configHelp(String c) throws IOException {
		if (c.endsWith("confighelp")) {
			BufferedReader bufIn = new BufferedReader(
					new InputStreamReader(
							FlowTreeCliServer.class.getClassLoader()
									.getResource("node.conf")
									.openStream()));
			StringBuilder buf = new StringBuilder();

			while (true) {
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
					"# Default: 6767\n" +
					"# See also:\n" +
					"# \t open command (help open)\n" +
					"# server.port = [on,off]";
		} else {
			return "Unknown parameter.";
		}
	}

	// -----------------------------------------------------------------------
	// Render commands
	// -----------------------------------------------------------------------

	/**
	 * Handles the {@code ::render} command, which submits a ray-tracing job
	 * using the XML scene at the given path.
	 *
	 * @param c       the full command string
	 * @param httpwww base HTTP URL of the www content root
	 * @param jobSize tile size used when submitting rendering jobs
	 * @return a message describing the started render thread
	 */
	static String render(String c, String httpwww, int jobSize) {
		long id = System.currentTimeMillis();

		// TODO  Load producer by reflection
		return "Started render thread: " + id;
	}

	/**
	 * Handles the {@code ::plyrender} command, submitting a PLY-geometry
	 * ray-tracing job.
	 *
	 * @param c       the full command string
	 * @param ps      print stream of the current terminal connection
	 * @param jobSize tile size used when submitting rendering jobs
	 * @param commands registered custom command map, forwarded to recursive calls
	 * @return a message describing the started render thread, or help text
	 */
	static String plyRender(String c, PrintStream ps, int jobSize, LinkedHashMap commands) {
		String[] s = FlowTreeCliServer.parseCommand(c);

		if (s.length <= 0)
			return FlowTreeCliServer.runCommand("help plyrender", ps, commands);

		long id = System.currentTimeMillis();

		// TODO  Load Job Producer using reflection
		return "Started render thread: " + id;
	}

	/**
	 * Handles the {@code ::gtsrender} command, submitting a GTS-geometry
	 * ray-tracing job.
	 *
	 * @param c       the full command string
	 * @param ps      print stream of the current terminal connection
	 * @param jobSize tile size used when submitting rendering jobs
	 * @param commands registered custom command map, forwarded to recursive calls
	 * @return a message describing the started render thread, or help text
	 */
	static String gtsRender(String c, PrintStream ps, int jobSize, LinkedHashMap commands) {
		String[] s = FlowTreeCliServer.parseCommand(c);

		if (s.length <= 0)
			return FlowTreeCliServer.runCommand("help gtsrender", ps, commands);

		long id = System.currentTimeMillis();

		// TODO  Load Job Producer using reflection
		return "Started render thread: " + id;
	}

	// -----------------------------------------------------------------------
	// Task / job commands
	// -----------------------------------------------------------------------

	/**
	 * Handles the {@code ::sendtask} command, instantiating a {@link JobFactory}
	 * by class name and dispatching it to the specified peer.
	 *
	 * @param c  the full command string
	 * @param ps print stream of the current terminal connection
	 * @return a status string describing the dispatch outcome
	 * @throws Exception if the class cannot be loaded, instantiated, or the peer
	 *                   index is invalid
	 */
	static String sendTask(String c, PrintStream ps) throws Exception {
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
	}

	/**
	 * Handles the {@code ::threads} command, listing all active threads on the
	 * server.
	 *
	 * @param ps print stream of the current terminal connection
	 * @return a summary string with the active thread count
	 */
	static String threads(PrintStream ps) {
		String[] s = Client.getCurrentClient().getServer().getThreadList();
		for (int i = 0; i < s.length; i++) ps.println(s[i]);
		return s.length + " active threads.";
	}

	/**
	 * Handles the {@code ::kill} command, sending a kill signal to a running task.
	 *
	 * @param c the full command string containing the task id and relay count
	 * @return a confirmation string
	 */
	static String kill(String c) {
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
	}

	/**
	 * Handles the {@code ::work} command, printing the current work status of
	 * each node.
	 *
	 * @param ps print stream of the current terminal connection
	 * @return a summary string with the job and node counts
	 */
	static String work(PrintStream ps) {
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
	}

	/**
	 * Handles the {@code ::tasks} command, listing all pending tasks in the node
	 * group.
	 *
	 * @return a newline-delimited string of task descriptions
	 */
	static String tasks() {
		String[] s = Client.getCurrentClient().getServer().getNodeGroup().taskList();
		StringBuilder r = new StringBuilder();
		for (int i = 0; i < s.length; i++) r.append(s[i] + "\n");
		return r.toString();
	}

	/**
	 * Handles the {@code ::run} command, which can execute a test job, an
	 * arbitrary {@link Runnable}, or a {@link FlowTreeCliServer.Command}.
	 *
	 * @param c  the full command string
	 * @param ps print stream of the current terminal connection
	 * @return a status string describing the execution outcome
	 * @throws Exception if the sub-command class cannot be loaded or instantiated
	 */
	static String run(String c, PrintStream ps) throws Exception {
		int index;
		String s = c;

		index = s.indexOf(" ");
		s = s.substring(index + 1);
		index = s.indexOf(" ");
		String prg = s.substring(0, index);

		if (prg.equals("test")) {
			// TODO
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
			FlowTreeCliServer.Command r =
					(FlowTreeCliServer.Command) Class.forName(s.substring(index + 1)).newInstance();

			long start = System.currentTimeMillis();
			r.run(c, ps);
			long end = System.currentTimeMillis();

			return "Executed command " + r.getClass().getName() +
					" in " + (end - start) + " msecs.";
		}

		return "Unknown task type \"" + prg + "\"";
	}

	// -----------------------------------------------------------------------
	// Resource / filesystem commands
	// -----------------------------------------------------------------------

	/**
	 * Handles the {@code ::ls} command, listing the children of a distributed
	 * resource directory with optional HTML colour markup.
	 *
	 * @param c  the full command string
	 * @param ps print stream of the current terminal connection
	 * @return a summary string with the file count and total size
	 */
	static String ls(String c, PrintStream ps) {
		String[] s = FlowTreeCliServer.parseCommand(c);
		String file = "/";
		boolean useColor = false;
		if (s.length > 0 && !s[0].equals("ls")) file = s[0];
		if (s.length > 1 && s[1].equals("color")) useColor = true;

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
						ps.print("<font color=\"red\">");
					else
						ps.print("<font color=\"blue\">");
				}

				ps.print(list[i]);

				if (useColor) ps.print("</font>");

				DistributedResource d = t.getResource(list[i]);

				if (d != null) {
					double l = d.getTotalBytes() / 1000.0;

					if (l < 0) {
						ps.print(" ?");
					} else {
						tot += l;
						ps.print(" ");
						ps.print(l);
						ps.print(" kb");
					}
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
	}

	/**
	 * Handles the {@code ::mkdir} command, creating a directory in the
	 * distributed resource tree.
	 *
	 * @param c the full command string containing the target URI
	 * @return a confirmation string or an error message
	 */
	static String mkdir(String c) {
		String[] s = FlowTreeCliServer.parseCommand(c);
		if (s.length <= 0) return "Specify a URI.";

		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		if (t == null) return "No running ResourceDistributionTask.";

		String l = t.createDirectory(s[0]);

		if (l == null)
			return "Could not create " + s[0];
		else
			return "Created " + l;
	}

	/**
	 * Handles the {@code ::rmdir} command, recursively deleting a directory from
	 * the distributed resource tree.
	 *
	 * @param c the full command string containing the target URI
	 * @return a confirmation string or a partial-failure message
	 */
	static String rmdir(String c) {
		String[] s = FlowTreeCliServer.parseCommand(c);
		if (s.length <= 0) return "Specify a URI.";

		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		if (t == null) return "No running ResourceDistributionTask.";

		boolean d = t.deleteDirectory(s[0]);

		if (d)
			return "Deleted " + s[0];
		else
			return "Some files could not be deleted.";
	}

	/**
	 * Handles the {@code ::rm} command, deleting a single resource from the
	 * distributed resource tree.
	 *
	 * @param c the full command string containing the target URI
	 * @return a confirmation string or an error message
	 */
	static String rm(String c) {
		String[] s = FlowTreeCliServer.parseCommand(c);
		if (s.length <= 0) return "Specify a URI.";

		ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();
		if (t == null) return "No running ResourceDistributionTask.";

		boolean d = t.deleteResource(s[0]);

		if (d)
			return "Deleted " + s[0];
		else
			return "Could not delete " + s[0];
	}

	/**
	 * Handles the {@code ::store} command, persisting a named server object to
	 * the distributed file system.
	 *
	 * @param c the full command string containing the object name and target URI
	 * @return a confirmation string or an error message
	 * @throws IOException if the output stream cannot be opened or written
	 */
	static String store(String c) throws IOException {
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
			((Storable) o).store(out);
			return o + " stored to " + s[1];
		}
	}

	/**
	 * Handles the {@code ::export} command, downloading a distributed resource
	 * to a local file prefixed with {@code ~}.
	 *
	 * @param c the full command string containing the resource URI
	 * @return a confirmation string with the local file path
	 * @throws IOException if the resource or local file cannot be accessed
	 */
	static String export(String c) throws IOException {
		String s = c;
		s = s.substring(s.indexOf(" ") + 1);

		Resource r = DistributedResource.createDistributedResource(s);
		try (InputStream ins =
					 Client.getCurrentClient().getServer().loadResource(r).getInputStream();
			 FileOutputStream fout = new FileOutputStream("~" + s)) {
			while (true) {
				int i = ins.read();
				if (i < 0) break;
				fout.write(i);
			}

			fout.flush();
		}

		return "Wrote ~" + s;
	}

	/**
	 * Handles the {@code ::loadimage} command, loading an image from the server
	 * by URI.
	 *
	 * @param c the full command string containing the image URI
	 * @return a description of the loaded image dimensions or a not-found message
	 */
	static String loadImage(String c) {
		String[] s = FlowTreeCliServer.parseCommand(c);
		RGB[][] rgb = Client.getCurrentClient().getServer().loadImage(s[0]);

		if (rgb == null)
			return "No image data loaded.";
		else
			return "Loaded " + rgb.length + "x" + rgb[0].length + " image.";
	}

	/**
	 * Handles the {@code ::ccache} command, clearing named server-side caches.
	 *
	 * @param c the full command string containing the cache name
	 * @return a status string or an error message
	 */
	static String ccache(String c) {
		int index;
		String s = c;

		index = s.indexOf(" ");
		s = s.substring(index + 1);
		index = s.indexOf(" ");
		String name = s.substring(0, index);

		if (name.equals("scene")) {
			// TODO  Move removeSceneCache method to flowtree
			return "not implemented";
		} else {
			return "Unknown cache: " + name;
		}
	}

	// -----------------------------------------------------------------------
	// Network / peer commands
	// -----------------------------------------------------------------------

	/**
	 * Handles the {@code ::peers} command, listing all currently connected peers.
	 *
	 * @return a newline-delimited list of peer addresses
	 */
	static String peers() {
		String[] s = Client.getCurrentClient().getServer().getPeers();
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length; i++) b.append(s[i] + "\n");
		return b.toString();
	}

	/**
	 * Handles the {@code ::pping} command, sending ping messages to a peer and
	 * reporting round-trip latency statistics.
	 *
	 * @param c  the full command string
	 * @param ps print stream of the current terminal connection
	 * @return a summary string with averaged latency
	 * @throws InterruptedException if the inter-ping sleep is interrupted
	 */
	static String pping(String c, PrintStream ps) throws InterruptedException, IOException {
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
			Thread.sleep(t * 1000L);

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
	}

	/**
	 * Handles the {@code ::open} command, establishing a connection to a remote
	 * peer by host and optional port.
	 *
	 * @param c the full command string containing the host and optional port
	 * @return a confirmation or failure message
	 */
	static String open(String c) throws IOException {
		String[] s = FlowTreeCliServer.parseCommand(c);
		String host = s[0];
		int port = Server.defaultPort;
		if (s.length > 1) port = Integer.parseInt(s[1]);

		if (Client.getCurrentClient().getServer().open(host, port)) {
			return "Opened host " + host;
		} else {
			return "Host was not opened.";
		}
	}

	/**
	 * Handles the {@code ::close} command, dropping all connections to the peer
	 * at the given index.
	 *
	 * @param c the full command string containing the peer index
	 * @return a confirmation string
	 */
	static String close(String c) {
		String[] s = FlowTreeCliServer.parseCommand(c);
		int h = Integer.parseInt(s[0]);
		String p = Client.getCurrentClient().getServer().getPeers()[h];
		int d = Client.getCurrentClient().getServer().close(h);
		return "Dropped " + d + " node connections to " + p;
	}

	/**
	 * Handles the {@code ::status} command, writing or printing the server status.
	 *
	 * @param c  the full command string, optionally including a file path
	 * @param ps print stream of the current terminal connection
	 * @return a confirmation string
	 */
	static String status(String c, PrintStream ps) throws IOException {
		int index = c.indexOf(" ");

		if (index > 0) {
			String file = c.substring(index + 1);
			Client.getCurrentClient().getServer().writeStatus(file);
			return "Wrote status file.";
		} else {
			Client.getCurrentClient().getServer().printStatus(ps);
			return "\n";
		}
	}

	// -----------------------------------------------------------------------
	// Server / behavior / misc commands
	// -----------------------------------------------------------------------

	/**
	 * Handles the {@code ::behave} command, synchronously running a
	 * {@link ServerBehavior} identified by class name.
	 *
	 * @param c  the full command string
	 * @param ps print stream of the current terminal connection
	 * @return a confirmation string or an error message
	 * @throws Exception if the behavior class cannot be loaded or instantiated
	 */
	static String behave(String c, PrintStream ps) throws Exception {
		String[] s = FlowTreeCliServer.parseCommand(c);
		if (s.length <= 0) return "Please specify a ServerBehavior.";

		Object o;
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
	}

	/**
	 * Handles the {@code ::sbehave} command, running a {@link ServerBehavior} in
	 * a background thread.
	 *
	 * @param c  the full command string
	 * @param ps print stream of the current terminal connection
	 * @return a confirmation string including the thread identity
	 * @throws Exception if the behavior class cannot be loaded or instantiated
	 */
	static String sBehave(String c, PrintStream ps) throws Exception {
		String[] s = FlowTreeCliServer.parseCommand(c);
		if (s.length <= 0) return "Please specify a ServerBehavior.";

		Object o;
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
			@Override
			public void run() {
				((ServerBehavior) oo).behave(Client.getCurrentClient().getServer(), ps);
			}
		};

		Message.verbose = v;

		t.start();

		return "Executed behavior in thread " + t + ".";
	}

	/**
	 * Handles the {@code ::print} command, printing the server object registered
	 * under the given name.
	 *
	 * @param c  the full command string containing the object name
	 * @param ps print stream of the current terminal connection
	 * @return a type description or a null message
	 */
	static String print(String c, PrintStream ps) {
		// TODO  Add print command to documentation.
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
			return col + " is a collection of " + col.size() + " elements.";
		} else if (o instanceof Object[]) {
			Object[] ob = (Object[]) o;
			for (int i = 0; i < ob.length; i++) ps.println(ob[i]);
			return Arrays.toString(ob) + " is an array of " + ob.length + " elements.";
		} else {
			ps.println(o);
			return "An instance of " + o.getClass().getName();
		}
	}

	/**
	 * Handles the {@code ::set} command, setting a named server parameter to a
	 * given value.
	 *
	 * @param c the full command string containing the parameter name and value
	 * @return a confirmation or unknown-parameter message
	 */
	static String set(String c) {
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
	}

	/**
	 * Handles the {@code ::output} command, writing output resources to files.
	 *
	 * @param c the full command string
	 * @return a result string or an error message
	 */
	static String output(String c) {
		int index = c.indexOf(" ");

		if (index > 0) {
			String f = c.substring(index + 1);

			if (f.endsWith("-image")) {
				// TODO  Image output should be general purpose
				return "not implemented";
			} else {
				return "Unknown resource type.";
			}
		} else {
			return "Must specify output resource.";
		}
	}

	// -----------------------------------------------------------------------
	// Database commands
	// -----------------------------------------------------------------------

	/**
	 * Handles the {@code ::dbs} command family ({@code start}, {@code create},
	 * {@code add}), managing the embedded {@link OutputServer} database.
	 *
	 * @param c the full command string
	 * @return a status string or an error message
	 * @throws Exception if a handler class cannot be loaded or instantiated
	 */
	static String dbs(String c) throws Exception {
		String[] s = FlowTreeCliServer.parseCommand(c);

		if (s.length <= 0) {
			return "Usage: dbs <command>";
		} else if (s[0].equals("start")) {
			Properties p = new Properties();
			p.setProperty("db.test", "true");
			new OutputServer(p);
			return "Started DBS.";
		} else if (s[0].equals("create")) {
			OutputServer server = OutputServer.getCurrentServer();
			if (server == null) return "No DBS running.";

			if (server.getDatabaseConnection().createOutputTable())
				return "Created DB tables.";
			else
				return "Could not create DB tables.";
		} else if (s[0].equals("add")) {
			OutputServer server = OutputServer.getCurrentServer();
			if (server == null) return "No DBS running.";

			Object o = Class.forName(s[1]).newInstance();

			boolean out = false;
			boolean que = false;

			if (o instanceof OutputHandler) {
				server.getDatabaseConnection().addOutputHandler((OutputHandler) o);
				out = true;
			}

			if (o instanceof QueryHandler) {
				server.getDatabaseConnection().addQueryHandler((QueryHandler) o);
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
	}

	/**
	 * Handles the {@code ::dbnotify} command, passing stored output to all
	 * registered output handlers.
	 *
	 * @param c the full command string containing the source name
	 * @return a confirmation string or an error message
	 */
	static String dbNotify(String c) {
		OutputServer server = OutputServer.getCurrentServer();
		if (server == null) return "No DBS running.";

		String[] s = FlowTreeCliServer.parseCommand(c);
		server.storeOutput();

		return "Output from " + s[0] + " passed to output handlers.";
	}

	/**
	 * Handles the {@code ::dbupdate} command, executing a network query and
	 * passing the results to the output handlers.
	 *
	 * @param c  the full command string
	 * @param ps print stream of the current terminal connection
	 * @return a confirmation string with the number of items processed
	 */
	static String dbUpdate(String c, PrintStream ps) throws IOException {
		OutputServer dbs = OutputServer.getCurrentServer();
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

		LinkedHashMap h = new LinkedHashMap();
		Query.fromString(m.getData(), h);

		ps.println("Network Query returned " + h.size() + " items.");
		dbs.storeOutput(h);
		return "Passed " + h.size() + " items to output handlers.";
	}
}
