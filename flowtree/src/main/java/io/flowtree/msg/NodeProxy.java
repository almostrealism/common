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

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
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
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
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
 * A NodeProxy object uses a Socket to enable communication between remote nodes.
 * 
 * @author Mike Murray
 */
public class NodeProxy implements Proxy, Runnable {
	public static int sleep = 100;
	public static long queryTimeout = 900000;
	public static String serviceName = "RINGS";
	public static final String msgHeader = "msg";
	public static final String queryHeader = "query";
	private final String securityProvider = "SunJCE";
	private String cipherAlgorithm = "PBEWithMD5AndDES";
	public static final byte[] defaultSalt = {
			(byte)0xc7, (byte)0x73, (byte)0x21, (byte)0x8c,
			(byte)0x7e, (byte)0xc8, (byte)0xee, (byte)0x99
	};
	private final byte[] salt = {
			(byte)0xc7, (byte)0x73, (byte)0x21, (byte)0x8c,
			(byte)0x7e, (byte)0xc8, (byte)0xee, (byte)0x99
	};
	public static final int defaultCount = 20;
	private final int count = 20;
	
	private static Cipher sInc;
	
	private int lastPing = 1;
	private final int pingFreq = 40;
	private double[] pingStat;
	private Thread pingThread;
	
	private class StoredObject {
		Object o;
		int id;
		
		public StoredObject(Object o, int id) {
			this.o = o;
			this.id = id;
		}
		
		public Object getObject() { return this.o; }
		public int getId() { return this.id; }
		
		public String toString() { return "StoredObject: " + this.getId() + " " + this.getObject(); }
	}
	
	private static class ByteWrapper implements Externalizable {
		private static final long serialVersionUID = 6512456536452755866L;
		private final transient Cipher c;
		private byte[] b;
		
		public ByteWrapper() { this(NodeProxy.sInc); }
		public ByteWrapper(Cipher c) { this.c = c; }
		public ByteWrapper(Cipher c, byte[] b) { this.c = c; this.b = b; }
		public void setBytes(byte[] b) { this.b = b; }
		public byte[] getBytes() { return this.b; }
		
		public void writeExternal(ObjectOutput out) throws IOException {
			try {
				int div = 8 - (b.length % 8);
				
				if (div < 8) {
					byte[] temp = this.b;
					int l = temp.length;
					this.b = new byte[l + div];
					System.arraycopy(temp, 0, this.b, 0, l);
					for (int i = l; i < this.b.length; i++) this.b[i] = -1;
					
					if (Message.dverbose)
						System.out.println("NodeProxy.ByteWrapper: Padded message by " + div);
				}
				
				byte[] b = this.c.doFinal(this.b);
				out.writeInt(b.length);
				out.write(b);
			} catch (IllegalBlockSizeException e) {
				System.out.println("NodeProxy.ByteWrapper: Illegal block size (" + e.getMessage() + ")");
			} catch (BadPaddingException e) {
				System.out.println("NodeProxy.ByteWrapper: Bad padding (" + e.getMessage() + ")");
			}
		}
		
		public void readExternal(ObjectInput in) throws IOException {
			try {
				if (this.b == null) this.b = new byte[in.readInt()];
				in.readFully(this.b);
				this.b = this.c.doFinal(this.b);
				
				int i;
				i: for (i = 0; i < 8; i++) {
					if (this.b[this.b.length - i - 1] != -1) break;
				}
				
				if (i > 0) {
					byte[] temp = new byte[this.b.length - i];
					System.arraycopy(this.b, 0, temp, 0, temp.length);
					this.b = temp;
					
					if (Message.dverbose)
						System.out.println("NodeProxy.ByteWrapper: Truncated message by " + i);
				}
			} catch (IllegalBlockSizeException e) {
				System.out.println("NodeProxy.ByteWrapper: Illegal block size (" + e.getMessage() + ")");
			} catch (BadPaddingException e) {
				System.out.println("NodeProxy.ByteWrapper: Bad padding (" + e.getMessage() + ")");
			}
		}
	}
	
	public interface EventListener {
		void connect(NodeProxy p);
		int disconnect(NodeProxy p);
		boolean recievedMessage(Message m, int reciever);
	}
	
	private final int timeout = 20000;
	private final int maxStore = 100;
	
	private final String label;
	
	private Socket soc;
	private ObjectInputStream in;
	private ObjectOutputStream out, fout;
	private Cipher inc, outc;
	
	private boolean secure = true, connected, reset, useQueue = true;
	private int mwait, owait;
	private int resets, nullCount;
	
	private int totalMsgIn, currentMsgIn;
	private long checkedMsgIn;
	
	private final List obj;
	private final List listeners;
	private final List queue;
	
	private double jobtime, activity;
	
	/**
	 * Constructs a new NodeProxy object using the specified Socket.
	 * This starts a thread which will wait for data from the Socket.
	 * 
	 * @param s  Socket to use.
	 * @throws IOException  If an IOException occurs while getting IO streams.
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidAlgorithmParameterException 
	 * @throws NoSuchPaddingException 
	 * @throws InvalidKeySpecException 
	 * @throws InvalidKeyException 
	 */
	public NodeProxy(Socket s) throws IOException,
									NoSuchAlgorithmException,
									InvalidKeyException,
									InvalidKeySpecException,
									NoSuchPaddingException,
									InvalidAlgorithmParameterException {
		this(s, null, null, false);
	}
	
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

	@Override
	public void writeObject(Object o, int id) throws IOException {
		this.writeObject(o, id, this.useQueue);
	}
	
	/**
	 * @see Proxy#writeObject(java.lang.Object, int)
	 * @throws IllegalArgumentException  If the object is not an instance of Message or Query.
	 * @throws IOException  If an IOException occurs while writing to the output stream.
	 */
	public void writeObject(Object o, int id, boolean useQueue) throws IOException {
		if (useQueue) {
			this.queue.add(new StoredObject(o, id));
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
	
	protected void writeSecure(byte[] b) throws IOException {
		ByteWrapper bw = new ByteWrapper(this.outc, b);
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
		StoredObject[] o;
		
		synchronized (this.obj) {
			if (this.obj.size() <= 0) return null;
			o = (StoredObject[])this.obj.toArray(new StoredObject[0]);
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
		StoredObject[] o;
		
		synchronized (this.obj) {
			if (this.obj.size() <= 0) return null;
			o = (StoredObject[])this.obj.toArray(new StoredObject[0]);
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
		this.owait++;
		long start = System.currentTimeMillis();
		
		i: for (int i = 0; ; i++) {
			try {
				Thread.sleep(NodeProxy.sleep);
			} catch (InterruptedException ie) {
				this.println(ie.toString());
			}
			
			Object o = this.nextObject(id);
			if (o != null) {
				this.owait--;
				this.println("waitFor: Returning " + o, true);
				return o;
			}
			
			if (System.currentTimeMillis() - start > timeout) break;
		}
		
		this.owait--;
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
		this.mwait++;
		long start = System.currentTimeMillis();
		
		i: for (int i = 0; ; i++) {
			try {
				Thread.sleep(NodeProxy.sleep);
			} catch (InterruptedException ie) {
				this.println(ie.toString());
			}
			
			Object o = this.nextMessage(type, data);
			if (o != null) {
				this.mwait--;
				this.println("waitForMessage: Returning " + o, true);
				return o;
			}
			
			if (System.currentTimeMillis() - start > timeout) break;
		}
		
		this.mwait--;
		this.println("waitForMessage: timeout.", true);
		
		return null;
	}
	
	protected void storeMessage(Message m) {
		this.println("Storing message -- " + m, this.mwait > 0);
		
		synchronized (this.obj) {
			this.obj.add(0, new StoredObject(m, m.getReceiver()));
			if (this.obj.size() > this.maxStore) this.obj.remove(this.obj.size() - 1);
		}
	}
	
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
	
	public InetAddress getInetAddress() {
		if (this.soc == null)
			return null;
		else
			return this.soc.getInetAddress();
	}
	
	public int getRemotePort() {
		if (this.soc == null)
			return -1;
		else
			return this.soc.getPort();
	}
	
	/**
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
	
	public void setJobTime(double t) { this.jobtime = t; }
	public double getJobTime() { return this.jobtime; }
	
	public void setActivityRating(double a) { this.activity = a; }
	public double getActivityRating() { return this.activity; }
	
	protected void activateQueue() {
		this.useQueue = true;
	}
	
	public void flushQueue() {
		this.useQueue = false;
		
		synchronized (this.queue) {
			Iterator itr = this.queue.iterator();
			
			while (itr.hasNext()) {
				StoredObject o = (StoredObject) itr.next();
				
				try {
					this.writeObject(o.getObject(), o.getId(), false);
				} catch (IOException ioe) {
					this.println("IO error flushing queue (" + ioe.getMessage() + ")");
				}
			}
			
			this.queue.clear();
		}
	}
	
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
						ByteWrapper bw = new ByteWrapper(this.inc);
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
						
						this.totalMsgIn++;
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
				e.printStackTrace();
			}
		}
		
		try {
			if (this.in != null) this.in.close();
			if (this.out != null) this.out.close();
			if (this.soc != null) this.soc.close();
		} catch (Exception e) { }
			
		System.out.println("NodeProxy: Thread ended");
	}

	public void print(String msg) {
		System.out.print("NodeProxy (" + this + "): " + msg);
	}
	
	public void print(String msg, boolean verbose) {
		if (!verbose || Message.verbose)
			System.out.print("NodeProxy (" + this + "): " + msg);
	}
	
	public void println(String msg) {
		System.out.println("NodeProxy (" + this + "): " + msg);
	}
	
	public void println(String msg, boolean verbose) {
		if (!verbose || Message.verbose)
			System.out.println("NodeProxy (" + this + "): " + msg);
	}
	
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
