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
 * A {@link Connection} is used to relay information between a local node
 * and a remote node.
 * 
 * @author Mike Murray
 */
public class Connection implements Runnable, NodeProxy.EventListener {
	private final Node node;
	private NodeProxy proxy;
	
	private final int id;
	
	/**
	 * Constructs a new Connection object.
	 * 
	 * @param node  Local node.
	 * @param p  NodeProxy to remote server.
	 * @param id  Unique id of remote node.
	 * @throws IOException
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
	 * Adds this Connection object as an event listener to the NodeProxy object stored.
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
	 * @see NodeProxy.EventListener#connect(NodeProxy)
	 */
	public void connect(NodeProxy p) {
		System.out.println(this + ": Connected to " + p);
		this.proxy = p;
	}

	/**
	 * @see NodeProxy.EventListener#disconnect(NodeProxy)
	 * @return  0.
	 */
	public int disconnect(NodeProxy p) {
		System.out.println(this + ": Disconnected from " + p);
		p.removeEventListener(this);
		this.proxy = null;
		
		return this.node.disconnect(this);
	}

	/**
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
				
				this.node.addJob(this.node.getJobFactory().createJob(md));
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
	
	protected void finalize() { System.out.println("Finalizing " + this); }
	
	public String toString() { return this.toString(false); }
	
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
