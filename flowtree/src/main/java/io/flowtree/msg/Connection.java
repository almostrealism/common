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

package io.flowtree.msg;

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.node.Node;

import java.io.IOException;

/**
 * A peer-to-peer link between a local {@link Node} and a specific
 * remote Node on another Server.
 *
 * <p>A Connection wraps a {@link NodeProxy} (the socket-level server
 * connection) and adds a remote Node ID, so that messages are routed
 * to a particular Node rather than broadcast to the entire remote
 * {@link io.flowtree.node.NodeGroup}.</p>
 *
 * <p>Connections are the mechanism through which the relay loop moves
 * jobs between Nodes. When a Node has excess jobs in its queue, the
 * activity thread calls {@link #sendJob(Job)} on a peer Connection to
 * transfer the job to the remote Node.</p>
 *
 * <p>Connections are established automatically: a Node's activity
 * thread requests a connection via
 * {@link io.flowtree.node.NodeGroup#getConnection(int)}, which sends
 * a {@link Message#ConnectionRequest} through a NodeProxy. The remote
 * NodeGroup responds and both sides add a Connection to their peer
 * sets.</p>
 *
 * @author Mike Murray
 * @see Node
 * @see NodeProxy
 * @see <a href="../docs/node-relay.md">Node Relay and Job Routing</a>
 */
public class Connection implements Runnable, NodeProxy.EventListener {
	/** The local {@link Node} that owns this connection and receives incoming jobs. */
	private final Node node;

	/**
	 * The {@link NodeProxy} through which messages to the remote node are sent.
	 * Replaced by {@link #connect(NodeProxy)} after a reconnect, or set to
	 * {@code null} by {@link #disconnect(NodeProxy)} when the link is lost.
	 */
	private NodeProxy proxy;

	/** The integer ID of the remote node this connection communicates with. */
	private final int id;
	
	/**
	 * Constructs a Connection from {@code node} to a specific remote node identified
	 * by {@code id} on the server reachable through {@code p}.
	 *
	 * <p>The constructor only records the parameters and logs a diagnostic line;
	 * it does not register event listeners or begin sending messages. Call
	 * {@link #start()} after construction to activate the connection.</p>
	 *
	 * @param node  The local {@link Node} that owns this connection.
	 * @param p     The {@link NodeProxy} wrapping the socket to the remote server.
	 * @param id    The integer ID of the target node on the remote server.
	 * @throws IOException  Declared for API compatibility; not thrown by this
	 *                      constructor but may be thrown by subclasses.
	 */
	public Connection(Node node, NodeProxy p, int id) throws IOException {
		System.out.println("Constructing connection from [" + node +
							"] to remote node " + id +
							" by way of " + p);
		
		this.node = node;
		this.proxy = p;
		
		this.id = id;
	}
	
	/**
	 * Activates this connection by registering it as an
	 * {@link NodeProxy.EventListener} on the underlying {@link NodeProxy}.
	 *
	 * <p>Once started, the connection receives connect, disconnect, and
	 * message-arrival callbacks from the proxy's reader thread. This method
	 * must be called after construction before any messages can be processed.</p>
	 */
	public void start() { this.proxy.addEventListener(this); }
	
	/**
	 * @return  The integer node id of the remote node that this Connection object communicates with.
	 */
	public int getRemoteNodeId() { return this.id; }
	
	/**
	 * @return  The NodeProxy object stored by this Connection object.
	 */
	public NodeProxy getNodeProxy() { return this.proxy; }
	
	/**
	 * @return  The activity rating of the node group of the remote node.
	 *          This value is reported to the NodeProxy by the remote node group.
	 */
	public double getActivityRating() { return this.proxy.getActivityRating(); }
	
	/**
	 * Writes the specified message using the proxy stored by this Connection object.
	 * 
	 * @param m Message object to send.
	 * @throws IOException  If an IO error occurs while sending message.
	 */
	public void sendMessage(Message m) throws IOException {
		if (this.proxy == null)
			throw new IOException("Connection not connected to a proxy.");
		else
			this.proxy.writeObject(m, id);
	}
	
	/**
	 * Writes the specified Job object by using a Message object and calling sendMessage.
	 * 
	 * @param j  Job to encode and send.
	 * @throws IOException  If an IO error occurs while sending message.
	 */
	public void sendJob(Job j) throws IOException {
		if (this.proxy == null) throw new IOException("Connection not connected to a proxy.");
		
		Message m = new Message(Message.Job, this.node.getId(), this.proxy);
		m.setJob(j);
		m.send(this.id);
	}
	
	/**
	 * Attempts to confirm this connection.
	 * 
	 * @return  True if this connection is stable, false otherwise.
	 */
	public boolean confirm() {
		if (this.proxy == null) return false;
		
		Boolean b = null;
		
		System.out.println("Connection (" + this +
							"): Confirming connection...");
		
		try {
			Message m = new Message(Message.ConnectionConfirmation,
									this.node.getId(), this.proxy);
			b = (Boolean)m.send(this.id);
		} catch (IOException ioe) {
			return false;
		}

		return b != null && b.booleanValue();
	}
	
	/**
	 * No-op implementation of {@link Runnable#run()}.
	 *
	 * <p>The active message-handling logic that previously lived here has been
	 * superseded by the event-listener model: incoming messages are now delivered
	 * to {@link #recievedMessage(Message, int)} by the {@link NodeProxy}'s reader
	 * thread. This method is retained to satisfy the {@link Runnable} contract but
	 * performs no work.</p>
	 */
	public void run() {
//		loop: while (true) {
//			try {
//				Thread.sleep(500);
//				
//				Message m = (Message) this.proxy.nextObject(this.node.getId());
//				
//				if (m == null) continue loop;
//				
//				if (m.getType() == Message.Job) {
//					if (this.node.getParent() == null) {
//						Message response = new Message(Message.Job, this.node.getId(), this.proxy);
//						response.setString(m.getData());
//						response.send(m.getSender());
//					} else {
//					//	System.out.println("Connection: parent = " + this.node.getParent() +
//					//						" factory = " + this.node.getParent().getJobFactory() +
//					//						" data = " + m.getData());
//						
//						this.node.addJob(this.node.getParent().getJobFactory().createJob(m.getData()));
//					}
//				} else if (m.getType() == Message.ConnectionConfirmation) {
//					if (m.getData() == null) {
//						Message response = new Message(Message.ConnectionConfirmation, this.node.getId(), this.proxy);
//						response.send(m.getSender());
//					}
//				} else if (m.getType() == Message.Kill) {
//					int i = m.getData().indexOf(ENTRY_SEPARATOR);
//					long task = Long.parseLong(m.getData().substring(0, i));
//					int relay = Integer.parseInt(m.getData().substring(i + ENTRY_SEPARATOR.length()));
//					
//					this.node.sendKill(task, relay--);
//				}
//			} catch (IndexOutOfBoundsException obe) {
//				System.out.println("Connection (" + this.toString() + "): " + obe);
//				obe.printStackTrace(System.out);
//			} catch (IllegalThreadStateException its) {
//				System.out.println("Connection (" + this.toString() + "): " + its);
//				its.printStackTrace(System.out);
//			} catch (Exception e) {
//				System.out.println("Connection (" + this.toString() + "): " + e);
//			}
//		}
	}
	
	/**
	 * Called by the {@link NodeProxy} when the underlying socket reconnects after
	 * a reset. Updates the stored proxy reference so that subsequent
	 * {@link #sendMessage} and {@link #sendJob} calls use the new connection.
	 *
	 * @param p  The newly connected {@link NodeProxy}.
	 * @see NodeProxy.EventListener#connect(NodeProxy)
	 */
	public void connect(NodeProxy p) {
		System.out.println(this + ": Connected to " + p);
		this.proxy = p;
	}

	/**
	 * Called by the {@link NodeProxy} when the underlying socket is lost.
	 * Deregisters this listener from the proxy, nulls the proxy reference, and
	 * delegates to {@link Node#disconnect(Connection)} so the owning node can
	 * remove this connection from its active peer set.
	 *
	 * @param p  The {@link NodeProxy} that has disconnected.
	 * @return   The value returned by {@link Node#disconnect(Connection)}, typically
	 *           {@code 0} to indicate the disconnect was handled.
	 * @see NodeProxy.EventListener#disconnect(NodeProxy)
	 */
	public int disconnect(NodeProxy p) {
		System.out.println(this + ": Disconnected from " + p);
		p.removeEventListener(this);
		this.proxy = null;

		return this.node.disconnect(this);
	}

	/**
	 * Handles a message delivered by the {@link NodeProxy} reader thread.
	 *
	 * <p>This method returns {@code false} immediately (yielding to other
	 * listeners) if the receiver does not match the local node's ID or if the
	 * sender does not match this connection's remote node ID. Otherwise it
	 * dispatches on message type:</p>
	 * <ul>
	 *   <li>{@link Message#Job} — decodes the payload via the local node's
	 *       {@link io.flowtree.job.JobFactory} and enqueues the resulting job.</li>
	 *   <li>{@link Message#ConnectionConfirmation} — if the payload is
	 *       {@code null}, sends a confirmation reply with {@code "true"}.</li>
	 *   <li>{@link Message#Kill} — extracts task ID and relay count from the
	 *       payload and forwards the kill signal via
	 *       {@link Node#sendKill(String, int)}.</li>
	 * </ul>
	 *
	 * @param m         The message received from the remote peer.
	 * @param reciever  The node ID encoded in the message's receiver field.
	 * @return  {@code true} if this connection claimed and processed the message;
	 *          {@code false} if the message was not addressed to this connection
	 *          or if a processing error occurred.
	 * @see NodeProxy.EventListener#recievedMessage(Message, int)
	 */
	public boolean recievedMessage(Message m, int reciever) {
		if (reciever != this.node.getId()) return false;
		if (m.getSender() != this.id) return false;
		
		try {
			if (m.getType() == Message.Job) {
				String md = m.getData();
				
				if (md == null) {
					System.out.println(this + ": Job message contains no job data.");
					return true;
				}
				
				if (Message.verbose) {
					int mdi = md.indexOf("RAW");
					String dis = md;
					if (mdi > 0) dis = md.substring(0, mdi + 3);
					System.out.println(this +
										" -- Adding job to node  -- "
										+ dis);
				}
				
				Job received = this.node.getJobFactory().createJob(md);
				if ("relay".equals(this.node.getLabels().get("role"))
						&& this.node.getParent() != null) {
					// Relay nodes must not queue network-received jobs locally.
					// Forward to the parent NodeGroup so routeJob() can route
					// to a capable execution node, preventing relay-to-relay circuits.
					this.node.getParent().addJob(received);
				} else {
					this.node.addJob(received);
				}
			} else if (m.getType() == Message.ConnectionConfirmation) {
				if (m.getData() == null) {
					Message response = new Message(Message.ConnectionConfirmation,
													this.node.getId(), this.proxy);
					response.setString("true");
					response.send(m.getSender());
				}
			} else if (m.getType() == Message.Kill) {
				int i = m.getData().indexOf(JobFactory.ENTRY_SEPARATOR);
				String task = m.getData().substring(0, i);
				int relay = Integer.parseInt(m.getData().substring(i + JobFactory.ENTRY_SEPARATOR.length()));
				
				this.node.sendKill(task, relay--);
			}
		} catch (IndexOutOfBoundsException obe) {
			System.out.println("Connection: " + obe);
			obe.printStackTrace(System.out);
			return false;
		} catch (IllegalThreadStateException tse) {
			System.out.println(this +
							" -- Illegal Thread State (" +
							tse.getMessage() + ")");
			tse.printStackTrace(System.out);
			return false;
		} catch (Exception e) {
			System.out.println("Connection: " + e);
			return false;
		}
		
		return true;
	}
	
	/**
	 * Logs a finalisation notice when this object is garbage-collected.
	 * Useful for diagnosing connection lifecycle issues during development.
	 */
	protected void finalize() { System.out.println("Finalizing " + this); }

	/**
	 * Returns a brief human-readable description of this connection without
	 * ping statistics. Equivalent to {@code toString(false)}.
	 *
	 * @return  A string of the form {@code "Connection from <localNode> to remote node <id> (<proxy>)"}.
	 */
	public String toString() { return this.toString(false); }

	/**
	 * Returns a human-readable description of this connection, optionally
	 * including the underlying proxy's ping statistics.
	 *
	 * @param showStat  When {@code true}, the proxy's
	 *                  {@link NodeProxy#toString(boolean)} is called with
	 *                  {@code true}, appending {@code [min/max/avg]} latency
	 *                  data to the string.
	 * @return  A string identifying the local node name, the remote node ID,
	 *          and a representation of the backing proxy.
	 */
	public String toString(boolean showStat) {

		String b = "Connection from " +
				this.node.getName() +
				" to remote node " +
				this.id +
				" (" +
				this.proxy.toString(showStat) +
				") ";
		
		return b;
	}
}
