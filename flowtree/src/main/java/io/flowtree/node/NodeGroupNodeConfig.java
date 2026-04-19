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
import io.flowtree.msg.Message;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.Properties;

/**
 * Propagates configuration parameters to all child {@link Node}s of a
 * {@link NodeGroup} and handles the {@link NodeGroup#setParam(String, String)}
 * dispatch table.
 *
 * <p>This class is a package-private collaborator extracted from
 * {@link NodeGroup} to keep that class within the 1 500-line limit imposed
 * by the project's Checkstyle configuration.
 *
 * <p>Configuration keys recognised by {@link #setParam(String, String)}:
 * <ul>
 *   <li>{@code nodes.acs} — activity-sleep coefficient</li>
 *   <li>{@code nodes.pasc} — peer-activity-sleep coefficient</li>
 *   <li>{@code nodes.parp} — parental-relay probability</li>
 *   <li>{@code nodes.prc} — peer-relay coefficient</li>
 *   <li>{@code nodes.mjp} — minimum-job probability</li>
 *   <li>{@code nodes.mfj} — max failed jobs per node</li>
 *   <li>{@code group.aco} — activity offset for the group average</li>
 *   <li>{@code group.msc} — max-sleep coefficient</li>
 *   <li>{@code network.msg.verbose} — {@link Message#verbose}</li>
 *   <li>{@code network.msg.dverbose} — {@link Message#dverbose}</li>
 *   <li>{@code network.msg.sverbose} — {@link Message#sverbose}</li>
 *   <li>{@code group.nverbose} — node-group verbose flag</li>
 *   <li>{@code group.taskjobs} — jobs requested per task per iteration</li>
 *   <li>{@code group.taskmax} — max active task factories per iteration</li>
 *   <li>{@code nodes.workingDirectory} — flowtree working directory property</li>
 *   <li>{@code nodes.relay} — relay probability for child nodes</li>
 *   <li>{@code nodes.wp} — whether to weight peers by activity</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see NodeGroup
 */
public class NodeGroupNodeConfig implements ConsoleFeatures {

	/**
	 * Result returned by {@link #setParam} when the key is not recognised.
	 * Callers use this to distinguish "unknown key" from "key applied".
	 */
	static final boolean PARAM_UNKNOWN = false;

	/**
	 * The {@link NodeGroup} whose parameters and child nodes are configured.
	 */
	private final NodeGroup group;

	/**
	 * The live collection of child {@link Node}s to which parameters are
	 * propagated. This is the same list instance held by the group.
	 */
	private final Collection<Node> nodes;

	/**
	 * Constructs a configurator bound to the given group and its child-node list.
	 *
	 * @param group  The owning group; must not be {@code null}.
	 * @param nodes  The group's live child-node collection; must not be {@code null}.
	 */
	NodeGroupNodeConfig(NodeGroup group, Collection<Node> nodes) {
		this.group = group;
		this.nodes = nodes;
	}

	/**
	 * Applies a single named configuration parameter to the owning group and
	 * propagates relevant parameters to all child nodes.
	 *
	 * @param name   The property key.
	 * @param value  The string value to apply.
	 * @return {@code true} if the key was recognised and applied;
	 *         {@code false} if the key is unknown to this configurator.
	 * @throws NumberFormatException  If {@code value} cannot be parsed as the
	 *         numeric type required by the named property.
	 */
	boolean setParam(String name, String value) {
		String msg = null;

		if (name.equals("nodes.acs")) {
			msg = "ActivitySleepC = " + value;
			setActivitySleepC(Double.parseDouble(value));
		} else if (name.equals("nodes.pasc")) {
			msg = "PeerActivitySleepC = " + value;
			setPeerActivitySleepC(Double.parseDouble(value));
		} else if (name.equals("nodes.parp")) {
			msg = "ParentalRelayP = " + value;
			setParentalRelayP(Double.parseDouble(value));
		} else if (name.equals("nodes.prc")) {
			msg = "PeerRelayC = " + value;
			setPeerRelayC(Double.parseDouble(value));
		} else if (name.equals("nodes.mjp")) {
			msg = "MinimumJobP = " + value;
			setMinimumJobP(Double.parseDouble(value));
		} else if (name.equals("nodes.mfj")) {
			msg = "MaxFailedJobs = " + value;
			setMaxFailedJobs(Integer.parseInt(value));
		} else if (name.equals("group.aco")) {
			msg = "ActivityOffset = " + value;
			group.setActivityOffset(Double.parseDouble(value));
		} else if (name.equals("group.msc")) {
			msg = "MaxSleepC = " + value;
			group.setMaxSleepC(Double.parseDouble(value));
		} else if (name.equals("network.msg.verbose")) {
			Message.verbose = Boolean.parseBoolean(value);
		} else if (name.equals("network.msg.dverbose")) {
			Message.dverbose = Boolean.parseBoolean(value);
		} else if (name.equals("network.msg.sverbose")) {
			Message.sverbose = Boolean.parseBoolean(value);
		} else if (name.equals("group.nverbose")) {
			group.verbose = Boolean.parseBoolean(value);
		} else if (name.equals("group.taskjobs")) {
			msg = "JobsPerTask = " + value;
			group.setJobsPerTask(Integer.parseInt(value));
		} else if (name.equals("group.taskmax")) {
			msg = "MaxTasks = " + value;
			group.setMaxTasks(Integer.parseInt(value));
		} else if (name.equals("nodes.workingDirectory")) {
			msg = "WorkingDirectory = " + value;
			System.setProperty("flowtree.workingDirectory", value);
		} else if (name.equals("nodes.relay")) {
			msg = "RelayP = " + value;
			setRelayProbability(Double.parseDouble(value));
		} else if (name.equals("nodes.wp")) {
			msg = "WeightPeers = " + value;
			setWeightPeers(Boolean.parseBoolean(value));
		} else {
			return PARAM_UNKNOWN;
		}

		if (msg != null) {
			log("NodeGroup: " + msg);
			group.getStatusRenderer().addActivityMessage(msg);
		}

		return true;
	}

	/**
	 * Propagates the activity-sleep coefficient to every child node.
	 *
	 * @param acs  Activity-sleep coefficient to apply to all child nodes.
	 */
	void setActivitySleepC(double acs) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setActivitySleepC(acs);
	}

	/**
	 * Propagates the peer-activity-sleep coefficient to every child node.
	 *
	 * @param pacs  Peer-activity-sleep coefficient to apply to all child nodes.
	 */
	void setPeerActivitySleepC(double pacs) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setPeerActivitySleepC(pacs);
	}

	/**
	 * Propagates the parental-relay probability to every child node.
	 *
	 * @param parp  Parental relay probability in the range [0.0, 1.0].
	 */
	void setParentalRelayP(double parp) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setParentalRelayP(parp);
	}

	/**
	 * Propagates the peer-relay coefficient to every child node.
	 *
	 * @param prc  Peer relay coefficient to apply to all child nodes.
	 */
	void setPeerRelayC(double prc) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setPeerRelayC(prc);
	}

	/**
	 * Propagates the minimum-job probability to every child node.
	 *
	 * @param mjp  Minimum job-execution probability in the range [0.0, 1.0].
	 */
	void setMinimumJobP(double mjp) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setMinimumJobP(mjp);
	}

	/**
	 * Propagates the maximum failed-job count to every child node.
	 *
	 * @param mfj  Maximum number of failed jobs to retain per node.
	 */
	void setMaxFailedJobs(int mfj) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setMaxFailedJobs(mfj);
	}

	/**
	 * Propagates the relay probability to every child node.
	 *
	 * @param r  Relay probability in the range [0.0, 1.0].
	 */
	void setRelayProbability(double r) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setRelayProbability(r);
	}

	/**
	 * Propagates the peer-weighting flag to every child node.
	 *
	 * @param w  {@code true} if peers should be chosen with weighted probability.
	 */
	void setWeightPeers(boolean w) {
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) itr.next().setWeightPeers(w);
	}

	/**
	 * Applies node labels to the given group and all its child nodes from the
	 * supplied {@link Properties} (keys with prefix {@code nodes.labels.}) and
	 * from the {@code FLOWTREE_NODE_LABELS} environment variable
	 * ({@code key:value} pairs separated by commas).  Auto-detects the
	 * {@code platform} label when it is not explicitly configured.
	 *
	 * @param group  The owning {@link NodeGroup} to label.
	 * @param nodes  The child nodes that should receive the same labels.
	 * @param p      The properties containing optional label entries.
	 */
	static void applyNodeLabels(NodeGroup group, Collection<Node> nodes, Properties p) {
		String labelPrefix = "nodes.labels.";
		for (Object keyObj : p.keySet()) {
			String key = (String) keyObj;
			if (key.startsWith(labelPrefix)) {
				String labelKey = key.substring(labelPrefix.length());
				String labelValue = p.getProperty(key);
				group.setLabel(labelKey, labelValue);
				for (Node n : nodes) {
					n.setLabel(labelKey, labelValue);
				}
			}
		}

		String envLabels = System.getenv("FLOWTREE_NODE_LABELS");
		if (envLabels != null && !envLabels.isEmpty()) {
			for (String pair : envLabels.split(",")) {
				String[] parts = pair.split(":", 2);
				if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
					group.setLabel(parts[0].trim(), parts[1].trim());
					for (Node n : nodes) {
						n.setLabel(parts[0].trim(), parts[1].trim());
					}
				}
			}
		}

		if (group.getLabels().get("platform") == null) {
			String os = System.getProperty("os.name", "").toLowerCase();
			String platform = os.contains("mac") ? "macos" : "linux";
			group.setLabel("platform", platform);
			for (Node n : nodes) {
				n.setLabel("platform", platform);
			}
			Console.root().println("NodeGroup: Auto-detected platform label: platform=" + platform);
		}
	}

	/**
	 * System property that disables all outbound connections to external servers.
	 *
	 * <p>Set {@code -Dflowtree.offline=true} (or call
	 * {@code System.setProperty("flowtree.offline", "true")}) to prevent any
	 * {@link NodeGroup} from connecting to {@code FLOWTREE_ROOT_HOST} or to
	 * enumerated {@code servers.N.host} entries.  This is the required guard
	 * for tests that construct real {@link Server} instances — without it, a
	 * test running in a production environment (where {@code FLOWTREE_ROOT_HOST}
	 * is set) would connect to the live controller and receive real jobs.</p>
	 *
	 * <p>Tests must activate offline mode before any {@link Server} is constructed:</p>
	 * <pre>{@code
	 * @BeforeClass
	 * public static void enforceOfflineMode() {
	 *     System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, "true");
	 * }
	 * }</pre>
	 */
	public static final String OFFLINE_MODE_PROPERTY = "flowtree.offline";

	/**
	 * Returns {@code true} when offline mode is active, meaning all outbound
	 * server connections should be suppressed.
	 *
	 * @return {@code true} if {@code flowtree.offline} system property is set to {@code "true"}
	 */
	public static boolean isOfflineMode() {
		return Boolean.getBoolean(OFFLINE_MODE_PROPERTY);
	}

	/**
	 * Opens the initial server connections specified in {@code p} and wires up
	 * the persistent-host reconnect thread when the {@code FLOWTREE_ROOT_HOST}
	 * environment variable is set.
	 *
	 * <p>All outbound connections are suppressed when
	 * {@link #OFFLINE_MODE_PROPERTY} ({@code flowtree.offline}) is set to
	 * {@code true}.  Tests that construct real {@link Server} instances must
	 * activate offline mode via {@code System.setProperty} before the
	 * constructor runs.</p>
	 *
	 * @param group        The {@link NodeGroup} to register new server connections on.
	 * @param p            Properties to read server host/port entries from.
	 * @param serverCount  Number of server entries to open.
	 */
	static void initServerConnections(NodeGroup group, Properties p, int serverCount) {
		if (isOfflineMode()) {
			Console.root().println("NodeGroup: Offline mode active — skipping all external server connections.");
			return;
		}

		String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
		String rootPort = System.getenv("FLOWTREE_ROOT_PORT");

		if (rootHost != null) {
			if (rootPort == null) rootPort = String.valueOf(Server.defaultPort);
			group.startPersistentHost(rootHost, Integer.parseInt(rootPort));
		}

		if (serverCount > 0) Console.root().println("NodeGroup: Opening server connections...");

		for (int i = 0; i < serverCount; i++) {
			String host = p.getProperty("servers." + i + ".host", "localhost");
			int port = Integer.parseInt(p.getProperty("servers." + i + ".port", "7777"));

			try {
				Console.root().println("NodeGroup: Connecting to server " + i + " (" + host + ":" + port + ")...");
				group.addServer(new Socket(host, port));
			} catch (UnknownHostException uh) {
				Console.root().warn("NodeGroup: Server " + i + " is unknown host", null);
			} catch (IOException ioe) {
				Console.root().warn("NodeGroup: IO error while connecting to server " +
						i + " -- " + ioe.getMessage(), ioe);
			} catch (SecurityException se) {
				Console.root().warn("NodeGroup: Security exception while connecting to server " + i +
						" (" + se.getMessage() + ")", se);
			}
		}
	}

	/**
	 * Decodes an encoded task string into a fully initialised {@link JobFactory}.
	 * The string format is:
	 * <pre>
	 *   &lt;ClassName&gt;&lt;ENTRY_SEP&gt;&lt;key1&gt;:=&lt;value1&gt;&lt;ENTRY_SEP&gt;...
	 * </pre>
	 * The first token is the fully-qualified class name; subsequent tokens are
	 * key-value pairs applied via {@link JobFactory#set(String, String)}.
	 * Returns {@code null} if the class cannot be found or instantiated.
	 *
	 * @param data              Encoded representation of the {@link JobFactory}.
	 * @param keyValueSeparator The separator token used between key and value fields
	 *                          (typically {@link NodeGroup#KEY_VALUE_SEPARATOR}).
	 * @return  The constructed and configured factory, or {@code null} on failure.
	 */
	static JobFactory createTask(String data, String keyValueSeparator) {
		int index = data.indexOf(JobFactory.ENTRY_SEPARATOR);
		String className = data.substring(0, index);

		Class c = null;
		JobFactory j = null;

		try {
			c = Class.forName(className);
			j = (JobFactory) c.newInstance();

			boolean end = false;

			while (!end) {
				data = data.substring(index + JobFactory.ENTRY_SEPARATOR.length());
				index = data.indexOf(JobFactory.ENTRY_SEPARATOR);

				while (data.charAt(index + JobFactory.ENTRY_SEPARATOR.length()) == '/' ||
						index > 0 && data.charAt(index - 1) == '\\') {
					index = data.indexOf(JobFactory.ENTRY_SEPARATOR, index + 1);
				}

				String s = null;

				if (index <= 0) {
					s = data;
					end = true;
				} else {
					s = data.substring(0, index);
				}

				String key = s.substring(0, s.indexOf(keyValueSeparator));
				String value = s.substring(s.indexOf(keyValueSeparator) + keyValueSeparator.length());

				j.set(key, value);
			}
		} catch (ClassNotFoundException cnf) {
			Console.root().warn("NodeGroup: Class not found: " + className, cnf);
		} catch (ClassCastException cce) {
			Console.root().warn("NodeGroup: Error casting " +
					Optional.ofNullable(c).map(Class::getName).orElse(null) +
					" to JobFactory", cce);
		} catch (Exception e) {
			Console.root().warn("NodeGroup: " + e, e);
		}

		return j;
	}
}
