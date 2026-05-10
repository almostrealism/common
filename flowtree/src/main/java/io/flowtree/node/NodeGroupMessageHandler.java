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

import io.flowtree.fs.OutputServer;
import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.Connection;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import io.flowtree.Server;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;

/**
 * Handles dispatch of incoming {@link Message} objects on behalf of a
 * {@link NodeGroup}.  This class is a package-private collaborator extracted
 * from {@link NodeGroup} to keep that class within the 1 500-line limit imposed
 * by the project's Checkstyle configuration.
 *
 * <p>Only messages addressed to receiver {@code -1} (the group itself, rather
 * than a specific child {@link Node}) are processed. Supported message types
 * are:
 * <ul>
 *   <li>{@link Message#Job} — decodes and routes an inbound job.</li>
 *   <li>{@link Message#StringMessage} — logs a human-readable string.</li>
 *   <li>{@link Message#ConnectionRequest} — assigns the least-connected local
 *       node (or the relay node) to service the request, creates a
 *       {@link Connection}, and sends a {@link Message#ConnectionConfirmation}
 *       back to the requester.</li>
 *   <li>{@link Message#ConnectionConfirmation} — completes the two-phase
 *       connection handshake.</li>
 *   <li>{@link Message#ServerStatus} — updates the sending proxy's activity
 *       rating and average job time from the encoded payload.</li>
 *   <li>{@link Message#ServerStatusQuery} — responds with the list of known
 *       peer servers, excluding the querying peer itself.</li>
 *   <li>{@link Message#ResourceRequest} — looks up the URI of the requested
 *       resource and replies with a {@link Message#ResourceUri}.</li>
 *   <li>{@link Message#Task} — deserialises and registers a new
 *       {@link JobFactory} task.</li>
 *   <li>{@link Message#Kill} — kills the identified task and propagates the
 *       signal to all child nodes.</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see NodeGroup
 */
class NodeGroupMessageHandler implements ConsoleFeatures {

	/**
	 * The {@link NodeGroup} on whose behalf messages are dispatched.
	 * Every state mutation goes through the group's own public API.
	 */
	private final NodeGroup group;

	/**
	 * Constructs a handler bound to the given {@link NodeGroup}.
	 *
	 * @param group  The owning group; must not be {@code null}.
	 */
	NodeGroupMessageHandler(NodeGroup group) {
		this.group = group;
	}

	/**
	 * Dispatches an incoming {@link Message} to the appropriate handler.
	 * Only messages addressed to receiver {@code -1} are processed here;
	 * all others are ignored and {@code false} is returned.
	 *
	 * @param m         The received message.
	 * @param receiver  ID of the intended recipient; must be {@code -1} for this
	 *                  method to handle the message.
	 * @return {@code true} if the message was handled; {@code false} otherwise.
	 */
	boolean recievedMessage(Message m, int receiver) {
		if (receiver != -1) {
			return false;
		}

		NodeProxy p = m.getNodeProxy();

		int type = m.getType();
		int remoteId = m.getSender();

		if (type == Message.Job) {
			log("NodeGroup: Received job. Data = " + m.getData());
			Job j = group.getJobFactory().createJob(m.getData());
			group.routeJobFromHandler(j);
		} else if (type == Message.StringMessage) {
			log("Message from " + p.toString() + ": " + m.getData());
		} else if (type == Message.ConnectionRequest) {
			handleConnectionRequest(p, remoteId);
		} else if (type == Message.ConnectionConfirmation) {
			handleConnectionConfirmation(m, p, remoteId);
		} else if (type == Message.ServerStatus) {
			return handleServerStatus(m, p);
		} else if (type == Message.ServerStatusQuery) {
			handleServerStatusQuery(m, p, remoteId);
		} else if (type == Message.ResourceRequest) {
			handleResourceRequest(m, p, remoteId);
		} else if (type == Message.Task) {
			if (m.getData() != null) {
				group.addTask(m.getData());
			} else {
				group.displayMessage("Received null task.");
			}
		} else if (type == Message.Kill) {
			int i = m.getData().indexOf(JobFactory.ENTRY_SEPARATOR);
			String task = m.getData().substring(0, i);
			int relay = Integer.parseInt(m.getData().substring(i + JobFactory.ENTRY_SEPARATOR.length()));
			group.sendKill(task, relay--);
		} else {
			return false;
		}

		return true;
	}

	/**
	 * Handles a {@link Message#ConnectionRequest} by selecting the least-connected
	 * available node (falling back to the relay node), constructing a
	 * {@link Connection}, and sending back a {@link Message#ConnectionConfirmation}.
	 *
	 * @param p        The {@link NodeProxy} that delivered the message.
	 * @param remoteId The sender's node ID, used when replying.
	 */
	private void handleConnectionRequest(NodeProxy p, int remoteId) {
		try {
			Node n = group.getLeastConnectedNode();
			if (n == null) {
				n = group.getRelayNode();
			}

			Connection c;

			if (n == null) {
				warn("NodeGroup: ConnectionRequest rejected -- no available node (no workers, no relay)");
				c = null;
			} else if (n.getPeers().length >= n.getMaxPeers()) {
				warn("NodeGroup: ConnectionRequest rejected -- node " + n.getId() +
						" at peer capacity (" + n.getPeers().length + "/" + n.getMaxPeers() + ")");
				c = null;
			} else if (n.isConnected(p)) {
				if (Message.verbose) {
					warn("NodeGroup: ConnectionRequest rejected -- node " + n.getId() +
							" already connected via proxy " + p);
				}
				c = null;
			} else {
				log("NodeGroup: Constructing connection...");
				c = new Connection(n, p, remoteId);
			}

			if (c != null && n.connect(c)) {
				Message response = new Message(Message.ConnectionConfirmation, n.getId(), p);
				response.setString("true");
				response.send(remoteId);
			} else {
				if (c != null) {
					warn("NodeGroup: ConnectionRequest rejected -- n.connect(c) returned false for node " + n.getId());
				}
				Message response = new Message(-1, -1, p);
				response.setString("false");
				response.send(remoteId);
			}
		} catch (IOException ioe) {
			warn(String.valueOf(ioe));
		}
	}

	/**
	 * Handles a {@link Message#ConnectionConfirmation}, completing the two-phase
	 * handshake by echoing a confirmation when the data payload is {@code null}.
	 *
	 * @param m        The incoming confirmation message.
	 * @param p        The {@link NodeProxy} that delivered the message.
	 * @param remoteId The sender's node ID, used when replying.
	 */
	private void handleConnectionConfirmation(Message m, NodeProxy p, int remoteId) {
		if (m.getData() == null) {
			try {
				Message response = new Message(Message.ConnectionConfirmation, -1, p);
				response.setString("true");
				response.send(remoteId);
			} catch (IOException ioe) {
				warn(String.valueOf(ioe));
			}
		}
	}

	/**
	 * Handles a {@link Message#ServerStatus} message by parsing the semicolon-delimited
	 * payload and updating the sending proxy's activity rating and job time.
	 *
	 * @param m  The incoming status message.
	 * @param p  The {@link NodeProxy} whose metrics are updated.
	 * @return {@code true} if at least one recognised status field was parsed.
	 */
	private boolean handleServerStatus(Message m, NodeProxy p) {
		String[] s = m.getData().split(";");

		boolean h = false;

		for (int i = 0; i < s.length; i++) {
			int index = s[i].indexOf(JobFactory.ENTRY_SEPARATOR);
			String v = "";
			if (index > 0 && index < s[i].length() - 1) {
				v = s[i].substring(index + JobFactory.ENTRY_SEPARATOR.length());
			}

			try {
				if (s[i].startsWith("jobtime" + JobFactory.ENTRY_SEPARATOR)) {
					p.setJobTime(Double.parseDouble(v));
					h = true;
				} else if (s[i].startsWith("activity" + JobFactory.ENTRY_SEPARATOR)) {
					p.setActivityRating(Double.parseDouble(v));
					h = true;
				} else {
					warn("NodeGroup: Unknown status type '" + s[i] + "'");
				}
			} catch (NumberFormatException nfe) {
				warn("NodeGroup: Could not parse status item '" +
						s[i] + "' (" + nfe.getMessage() + ")");
			}
		}

		return h;
	}

	/**
	 * Handles a {@link Message#ServerStatusQuery} by responding with the list of
	 * peer servers known to this group, excluding the querying proxy itself.
	 *
	 * @param m        The incoming query message.
	 * @param p        The {@link NodeProxy} that sent the query (excluded from reply).
	 * @param remoteId The sender's node ID, used when replying.
	 */
	private void handleServerStatusQuery(Message m, NodeProxy p, int remoteId) {
		if (!m.getData().equals("peers")) {
			return;
		}

		try {
			Message response = new Message(Message.ServerStatus, -1, p);

			NodeProxy[] svs = group.getServers();

			StringBuilder b = new StringBuilder();
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

			if (Message.verbose) {
				log("NodeGroup: Reported " + (svs.length - j) +
						" peers for status query (Excluded " + p + ").");
			}

			response.setString(b.toString());
			response.send(remoteId);
		} catch (IOException ioe) {
			warn("NodeGroup: Error sending server status (" + ioe.getMessage() + ")");
		}
	}

	/**
	 * Handles a {@link Message#ResourceRequest} by looking up the requested
	 * resource URI from the current {@link OutputServer} and replying with
	 * a {@link Message#ResourceUri} message.
	 *
	 * @param m        The incoming resource-request message.
	 * @param p        The {@link NodeProxy} that sent the request.
	 * @param remoteId The sender's node ID, used when replying.
	 */
	private void handleResourceRequest(Message m, NodeProxy p, int remoteId) {
		try {
			Message response = new Message(Message.ResourceUri, -1, p);

			Server s = OutputServer.getCurrentServer().getNodeServer();
			String r = s.getResourceUri(m.getData());
			log("NodeGroup: Sending resource uri (" + r + ")");
			response.setString(r);
			response.send(remoteId);
		} catch (IOException ioe) {
			warn("NodeGroup: Error sending resource uri (" + ioe.getMessage() + ")");
		}
	}
}
