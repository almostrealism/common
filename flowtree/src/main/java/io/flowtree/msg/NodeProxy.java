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

import io.almostrealism.db.Query;
import io.flowtree.Server;
import io.flowtree.node.Client;
import io.flowtree.node.NodeGroup;
import io.flowtree.node.Proxy;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.io.EOFException;
import java.io.Externalizable;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.io.StreamCorruptedException;
import java.io.UTFDataFormatException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Socket-level transport for peer-to-peer communication between FlowTree nodes.
 *
 * <p>A {@code NodeProxy} wraps a single TCP {@link Socket} to a remote server and
 * provides the following services:</p>
 * <ul>
 *   <li><strong>Framed object I/O</strong> — messages and queries are written to an
 *       {@link ObjectOutputStream} preceded by a UTF header string
 *       ({@link #msgHeader} or {@link #queryHeader}) that allows the reader thread
 *       to construct the correct {@link Externalizable} type.</li>
 *   <li><strong>Optional PBE encryption</strong> — when a password is supplied at
 *       construction time, each payload is encrypted/decrypted with a
 *       {@code PBEWithMD5AndDES} cipher via {@link NodeProxyByteWrapper}
 *       before it touches the stream.</li>
 *   <li><strong>Outbound queuing</strong> — writes issued while the proxy is
 *       reconnecting are buffered in {@link #queue} and flushed automatically once
 *       the socket is re-established.</li>
 *   <li><strong>Event dispatch</strong> — registered {@link EventListener}s are
 *       notified of connect, disconnect, and message-arrival events from the
 *       dedicated reader thread.</li>
 *   <li><strong>Periodic ping</strong> — every {@link #pingFreq} messages the proxy
 *       launches a background thread that measures round-trip latency and forwards
 *       the local server's status to the peer.</li>
 *   <li><strong>Auto-reconnect</strong> — up to three reconnection attempts are made
 *       after stream corruption or EOF before the proxy gives up and fires a final
 *       disconnect event.</li>
 * </ul>
 *
 * <p>Instances are not created directly by application code; they are created by
 * {@link io.flowtree.node.NodeGroup} when a new socket connection is accepted or
 * established.</p>
 *
 * @author Mike Murray
 * @see Message
 * @see Connection
 * @see io.flowtree.node.NodeGroup
 */
public class NodeProxy implements Proxy, Runnable {
	/**
	 * Milliseconds the reader thread sleeps between successive checks for incoming
	 * data. Reducing this value increases responsiveness at the cost of CPU usage.
	 */
	public static int sleep = 100;

	/**
	 * Maximum time in milliseconds that a database query dispatched via
	 * {@link #run()} will be allowed to execute on the remote server before it
	 * is considered timed out.
	 */
	public static long queryTimeout = 900000;

	/**
	 * Logical service name used to identify this FlowTree cluster. Sent as part
	 * of server-status messages so that heterogeneous clusters can be distinguished.
	 */
	public static String serviceName = "RINGS";

	/**
	 * UTF header string written before every serialised {@link Message} on the
	 * output stream. The reader thread uses this to select the correct
	 * {@link Externalizable} implementation for deserialisation.
	 */
	public static final String msgHeader = "msg";

	/**
	 * UTF header string written before every serialised
	 * {@link io.almostrealism.db.Query} on the output stream.
	 */
	public static final String queryHeader = "query";

	/** The JCE security provider used for PBE key and cipher operations. */
	private final String securityProvider = "SunJCE";

	/** PBE cipher algorithm name passed to {@link Cipher#getInstance}. */
	private String cipherAlgorithm = "PBEWithMD5AndDES";

	/**
	 * Default PBE salt shared by all {@code NodeProxy} instances that do not
	 * override it. Exposed as a public constant so that external tooling can
	 * verify compatibility.
	 */
	public static final byte[] defaultSalt = {
			(byte)0xc7, (byte)0x73, (byte)0x21, (byte)0x8c,
			(byte)0x7e, (byte)0xc8, (byte)0xee, (byte)0x99
	};

	/** Instance-level PBE salt; matches {@link #defaultSalt} in this implementation. */
	private final byte[] salt = {
			(byte)0xc7, (byte)0x73, (byte)0x21, (byte)0x8c,
			(byte)0x7e, (byte)0xc8, (byte)0xee, (byte)0x99
	};

	/**
	 * Default PBE iteration count used when constructing the cipher. Higher values
	 * increase the cost of brute-force attacks against the password.
	 */
	public static final int defaultCount = 20;

	/** Instance-level PBE iteration count; matches {@link #defaultCount}. */
	private final int count = 20;

	/**
	 * Shared static decrypt cipher used by {@link NodeProxyByteWrapper#NodeProxyByteWrapper()}
	 * when no cipher is provided explicitly. Package-private so that the extracted
	 * {@link NodeProxyByteWrapper} class can reference it. Populated by the first
	 * {@code NodeProxy} that initialises a secure session.
	 */
	static Cipher sInc;

	/**
	 * Counter incremented for every message received; reset to {@code 1} after a
	 * periodic ping is performed. Used to trigger the background ping every
	 * {@link #pingFreq} messages.
	 */
	private int lastPing = 1;

	/**
	 * Number of messages between successive periodic pings. A ping is initiated
	 * when {@code lastPing % pingFreq == 0}.
	 */
	private final int pingFreq = 40;

	/**
	 * Result of the most recent multi-ping round. Format:
	 * {@code {min, max, avg, deviation, errorFlag}}, as returned by
	 * {@link #ping(int, int, int)}. {@code null} until the first ping completes.
	 */
	private double[] pingStat;

	/**
	 * Background thread executing the periodic ping sequence, or {@code null}
	 * when no ping is in progress.
	 */
	private Thread pingThread;

	/**
	 * Backward-compatible alias for {@link NodeProxyEventListener}.
	 * All method declarations live in the parent interface; this alias exists
	 * solely so that pre-existing {@code implements NodeProxy.EventListener}
	 * and {@code NodeProxy.EventListener} type references continue to compile.
	 */
	public interface EventListener extends NodeProxyEventListener { }

	/**
	 * Milliseconds of silence on the input stream before the proxy attempts to
	 * reconfirm the connection with a {@link Message#ConnectionConfirmation}.
	 * Implemented as a null-read counter: each read-loop iteration that finds no
	 * data increments {@link #nullCount} by one (where one count ≈ {@link #sleep}
	 * milliseconds), so the effective timeout is {@code timeout * sleep} ms.
	 */
	private final int timeout = 20000;

	/**
	 * Maximum number of received objects that may be held in the inbox list
	 * ({@link #obj}) at one time. When the limit is reached the oldest entry is
	 * evicted to prevent unbounded memory growth.
	 */
	private final int maxStore = 100;

	/**
	 * Human-readable label for this proxy, derived from the remote socket's
	 * {@link InetAddress#toString()} at construction time. Used in log messages
	 * and {@link #toString()}.
	 */
	private final String label;

	/** The underlying TCP socket connecting this JVM to the remote server. */
	private Socket soc;

	/**
	 * Object input stream wrapping the socket's input, used by the reader thread
	 * to receive messages and queries.
	 */
	private ObjectInputStream in;

	/**
	 * Object output stream wrapping the socket's output, used by
	 * {@link #writeObject} to send messages and queries.
	 */
	private ObjectOutputStream out;

	/**
	 * Optional duplicate output stream that mirrors every write to a local file
	 * when {@link Message#verbose} is {@code true}. {@code null} in normal operation.
	 */
	private ObjectOutputStream fout;

	/**
	 * PBE decrypt cipher used to decrypt incoming {@link NodeProxyByteWrapper}s,
	 * or {@code null} when {@link #secure} is {@code false}.
	 */
	private Cipher inc;

	/**
	 * PBE encrypt cipher used to encrypt outgoing payloads before writing them
	 * as {@link NodeProxyByteWrapper}s, or {@code null} when {@link #secure} is
	 * {@code false}.
	 */
	private Cipher outc;

	/**
	 * {@code true} when PBE encryption is active for this proxy (i.e. a password
	 * was supplied at construction). {@code false} for plaintext connections.
	 */
	private boolean secure = true;

	/**
	 * {@code true} while the socket is open and the reader thread is running.
	 * Set to {@code false} by {@link #close()} and by {@link #fireDisconnect()}.
	 */
	private boolean connected;

	/**
	 * When {@code true}, the reader loop will call {@link #reset()} on the next
	 * iteration to re-establish the socket connection.
	 */
	private boolean reset;

	/**
	 * When {@code true}, calls to {@link #writeObject(Object, int)} enqueue the
	 * object in {@link #queue} rather than writing it directly. Activated during
	 * reconnection; cleared and flushed by {@link #flushQueue()}.
	 */
	private boolean useQueue = true;

	/**
	 * Number of consecutive reset attempts made since the last successful stream
	 * read. When this reaches 3 the reader loop exits and the socket is closed.
	 */
	private int resets;

	/**
	 * Number of consecutive read-loop iterations that found no data on the input
	 * stream. When this exceeds {@link #timeout} a connection-confirm is sent to
	 * verify the peer is still alive.
	 */
	private int nullCount;

	/**
	 * Count of messages received since the last call to {@link #getInputRate()}.
	 * Reset to zero by that method.
	 */
	private int currentMsgIn;

	/**
	 * Wall-clock time (ms since epoch) of the last {@link #getInputRate()} call.
	 * Used to compute the per-minute message rate.
	 */
	private long checkedMsgIn;

	/**
	 * Inbox list of received {@link NodeProxyStoredObject} instances waiting to be
	 * claimed by a caller via {@link #nextObject} or {@link #nextMessage}.
	 * Access is synchronised on the list itself.
	 */
	private final List obj;

	/**
	 * List of registered {@link EventListener}s. Listeners are called on the
	 * reader thread; modifications are guarded by {@code synchronized(listeners)}.
	 */
	private final List listeners;

	/**
	 * Outbound queue used to buffer writes that arrive while the proxy is
	 * reconnecting. Flushed to the socket in order by {@link #flushQueue()}.
	 */
	private final List queue;

	/**
	 * Most recent job execution time (in seconds) reported by the remote peer
	 * via a server-status message. Stored here so that higher layers can make
	 * load-balancing decisions without a network round-trip.
	 */
	private double jobtime;

	/**
	 * Activity rating of the remote peer's node group, as last reported via a
	 * server-status message. Higher values indicate heavier load.
	 */
	private double activity;
	
	/**
	 * Constructs a new {@code NodeProxy} for the given socket without encryption.
	 * Equivalent to {@code NodeProxy(s, null, null, false)}.
	 *
	 * <p>This constructor obtains the socket's I/O streams, builds a
	 * {@link ObjectOutputStream} / {@link ObjectInputStream} pair, and starts a
	 * daemon reader thread that processes incoming data.</p>
	 *
	 * @param s  The connected {@link Socket} to the remote server.
	 * @throws IOException                        If an I/O error occurs while wrapping the socket streams.
	 * @throws NoSuchAlgorithmException           Not thrown by this overload (no cipher initialised).
	 * @throws InvalidAlgorithmParameterException Not thrown by this overload.
	 * @throws NoSuchPaddingException             Not thrown by this overload.
	 * @throws InvalidKeySpecException            Not thrown by this overload.
	 * @throws InvalidKeyException                Not thrown by this overload.
	 */
	public NodeProxy(Socket s) throws IOException,
									NoSuchAlgorithmException,
									InvalidKeyException,
									InvalidKeySpecException,
									NoSuchPaddingException,
									InvalidAlgorithmParameterException {
		this(s, null, null, false);
	}

	/**
	 * Constructs a new {@code NodeProxy} for the given socket, optionally
	 * enabling PBE encryption.
	 *
	 * <p>When {@code passwd} is non-null, both an encrypt and a decrypt
	 * {@link Cipher} are initialised using {@code PBEWithMD5AndDES} (or the
	 * algorithm specified by {@code cipher}). All subsequent writes are encrypted
	 * by {@link NodeProxyByteWrapper} and all reads are decrypted before dispatch.</p>
	 *
	 * <p>After stream setup a daemon reader thread is started under the current
	 * {@link io.flowtree.node.Client}'s {@link Server#getThreadGroup() ThreadGroup},
	 * if one is available.</p>
	 *
	 * @param s       The connected {@link Socket} to the remote server.
	 * @param passwd  Password for PBE encryption, or {@code null} for plaintext.
	 * @param cipher  PBE cipher algorithm name (e.g. {@code "PBEWithMD5AndDES"}),
	 *                or {@code null} to use the default.
	 * @param server  Reserved for future server-side initialisation; currently unused.
	 * @throws IOException                        If an I/O error occurs while wrapping the socket streams.
	 * @throws NoSuchAlgorithmException           If the requested cipher algorithm is not available.
	 * @throws InvalidKeySpecException            If the password-based key specification is invalid.
	 * @throws NoSuchPaddingException             If the requested padding scheme is not available.
	 * @throws InvalidKeyException                If the secret key is inappropriate for the cipher.
	 * @throws InvalidAlgorithmParameterException If the PBE parameter spec is invalid.
	 */
	public NodeProxy(Socket s, char[] passwd, String cipher, boolean server) throws IOException,
									NoSuchAlgorithmException,
									InvalidKeySpecException,
									NoSuchPaddingException,
									InvalidKeyException,
									InvalidAlgorithmParameterException {
		this.secure = passwd != null;
		
		if (cipher != null) this.cipherAlgorithm = cipher;
		
		this.label = s.getInetAddress().toString();
		
		this.soc = s;
		
		if (this.secure) {
			Provider sp = Security.getProvider(this.securityProvider);
			this.println("Loaded " + this.securityProvider);
			
			this.print("Constructing secret key... ");
			
			PBEParameterSpec pspec = new PBEParameterSpec(this.salt, this.count);
			PBEKeySpec kspec = new PBEKeySpec(passwd);
			SecretKeyFactory kf = SecretKeyFactory.getInstance(this.cipherAlgorithm, sp);
			SecretKey key = kf.generateSecret(kspec);
			this.println("Done", false, false);
			
			this.print("Initializing output cipher... ");
			this.outc = Cipher.getInstance(this.cipherAlgorithm);
			this.outc.init(Cipher.ENCRYPT_MODE, key, pspec);
			this.println("Done", false, false);
			
			this.print("Initializing input cipher... ");
			this.inc = Cipher.getInstance(this.cipherAlgorithm);
			this.inc.init(Cipher.DECRYPT_MODE, key, pspec);
			if (NodeProxy.sInc == null) NodeProxy.sInc = this.inc;
			this.println("Done", false, false);
		}
		
		this.print("Getting output stream... ", true);
		this.out = new ObjectOutputStream(s.getOutputStream());
		this.println("Done", true, false);
		
		this.print("Getting input stream... ", true);
		this.in = new ObjectInputStream(s.getInputStream());
		this.println("Done", true, false);
		
		if (Message.verbose) {
			// TODO  Add socket dump file to documentation
			
			String fn = s.getInetAddress().getHostName() + ".out";
			
			try {
				this.fout = new ObjectOutputStream(new FileOutputStream(fn));
			} catch (FileNotFoundException fnf) {
				this.println("Unable to open socket dump file " + fnf.getMessage());
			}
		}
		
		this.connected = true;
		
		this.obj = Collections.synchronizedList(new ArrayList());
		this.listeners = new ArrayList();
		this.queue = new ArrayList();
		
		this.checkedMsgIn = System.currentTimeMillis();
		
		Client c = Client.getCurrentClient();
		ThreadGroup g = null;
		if (c != null) g = c.getServer().getThreadGroup();
		Thread t = new Thread(g, this);
		t.setName("NodeProxy for " + this.label);
		t.setPriority(Server.MODERATE_PRIORITY);
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Sends an object to the node with the given ID, honouring the current queue
	 * state. Equivalent to {@code writeObject(o, id, this.useQueue)}.
	 *
	 * @param o   The {@link Message} or {@link io.almostrealism.db.Query} to send.
	 * @param id  The receiver node ID to embed in the message header.
	 * @throws IOException  If a socket error occurs and the queue is not active.
	 * @see Proxy#writeObject(Object, int)
	 */
	@Override
	public void writeObject(Object o, int id) throws IOException {
		this.writeObject(o, id, this.useQueue);
	}
	
	/**
	 * Writes the given object to the output stream, optionally queuing it for deferred delivery.
	 *
	 * @see Proxy#writeObject(java.lang.Object, int)
	 * @throws IllegalArgumentException  If the object is not an instance of Message or Query.
	 * @throws IOException  If an IOException occurs while writing to the output stream.
	 */
	public void writeObject(Object o, int id, boolean useQueue) throws IOException {
		if (useQueue) {
			this.queue.add(new NodeProxyStoredObject(o, id));
			return;
		}
		
		if (!this.isConnected())
			throw new IOException("Node Proxy (" + this + ") not connected.");
		
		Externalizable ext = null;
		byte[] b = null;
		String head = null;
		
		if (o instanceof Message) {
			Message m = (Message) o;
			m.setReceiver(id);
			
			if (m.getType() == Message.ResourceRequest) {
				Message x = (Message) this.nextMessage(Message.ResourceUri, null);
				String xd = null;
				if (x != null) xd = x.getData();
				
				if (xd != null && xd.endsWith(m.getData())) {
					this.println("Resource URI matching request was found in queue.");
					this.storeMessage(x);
					return;
				}
			}
			
			head = NodeProxy.msgHeader;
			
			if (this.secure) {
				b = m.getBytes();
			} else {
				ext = m;
			}
		} else if (o instanceof Query) {
			Query q = (Query) o;
			
			head = NodeProxy.queryHeader;
			
			if (this.secure) {
				b = q.getBytes();
			} else {
				ext = q;
			}
		} else {
			throw new IllegalArgumentException("Object is not proper type.");
		}
		
		try {
			if (Message.sverbose)
				this.print("Locking out stream...", true);
			
			synchronized (this.out) {
				if (Message.sverbose)
					this.println("Synchronized out.", true);
				
				this.out.writeUTF(head);
				
				if (this.secure) {
					this.writeSecure(b);
				} else {
					ext.writeExternal(this.out);
				}
				
				if (Message.sverbose)
					this.println("Unlocking out stream...", true);
				
				this.out.notify();
			}
		} catch (SocketException se) {
			this.fireDisconnect();
			this.resets = 4;
		} catch (IOException ioe) {
			throw ioe;
		}
	}
	
	/**
	 * Measures the time in milliseconds to send a message of a specified size
	 * and recieve the same message echoed back.
	 * 
	 * @param size  Number of characters (byte pairs) to send in message.
	 * @param timeout  Maximum time to wait for a response in milliseconds.
	 * @return  The time it takes to do the communication in milliseconds,
	 *          -1 if timeout/error occurs.
	 * @throws IOException  If an IO error occurs reading/writing data.
	 */
	public long ping(int size, int timeout) throws IOException {
		Message m = new Message(Message.Ping, -1, this);
		
		char[] c = new char[size];
		
		for (int i = 0; i < c.length; i++) {
			if (Math.random() > 0.5)
				c[i] = '1';
			else
				c[i] = '0';
		}
		
		String s = new String(c);
		m.setString(s);
		
		long start = System.currentTimeMillis();
		
		this.writeObject(m, -1);
		Message r = (Message) this.waitForMessage(Message.Ping, s, timeout);
		
		long end = System.currentTimeMillis();
		
		this.println("Ping (" + start + ", " + end + ") Response  = " + r, true);
		
		if (r == null) {
			return -1;
		} else {
			return end - start;
		}
	}
	
	/**
	 * Pings a host that is connected to this server.
	 * 
	 * @param size  Size of packet in characters (pairs of bytes).
	 * @param timeout  Max time to wait for a response, in milliseconds.
	 * @param n  The number of pings to perform.
	 * @return  {minimum, maximum, average, deviation, errors}.
	 *          The last value will be non zero if an error occured.
	 */
	public double[] ping(int size, int timeout, int n) {
		double min = 0.0;
		double max = 0.0;
		double avg = 0.0;
		double div = 0.0;
		double error = 0.0;
		int q = 0;
		
		for (int j = 0; j < 15; j++) {
			double p = -1;
			
			try {
				p = this.ping(size, timeout);
			} catch (IOException e) {
				this.println("IO error during ping");
				error = 1.0;
			}
			
			if (p > 0) {
				avg = avg + p;
				q++;
				
				if (p > max || max <= 0.0) max = p;
				if (p < min || min <= 0.0) min = p;
			}
		}
		
		avg = avg / q;
		
		return new double[] {min, max, avg, div, error};
	}
	
	/**
	 * Encrypts the given byte array and writes it to the output stream as a
	 * {@link NodeProxyByteWrapper}. If a file-dump stream ({@link #fout}) is open, the
	 * same encrypted wrapper is also mirrored there.
	 *
	 * @param b  The plaintext byte array to encrypt and send.
	 * @throws IOException  If an I/O error occurs while writing to the stream.
	 */
	protected void writeSecure(byte[] b) throws IOException {
		NodeProxyByteWrapper bw = new NodeProxyByteWrapper(this.outc, b);
		bw.writeExternal(this.out);
		this.out.flush();
		
		if (this.fout != null) {
			this.println("Logging msg data to file...", true);
			bw.writeExternal(this.fout);
			this.fout.flush();
		}
		
		if (Message.dverbose)
			this.println("Wrote " + bw, true);
	}
	
	/**
	 * @see Proxy#nextObject(int)
	 */
	@Override
	public Object nextObject(int id) {
		NodeProxyStoredObject[] o;
		
		synchronized (this.obj) {
			if (this.obj.size() <= 0) return null;
			o = (NodeProxyStoredObject[])this.obj.toArray(new NodeProxyStoredObject[0]);
		}
		
		i: for (int i = o.length - 1; i >= 0; i--) {
			if (o[i] == null) continue i;
			
			if (o[i].getId() == id) {
				boolean r = this.obj.remove(o[i]);
				
				if (r)
					this.println("Removing stored object -- " + o[i].getObject(), true);
				
				return o[i].getObject();
			}
		}
		
		return null;
	}
	
	/**
	 * Finds the next Message object of the specified type that has been received.
	 * 
	 * @param type  The integer type code for the type of message to be returned.
	 * @param data  The data contained in the message to be returned.
	 *              (If null, data will not be checked).
	 * @return  The next Message object to be handled, or null if one is not present.
	 */
	public Object nextMessage(int type, String data) {
		NodeProxyStoredObject[] o;
		
		synchronized (this.obj) {
			if (this.obj.size() <= 0) return null;
			o = (NodeProxyStoredObject[])this.obj.toArray(new NodeProxyStoredObject[0]);
		}
		
		i: for (int i = o.length - 1; i >= 0; i--) {
			if (o[i] == null || !(o[i].getObject() instanceof Message))
				continue i;
			
			Message m = (Message) o[i].getObject();
			
			if (m.getType() == type && (data == null || data.equals(m.getData()))) {
				boolean r = this.obj.remove(o[i]);
				
				if (r)
					this.println("Removing stored object -- " + m, true);
				
				return m;
			}
		}
	
		return null;
	}
	
	/**
	 * Waits for the next Object to be received by the specified id.
	 * 
	 * @param id  Unique id of receiver.
	 * @param timeout  Max time to wait msecs.
	 * @return  Object received, null if wait times out.
	 */
	public Object waitFor(final int id, int timeout) {
		long start = System.currentTimeMillis();

		i: for (;;) {
			try {
				Thread.sleep(NodeProxy.sleep);
			} catch (InterruptedException ie) {
				this.println(ie.toString());
			}

			Object o = this.nextObject(id);
			if (o != null) {
				this.println("waitFor: Returning " + o, true);
				return o;
			}

			if (System.currentTimeMillis() - start > timeout) break;
		}

		this.println("waitFor: timout.", true);

		return null;
	}
	
	/**
	 * Waits for the next Message object of the specified type to be recieved
	 * and returns it,
	 * 
	 * @param type  Integer type code for message to wait for.
	 * @param data  Data contained in message to wait for. (If null, data will not be checked).
	 * @param timeout  Max time to wait in msecs.
	 * @return  Object received, null if wait times out.
	 */
	public Object waitForMessage(final int type, final String data, long timeout) {
		long start = System.currentTimeMillis();

		i: for (;;) {
			try {
				Thread.sleep(NodeProxy.sleep);
			} catch (InterruptedException ie) {
				this.println(ie.toString());
			}

			Object o = this.nextMessage(type, data);
			if (o != null) {
				this.println("waitForMessage: Returning " + o, true);
				return o;
			}

			if (System.currentTimeMillis() - start > timeout) break;
		}

		this.println("waitForMessage: timeout.", true);
		
		return null;
	}
	
	/**
	 * Stores an unhandled message in the inbox list so that it can be retrieved
	 * later by a caller waiting in {@link #waitFor} or {@link #waitForMessage}.
	 *
	 * <p>Messages are inserted at position 0 (most recent first) so that
	 * {@link #nextObject} and {@link #nextMessage}, which scan from the tail,
	 * find the oldest matching entry. If the inbox exceeds {@link #maxStore}
	 * entries, the oldest entry at the end of the list is evicted.</p>
	 *
	 * @param m  The message to store in the inbox.
	 */
	protected void storeMessage(Message m) {
		this.println("Storing message -- " + m, true);
		
		synchronized (this.obj) {
			this.obj.add(0, new NodeProxyStoredObject(m, m.getReceiver()));
			if (this.obj.size() > this.maxStore) this.obj.remove(this.obj.size() - 1);
		}
	}
	
	/**
	 * Closes the input stream, output stream, and underlying socket, then marks
	 * this proxy as disconnected. After this call {@link #isConnected()} returns
	 * {@code false} and no further reads or writes are possible.
	 *
	 * <p>Any {@link IOException} thrown during stream or socket closure is caught
	 * and logged; the disconnect state is always set regardless of errors.</p>
	 */
	public void close() {
		try {
			this.in.close();
			this.out.close();
			this.soc.close();
			this.in = null;
			this.out = null;
			this.soc = null;
		} catch (IOException ioe) {
			this.println("IO exception on close - " + ioe.getMessage());
		}
		
		this.reset = false;
		this.connected = false;
	}
	
	/**
	 * Returns whether this proxy currently has an open socket connection to the
	 * remote peer.
	 *
	 * @return  {@code true} if the socket is open and the reader thread is running;
	 *          {@code false} after {@link #close()} or a disconnect event.
	 */
	public boolean isConnected() { return this.connected; }
	
	/**
	 * Adds the specified EventListener to this NodeProxy object.
	 * 
	 * @param listener  EventListener implementation to notify of events.
	 */
	public void addEventListener(NodeProxy.EventListener listener) {
		this.listeners.add(listener);
		this.println("Added listener " +
				(this.listeners.size() - 1) + " -- " + listener);
	}
	
	/**
	 * Removes the specified EventListener from this NodeProxy object.
	 */
	public void removeEventListener(NodeProxy.EventListener listener) {
		this.listeners.remove(listener);
		this.println("Removed listener " +
				(this.listeners.size() - 1) + " -- " + listener);
	}
	
	/**
	 * Returns the {@link InetAddress} of the remote host to which this proxy's
	 * socket is connected.
	 *
	 * @return  The remote {@link InetAddress}, or {@code null} if the socket has
	 *          been closed.
	 */
	public InetAddress getInetAddress() {
		if (this.soc == null)
			return null;
		else
			return this.soc.getInetAddress();
	}

	/**
	 * Returns the remote port number of this proxy's socket connection.
	 *
	 * @return  The remote port, or {@code -1} if the socket has been closed.
	 */
	public int getRemotePort() {
		if (this.soc == null)
			return -1;
		else
			return this.soc.getPort();
	}
	
	/**
	 * Returns the average number of messages received per minute since the last time
	 * this method was called.
	 *
	 * @return  The average number of messages received per minute since
	 *          the last time this method was called.
	 */
	public double getInputRate() {
		long time = System.currentTimeMillis();
		double d = (time - this.checkedMsgIn) / 60000.0;
		double t = this.currentMsgIn/ d;
		
		this.currentMsgIn = 0;
		this.checkedMsgIn = time;
		
		return t;
	}
	
	/**
	 * Records the job execution time reported by the remote peer's last status
	 * message. Used by higher-level load balancers to estimate the peer's capacity.
	 *
	 * @param t  Average job time in seconds as last reported by the remote peer.
	 */
	public void setJobTime(double t) { this.jobtime = t; }

	/**
	 * Returns the job execution time most recently reported by the remote peer.
	 *
	 * @return  Average job time in seconds, or {@code 0.0} if not yet reported.
	 */
	public double getJobTime() { return this.jobtime; }

	/**
	 * Records the activity rating of the remote peer's node group, as reported
	 * in the peer's last server-status message.
	 *
	 * @param a  Activity rating; higher values indicate heavier load.
	 */
	public void setActivityRating(double a) { this.activity = a; }

	/**
	 * Returns the activity rating of the remote peer's node group.
	 *
	 * @return  Activity rating as last reported, or {@code 0.0} if not yet set.
	 * @see Connection#getActivityRating()
	 */
	public double getActivityRating() { return this.activity; }

	/**
	 * Enables the outbound queue so that subsequent {@link #writeObject} calls
	 * buffer their objects instead of writing directly to the socket. This is
	 * called automatically at the start of disconnect and reconnect sequences
	 * to prevent writes from racing with stream teardown.
	 */
	protected void activateQueue() {
		this.useQueue = true;
	}

	/**
	 * Disables the outbound queue and flushes all buffered objects to the socket
	 * in the order they were enqueued.
	 *
	 * <p>This method is called after a successful reconnection to deliver any
	 * messages that were buffered during the disconnect window. If a write fails
	 * during the flush, the error is logged and the remaining objects are still
	 * attempted.</p>
	 */
	public void flushQueue() {
		this.useQueue = false;
		
		synchronized (this.queue) {
			Iterator itr = this.queue.iterator();
			
			while (itr.hasNext()) {
				NodeProxyStoredObject o = (NodeProxyStoredObject) itr.next();
				
				try {
					this.writeObject(o.getObject(), o.getId(), false);
				} catch (IOException ioe) {
					this.println("IO error flushing queue (" + ioe.getMessage() + ")");
				}
			}
			
			this.queue.clear();
		}
	}
	
	/**
	 * Marks this proxy as connected, notifies all registered {@link EventListener}s
	 * of the connection event, and then flushes the outbound queue.
	 *
	 * <p>Listeners are called on the thread that invokes this method. A snapshot
	 * of the listener list is taken before iteration so that listeners which remove
	 * themselves during the callback do not cause {@link java.util.ConcurrentModificationException}.</p>
	 */
	public void fireConnect() {
		this.activateQueue();
		this.connected = true;
		
		synchronized (this.listeners) {
			this.println("Notifying listeners of connection (" + this.listeners.size() + ")." );
			Iterator itr = ((List)((ArrayList)this.listeners).clone()).iterator();
			while (itr.hasNext()) ((EventListener)itr.next()).connect(this);
		}
		
		this.flushQueue();
	}
	
	/**
	 * Marks this proxy as disconnected, notifies all registered
	 * {@link EventListener}s of the disconnection, clears the listener list,
	 * and flushes any queued outbound messages.
	 *
	 * <p>A warning is logged if none of the notified listeners was an instance
	 * of {@link io.flowtree.node.NodeGroup}, since that typically indicates the
	 * owning node group was not properly registered.</p>
	 */
	protected void fireDisconnect() {
		this.activateQueue();
		this.connected = false;
		
		synchronized (this.listeners) {
			this.println("Notifying listeners of disconnection (" + this.listeners.size() + ")." );
			boolean g = false;
			Iterator itr = ((List)((ArrayList)this.listeners).clone()).iterator();
			
			while (itr.hasNext()) {
				EventListener l = (EventListener) itr.next();
				l.disconnect(this);
				
				if (l instanceof NodeGroup) g = true;
			}
			
			this.println("Removing remaining listeners (" + this.listeners.size() + ")");
			this.listeners.clear();
			
			if (!g)
				this.println("Did not notify a NodeGroup.");
		}
		
		this.flushQueue();
	}
	
	/**
	 * Dispatches a received {@link Message} to all registered {@link EventListener}s
	 * and, if no listener claims it, stores it in the inbox for later retrieval.
	 *
	 * <p>Ping messages ({@link Message#Ping}) whose sender is {@code -1} are
	 * treated as incoming probes: instead of storing them, an echo reply is sent
	 * back immediately with sender ID {@code -2}.</p>
	 *
	 * <p>Every {@link #pingFreq} messages a background thread is launched to perform
	 * a multi-ping sequence and send the local server's status to the peer.</p>
	 *
	 * @param m         The received message to dispatch.
	 * @param reciever  The receiver node ID encoded in the message header.
	 */
	protected void fireReceivedMessage(Message m, int reciever) {
		this.activateQueue();
		boolean store = true;
		
		if (m.getType() == Message.Ping) this.println("Received ping.", true);
		
		Iterator itr = null;
		
		synchronized (this.listeners) {
			itr = ((List)((ArrayList)this.listeners).clone()).iterator();
		}
		
		while (itr != null && itr.hasNext()) {
			EventListener l = (EventListener)itr.next();
			
			if (l.recievedMessage(m, reciever)) {
				store = false;
				this.println("Message handled by " + l, true);
			}
		}
		
		while (itr != null && itr.hasNext()) {
			EventListener l = (EventListener) itr.next();
			
			try {
				if (l != null && l.recievedMessage(m, reciever)) {
					store = false;
					this.println("Message handled by " + l, true);
				}
			} catch (Exception e) {
				this.println("Event listener " + l + " encountered an error (" +
							e.getMessage() + ")");
			}
		}
		
		this.flushQueue();
		
		s: if (store) {
			m: if (m.getType() == Message.Ping) {
				if (m.getSender() != -1) break m;
				
				try {
					Message r = new Message(Message.Ping, -2, this);
					r.setString(m.getData());
					this.writeObject(r, m.getSender());
					break s;
				} catch (IOException ioe) {
					this.println("IO error replying to ping (" + ioe.getMessage() +")");
				}
			}
			
			this.storeMessage(m);
		}
		
		if (NodeProxy.this.lastPing % NodeProxy.this.pingFreq == 0
				&& this.pingThread == null) {
			Client cl = Client.getCurrentClient();
			ThreadGroup g = null;
			if (cl != null) g = cl.getServer().getThreadGroup();
			this.pingThread = new Thread(g, new Runnable() {
				@Override
				public void run() {
					NodeProxy.this.println("Starting routine ping...", true);
					NodeProxy.this.pingStat = NodeProxy.this.ping(500, 5000, 20);
					NodeProxy.this.println("Finished routine ping.", true);
					
					t: try {
						Client c = Client.getCurrentClient();
						if (c == null) break t;
						Server s = c.getServer();
						if (s == null) break t;
						
						NodeProxy.this.println("Sending status...", true);
						
						Message m = s.getStatusMessage();
						NodeProxy.this.writeObject(m, -1);
						
						NodeProxy.this.println("Finished sending status.", true);
					} catch (IOException e) {
						System.out.println("NodeProxy (" + this +
											"): IO error sending status (" +
											e.getMessage() + ")");
					}
					
					NodeProxy.this.lastPing = 1;
					NodeProxy.this.pingThread = null;
				}
			}, "Ping Thread");
			
			this.pingThread.start();
		} else {
			NodeProxy.this.lastPing++;
		}
	}
	
	/**
	 * Attempts to re-establish the socket connection to the remote host after a
	 * stream error. On the first call ({@code resets == 0}) a disconnect event is
	 * fired; subsequent calls increment the reset counter. After a successful
	 * reconnect, {@link #fireConnect()} is called to notify listeners and flush
	 * the queue.
	 *
	 * <p>If reconnection fails (e.g. the remote host is unreachable) the caller's
	 * reset loop will retry up to three times before giving up.</p>
	 *
	 * @throws IOException  If the new socket or its streams cannot be opened.
	 */
	protected void reset() throws IOException {
		if (resets == 0) this.fireDisconnect();
		
		resets++;
		
		this.println("Trying to reload input stream...");
		
		if (this.in != null) this.in.close();
		if (this.out != null) this.out.close();
		if (this.soc != null) this.soc.close();
		
		this.soc = new Socket(this.soc.getInetAddress().getHostAddress(), this.soc.getPort());
		
		this.in = new ObjectInputStream(this.soc.getInputStream());
		this.out = new ObjectOutputStream(this.soc.getOutputStream());
		
		resets = 0;
		reset = false;
		
		this.nullCount = 0;
		
		this.fireConnect();
	}
	
	/**
	 * @return  True if the specified Object is an instance of {@link NodeProxy} that is connected
	 *          to the same inet address and port as this one, false otherwise.
	 */
	@Override
	public boolean equals(Object o) {
		if (o instanceof NodeProxy) {
			NodeProxy p = (NodeProxy) o;
			InetAddress adp = p.getInetAddress();
			InetAddress adt = this.getInetAddress();
			
			if (adp == null || adt == null)
				return false;
			else
				return adp.getHostAddress().equals(adt.getHostAddress()) && p.getRemotePort() == this.getRemotePort();
		} else {
			return false;
		}
	}

	/**
	 * Returns a hash code consistent with {@link #equals}: two proxies connected
	 * to the same host and port will produce the same hash.
	 *
	 * @return  The sum of the remote {@link InetAddress} hash and the remote port number.
	 */
	@Override
	public int hashCode() { return this.getInetAddress().hashCode() + this.getRemotePort(); }
	
	/**
	 * Processes {@link Message}s and {@link Query}s from the peer.
	 */
	@Override
	public void run() {
		int count = 0;

		boolean h = false;
		String head = null;
		Externalizable ext = null;
		
		loop: while (resets < 3) {
			try {
				count = (count + 1) % 1000;
				Thread.sleep(NodeProxy.sleep);
				
				if (this.nullCount > this.timeout) {
					println("Attempting to reconfirm connection...");
					Message m = new Message(Message.ConnectionConfirmation, -1, this);
					
					if (m.send(-1) == null) {
						println("Connection timeout.");
						this.reset = true;
					} else {
						println("ConnectionConfirmation sent.");
					}
				}
				
				if (reset)
					this.reset();
				else if (!this.isConnected())
					break;
				
				i: if (this.in.available() > 0) {
					h = true;
					head = this.in.readUTF();
					h = false;
					
					if (Message.dverbose)
						this.println("Received header " + head, true);
					
					if (head.equals(NodeProxy.msgHeader)) {
						ext = new Message(this);
					} else if (head.equals(NodeProxy.queryHeader)) {
						ext = new Query();
					} else {
						this.nullCount++;
						this.println("Received unknown header " + head);
						break i;
					}
					
					if (this.secure) {
						NodeProxyByteWrapper bw = new NodeProxyByteWrapper(this.inc);
						bw.readExternal(this.in);
						
						if (Message.dverbose)
							this.println("Received " + bw, true);
						
						if (ext instanceof Message) {
							((Message) ext).setBytes(bw.getBytes());
						} else if (ext instanceof Query) {
							((Query) ext).setBytes(bw.getBytes());
						}
					} else {
						ext.readExternal(in);
					}
					
					if (ext instanceof Message) {
						Message m = (Message) ext;
						
						this.currentMsgIn++;
						this.nullCount = 0;
						this.fireReceivedMessage(m, m.getReceiver());
					} else if (ext instanceof Query) {
						this.nullCount = 0;
						Server server = Client.getCurrentClient().getServer(); // TODO  Remove dependence on Client ?
						Message m = server.executeQuery((Query) ext, this, NodeProxy.queryTimeout);
						this.writeObject(m, -2);
					}
				} else {
					this.nullCount++;
				}
			} catch (ArrayIndexOutOfBoundsException oob) {
				this.println("Strange exception... " + oob);
				oob.printStackTrace(System.out);
			} catch (InterruptedException ie) {
				this.println("Thread sleep interrupted");
			} catch (EOFException eof) {
				this.println("EOF ERROR");
				reset = true;
			} catch (SocketException se) {
				this.println(se.getMessage());
				// reset = true;
				this.fireDisconnect();
				break;
			} catch (StreamCorruptedException sce) {
				this.println("Stream Corrupted (" + sce.getMessage() + ")");
				reset = true;
			} catch (OptionalDataException ode) {
				this.println("Option Data Exception (eof = " +
							ode.eof + " len = " + ode.length + ")");
				ode.printStackTrace(System.out);
			} catch (UTFDataFormatException utfe) {
				if (h)
					this.println("Error reading message header.");
				else
					this.println("Error parsing UTF data.");
			} catch (IOException ioe) {
				this.println(ioe.toString());
			} catch (ClassCastException cce) {
				this.println(cce.getMessage());
			} catch (ClassNotFoundException cnf) {
				this.println(cnf.toString());
			} catch (Exception e) {
				System.err.println("NodeProxy (" + this + "): Unexpected error in reader loop: " + e);
			}
		}
		
		try {
			if (this.in != null) this.in.close();
			if (this.out != null) this.out.close();
			if (this.soc != null) this.soc.close();
		} catch (Exception e) { }
			
		System.out.println("NodeProxy: Thread ended");
	}

	/**
	 * Prints a diagnostic message to standard output, prefixed with this proxy's
	 * identity, without a trailing newline.
	 *
	 * @param msg  The message to print.
	 */
	public void print(String msg) {
		System.out.print("NodeProxy (" + this + "): " + msg);
	}

	/**
	 * Conditionally prints a diagnostic message without a trailing newline.
	 * The message is suppressed when {@code verbose} is {@code true} and
	 * {@link Message#verbose} is {@code false}.
	 *
	 * @param msg      The message to print.
	 * @param verbose  When {@code true}, only print if global verbose logging is enabled.
	 */
	public void print(String msg, boolean verbose) {
		if (!verbose || Message.verbose)
			System.out.print("NodeProxy (" + this + "): " + msg);
	}

	/**
	 * Prints a diagnostic message to standard output, prefixed with this proxy's
	 * identity, with a trailing newline.
	 *
	 * @param msg  The message to print.
	 */
	public void println(String msg) {
		System.out.println("NodeProxy (" + this + "): " + msg);
	}

	/**
	 * Conditionally prints a diagnostic message with a trailing newline.
	 * The message is suppressed when {@code verbose} is {@code true} and
	 * {@link Message#verbose} is {@code false}.
	 *
	 * @param msg      The message to print.
	 * @param verbose  When {@code true}, only print if global verbose logging is enabled.
	 */
	public void println(String msg, boolean verbose) {
		if (!verbose || Message.verbose)
			System.out.println("NodeProxy (" + this + "): " + msg);
	}

	/**
	 * Conditionally prints a message with a trailing newline, with optional proxy
	 * identity prefix.
	 *
	 * <p>When {@code ident} is {@code true}, delegates to {@link #println(String, boolean)}
	 * so the proxy identity prefix is included. When {@code ident} is {@code false},
	 * prints the raw message without prefix if {@code verbose} is satisfied.</p>
	 *
	 * @param msg      The message to print.
	 * @param verbose  When {@code true}, only print if global verbose logging is enabled.
	 * @param ident    When {@code true}, prepend the proxy identity prefix.
	 */
	public void println(String msg, boolean verbose, boolean ident) {
		if (ident) {
			this.println(msg, verbose);
		} else if (!verbose || Message.verbose) {
			System.out.println(msg);
		}
	}
	
	/**
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() { return this.toString(false); }
	
	/**
	 * Returns a human-readable description of this proxy, optionally appending
	 * the most recent ping statistics.
	 *
	 * @param showStat  When {@code true} and ping statistics are available,
	 *                  appends {@code [min/max/avg]} latency data; appends
	 *                  {@code [ERROR]} if the last ping sequence reported an error.
	 * @return  A string identifying the remote host, with optional statistics.
	 */
	public String toString(boolean showStat) {
		String s = this.label;
		
		f: if (showStat) {
			if (this.pingStat == null) {
				break f;
			} else if (this.pingStat[4] != 0.0) {
				s = s + " [ERROR]";
			} else {
				s = s + " [" + this.pingStat[0] + "/" +
							this.pingStat[1] + "/"
							+ this.pingStat[2] + "]";
			}
		}
		
		return s;
	}
}
