/*
 * Copyright 2022 Michael Murray
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

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;

/**
 * The wire-format envelope used to exchange all typed payloads between
 * FlowTree nodes.
 *
 * <p>A {@code Message} carries a {@link #getType() type code}, integer
 * {@link #getSender() sender} and {@link #getReceiver() receiver} node IDs,
 * and an optional {@link #getData() string payload}. It implements
 * {@link java.io.Externalizable} so that {@link NodeProxy} can serialise it
 * directly onto a Java object stream, or convert it to/from a compact byte
 * array for encrypted transport.</p>
 *
 * <h2>Message types</h2>
 * <p>Each type constant defines both the semantic purpose and the expected
 * payload format:</p>
 * <ul>
 *   <li>{@link #Job} — encoded {@link io.flowtree.job.Job} payload produced
 *       by {@link io.flowtree.job.Job#encode()}.</li>
 *   <li>{@link #StringMessage} — arbitrary string payload.</li>
 *   <li>{@link #ConnectionRequest} / {@link #ConnectionConfirmation} — used
 *       during the two-way handshake that establishes a {@link Connection}.</li>
 *   <li>{@link #ServerStatusQuery} / {@link #ServerStatus} — peer discovery
 *       and status exchange; the response carries a comma-separated peer list.</li>
 *   <li>{@link #ResourceRequest} / {@link #ResourceUri} /
 *       {@link #DistributedResourceUri} / {@link #DistributedResourceInvalidate} —
 *       distributed resource lookup and cache-invalidation protocol.</li>
 *   <li>{@link #Task} — a task descriptor sent from a coordinating node to a
 *       worker node group.</li>
 *   <li>{@link #Kill} — instructs the receiver to cancel a running task;
 *       payload is {@code "<taskId><ENTRY_SEPARATOR><relayCount>"}.</li>
 *   <li>{@link #Ping} — round-trip latency probe; the receiver echoes the
 *       payload back.</li>
 * </ul>
 *
 * <h2>Local vs remote messages</h2>
 * <p>Messages created by the local JVM (via the multi-argument constructors) are
 * marked {@link #isLocalMessage() local}. Messages reconstructed from the wire
 * (via the single-{@link NodeProxy} constructor or {@link #readExternal}) are
 * marked remote. Only local messages carry a live {@link Node} reference used
 * during connection handshakes.</p>
 *
 * @author Michael Murray
 * @see NodeProxy
 * @see Connection
 */
public class Message implements Externalizable {
	/** Type code for a message carrying an encoded {@link io.flowtree.job.Job}. */
	public static final int Job = 1;

	/** Type code for a message carrying a plain string payload. */
	public static final int StringMessage = 2;

	/**
	 * Type code sent by a node that wishes to open a new {@link Connection}
	 * to a specific remote node. The receiving {@link NodeProxy} responds with
	 * a {@link #ConnectionConfirmation} containing the remote node's ID.
	 */
	public static final int ConnectionRequest = 4;

	/**
	 * Type code used to acknowledge a {@link #ConnectionRequest} or to confirm
	 * that an existing connection is still alive. When {@link #getData()} is
	 * {@code null} the recipient must reply with a confirmation carrying
	 * {@code "true"} as data.
	 */
	public static final int ConnectionConfirmation = 8;

	/** Type code for a request to retrieve the remote server's status and peer list. */
	public static final int ServerStatusQuery = 10;

	/**
	 * Type code for a server status reply. The payload begins with
	 * {@code "peers:"} followed by a comma-separated list of peer addresses.
	 */
	public static final int ServerStatus = 11;

	/**
	 * Type code for a request to resolve a named resource to a URI.
	 * The payload is the resource name; the reply is a {@link #ResourceUri} message.
	 */
	public static final int ResourceRequest = 12;

	/**
	 * Type code for a response to a {@link #ResourceRequest} containing the
	 * resolved resource URI as payload.
	 */
	public static final int ResourceUri = 13;

	/**
	 * Type code that broadcasts a distributed resource URI so that other nodes
	 * can cache it locally.
	 */
	public static final int DistributedResourceUri = 14;

	/**
	 * Type code that instructs all peers to invalidate their cached entry for
	 * a distributed resource.
	 */
	public static final int DistributedResourceInvalidate = 15;

	/**
	 * Type code for a task descriptor sent from a coordinating node to a
	 * worker node group, as distinct from a fully encoded {@link #Job}.
	 */
	public static final int Task = 16;

	/**
	 * Type code that instructs the receiver to cancel a running task. The
	 * payload is {@code "<taskId><ENTRY_SEPARATOR><relayCount>"}, where
	 * {@code relayCount} is decremented at each hop so that the kill signal
	 * propagates a bounded number of relay steps.
	 */
	public static final int Kill = 32;

	/**
	 * Type code for a round-trip latency probe. The sender includes a random
	 * string payload; a receiver with sender ID {@code -1} echoes the exact
	 * payload back so that the original sender can measure elapsed time.
	 */
	public static final int Ping = 64;

	/**
	 * When {@code true}, every message serialisation and deserialisation event
	 * is printed to standard output. Intended for protocol-level debugging only.
	 */
	public static boolean verbose = false;

	/**
	 * When {@code true}, low-level details of encrypted byte wrappers (padding
	 * and truncation) are printed to standard output.
	 */
	public static boolean dverbose = false;

	/**
	 * When {@code true}, stream locking and synchronisation events inside
	 * {@link NodeProxy} are printed to standard output.
	 */
	public static boolean sverbose = false;

	/** Integer type code identifying the kind of payload this message carries. */
	private transient int type;

	/**
	 * Node ID of the sending node, or {@code -1} if unset.
	 * Populated from the wire during deserialisation.
	 */
	private transient int sender = -1;

	/**
	 * Node ID of the intended receiving node, or {@code -1} if unset.
	 * Set by {@link NodeProxy#writeObject} just before the message is written.
	 */
	private transient int receiver = -1;

	/**
	 * The {@link NodeProxy} through which this message will be (or was) transmitted.
	 * {@code transient}: never serialised to the wire.
	 */
	private transient NodeProxy proxy;

	/**
	 * The local {@link Node} acting as the connection endpoint for
	 * {@link #ConnectionRequest} messages. {@code null} for remote messages.
	 */
	private transient Node node;

	/**
	 * The string payload carried by this message. Interpretation depends on
	 * {@link #type}. {@code null} is a valid value for several message types
	 * (e.g. a {@link #ConnectionConfirmation} ping from a remote node).
	 */
	private transient String data;

	/**
	 * {@code true} when this message was constructed by the local JVM;
	 * {@code false} when it was received from the wire.
	 */
	private transient boolean local;

	/**
	 * When {@code true}, the message bypasses the outbound queue and is written
	 * directly to the socket even while the queue is active.
	 *
	 * @see NodeProxy#flushQueue()
	 */
	private transient boolean bypass;

	/**
	 * No-argument constructor required by the {@link java.io.Externalizable}
	 * contract. Must not be used directly; the assertion will fire and a stack
	 * trace is printed to aid diagnosis.
	 */
	public Message() {
		assert false : "Message constructed with no NodeProxy";
		new Exception().printStackTrace();
	}
	
	/**
	 * Constructs a {@link Message} that will be treated as a message
	 * received from a remote node. The type, sender, receiver, and data
	 * fields are populated later by {@link #readExternal} or {@link #setBytes}.
	 *
	 * @param p  The {@link NodeProxy} through which this message arrived.
	 */
	public Message(NodeProxy p) {
		this.local = false;
		this.proxy = p;
	}
	
	/**
	 * Constructs a new outbound {@link Message} of the specified type without
	 * binding it to a {@link NodeProxy}. The proxy must be supplied before
	 * calling {@link #send}. Equivalent to {@code Message(type, id, null)}.
	 *
	 * @param type  Type code identifying the kind of payload (e.g. {@link #Job},
	 *              {@link #ConnectionRequest}).
	 * @param id    Integer ID of the local sending node.
	 * @throws IOException  Declared for API compatibility; never thrown by this constructor.
	 */
	public Message(int type, int id) throws IOException {
		this(type, id, null);
	}
	
	/**
	 * Constructs a new outbound {@link Message} of the specified type, bound to the
	 * given {@link NodeProxy} for transmission.
	 *
	 * @param type  Type code identifying the kind of payload (e.g. {@link #Job},
	 *              {@link #ConnectionRequest}).
	 * @param id    Integer ID of the local sending node.
	 * @param p     The {@link NodeProxy} through which this message will be sent,
	 *              or {@code null} if the proxy will be supplied later.
	 * @throws IOException  Declared for API compatibility; never thrown by this constructor.
	 */
	public Message(int type, int id, NodeProxy p) throws IOException {
		this.type = type;
		this.sender = id;
		
		this.proxy = p;
		
		this.local = true;
	}
	
	/**
	 * Controls whether this message bypasses the outbound queue when sent.
	 *
	 * <p>When the {@link NodeProxy} is in queuing mode (between a disconnect and
	 * the subsequent reconnect flush), messages are normally buffered. Setting
	 * {@code bypass = true} causes {@link #send} to call
	 * {@link NodeProxy#writeObject(Object, int, boolean)} with the queue disabled,
	 * writing directly to the socket regardless of queue state. Use this only for
	 * control-plane messages that must not be deferred.</p>
	 *
	 * @param bypass  {@code true} to write directly to the socket, bypassing the queue.
	 */
	public void setQueueBypass(boolean bypass) { this.bypass = bypass; }
	
	/**
	 * Sets the Job to be sent with this message, if this message is the correct type.
	 * 
	 * @param j  The Job object to be encoded and transmitted.
	 */
	public void setJob(Job j) { if (this.type == Message.Job) this.data = j.encode(); }
	
	/**
	 * Sets the String to be sent with this message, if this message is the correct type.
	 * 
	 * @param s  The String to be transmitted.
	 */
	public void setString(String s) { this.data = s; }
	
	/**
	 * Sets the local node to be used as an endpoint if this message is a connection request.
	 * 
	 * @param node  Node object to connect with.
	 */
	public void setLocalNode(Node node) { if (this.local) this.node = node; }
	
	/**
	 * @return  The integer id of the node that is the sender of this message.
	 */
	public int getSender() { return this.sender; }
	
	/**
	 * Sets the integer id of the node that is the reciever of this message.
	 * 
	 * @param id  Integer id to use.
	 */
	public void setReceiver(int id) { this.receiver = id; }
	
	/**
	 * @return  The integer id of the node that is the reciever of this message.
	 */
	public int getReceiver() { return this.receiver; }
	
	/** @return  The integer type code for this message. */
	public int getType() { return this.type; }
	
	/** @return  The data stored by this message. */
	public String getData() { return this.data; }
	
	/**
	 * @return  True if this message was produced by a local node, false if it was sent by a remote node.
	 */
	public boolean isLocalMessage() { return this.local; }
	
	/**
	 * @return  The NodeProxy object stored by this Message object.
	 */
	public NodeProxy getNodeProxy() { return this.proxy; }
	
	/**
	 * Sends the data for this message and waits for a response, which is returned as an Object.
	 * 
	 * @param id  Unique id of remote node.
	 * @throws IOException  If an IO error occurs while sending the message.
	 * @return  Response to message.
	 */
	public Object send(int id) throws IOException {
		if (proxy == null) return null;
		
		if (this.type == Message.Job && this.data == null) {
			if (Message.verbose)
				System.out.println("Message: Null job not sent.");
			return null;
		}
		
		if (this.bypass)
			this.proxy.writeObject(this, id, false);
		else
			this.proxy.writeObject(this, id);
			
		if (this.type == Message.ConnectionRequest) {
			Message m = (Message) this.proxy.waitFor(this.sender, 10000);
			
			if (m != null && m.getSender() >= 0) {
				System.out.println("Message: Constructing connection...");
				return new Connection(this.node, this.proxy, m.getSender());
			}
		} else if (this.type == Message.ConnectionConfirmation && this.data == null) {
			Message m = (Message) this.proxy.waitFor(this.sender, 10000);
			
			if (m == null)
				return Boolean.valueOf(false);
			else
				return Boolean.valueOf(m.getData());
		} else if (this.type == Message.ServerStatusQuery) {
			Message m = (Message) this.proxy.waitForMessage(Message.ServerStatus, null, 10000);
			
			if (m == null) {
				return null;
			} else if (m.getData().startsWith("peers:")) {
				String ps = m.getData();
				ps = ps.substring(ps.indexOf(JobFactory.ENTRY_SEPARATOR) + JobFactory.ENTRY_SEPARATOR.length());
				String[] peers = ps.split(",");
				ArrayList l = new ArrayList(peers.length);
				
				for (int i = 0; i < peers.length; i++) {
					int index = peers[i].indexOf("/");
					if (index >= 0)
						peers[i] = peers[i].substring(index + 1);
					
					l.add(peers[i]);
				}
				
				return l;
			}
		} else if (this.type == Message.ResourceRequest) {
			System.out.println("Message: Sending resource request to " + this.proxy + " for " + this.data);

			Message m = (Message) this.proxy.waitForMessage(Message.ResourceUri, null, 10000);
			
			if (m == null)
				return null;
			else
				return m.getData();
		}
		
		return null;
	}
	
	/**
	 * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
	 */
	public void writeExternal(ObjectOutput out) throws IOException {
		if (Message.verbose) System.out.println("Write " + this);
		
		out.writeInt(this.sender);
		out.writeInt(this.receiver);
		out.writeInt(this.type);
		out.writeObject(this.data);
	}
	
	/**
	 * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
	 */
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		this.sender = in.readInt();
		this.receiver = in.readInt();
		this.type = in.readInt();
		
		this.data = (String) in.readObject();
		
		if (Message.verbose) System.out.println("Read " + this);
		
		this.node = null;
		this.local = false;
	}
	
	/**
	 * Populates this message from a compact byte array produced by the encrypted
	 * transport path. The layout is:
	 * <pre>
	 *   b[0] — sender node ID (cast to byte)
	 *   b[1] — receiver node ID (cast to byte)
	 *   b[2] — message type code (cast to byte)
	 *   b[3..] — UTF-8 encoded string payload (optional)
	 * </pre>
	 * This method marks the message as remote ({@link #isLocalMessage()} returns
	 * {@code false}) and clears the node reference.
	 *
	 * @param b  The raw byte array read from the decrypted {@link NodeProxy.ByteWrapper}.
	 */
	public void setBytes(byte[] b) {
		this.sender = b[0];
		this.receiver = b[1];
		this.type = b[2];
		
		if (b.length > 3)
			this.data = new String(b, 3, b.length - 3);
		
		if (Message.verbose)
			System.out.println("Read " + b.length + " bytes " + this);
		
		this.node = null;
		this.local = false;
	}
	
	/**
	 * Serialises this message to a compact byte array for encrypted transport.
	 * The layout mirrors {@link #setBytes}: bytes 0–2 hold sender, receiver, and
	 * type (each truncated to a single byte), followed by the UTF-8 encoding of
	 * the string payload, if any.
	 *
	 * @return  A byte array representation of this message suitable for wrapping
	 *          in a {@link NodeProxy.ByteWrapper} before encryption.
	 */
	public byte[] getBytes() {
		byte[] db = new byte[0];
		if (this.data != null) db = this.data.getBytes();
		byte[] b = new byte[3 + db.length];
		
		b[0] = (byte) this.sender;
		b[1] = (byte) this.receiver;
		b[2] = (byte) this.type;

		System.arraycopy(db, 0, b, 3, db.length);
		
		if (Message.verbose)
			System.out.println("Write " + b.length + " bytes " + this);
		
		return b;
	}
	
	/**
	 * Returns a human-readable summary of this message for logging and debugging.
	 * The format is {@code "Message: <sender> <receiver> <typeName> <truncatedData>"},
	 * where {@code typeName} is the symbolic name of the type code and
	 * {@code truncatedData} is the payload trimmed to 100 characters.
	 *
	 * @return  A concise string representation of this message.
	 */
	public String toString() {
		String t = null;
		
		if (this.type == Message.Job)
			t = "Job";
		else if (this.type == Message.StringMessage)
			t = "String";
		else if (this.type == Message.ConnectionRequest)
			t = "Connection";
		else if (this.type == Message.ConnectionConfirmation)
			t = "Confirm";
		else if (this.type == Message.ResourceRequest)
			t = "RequestUri";
		else if (this.type == Message.ResourceUri)
			t = "ResourceUri";
		else if (this.type == Message.DistributedResourceUri)
			t = "DistResourceUri";
		else if (this.type == Message.DistributedResourceInvalidate)
			t = "DistResourceDel";
		else if (this.type == Message.Task)
			t = "Task";
		else if (this.type == Message.Kill)
			t = "Kill";
		else if (this.type == Message.Ping)
			t = "Ping";
		else
			t = String.valueOf(this.type);
		
		String s = this.data;
		if (s != null && s.length() > 100) s = s.substring(0, 100) + "...";
		
		return "Message: "+ this.sender + " " + this.receiver + " " + t + " " + s;
	}
}
