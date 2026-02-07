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

package io.flowtree.fs;

import io.almostrealism.db.DatabaseConnection;
import io.almostrealism.db.Query;
import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import org.apache.commons.lang3.NotImplementedException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A {@link DistributedResource} represents a file in the distributed content repository.
 * The content repository is designed to break up an array of bytes into uniform size
 * chunks of data that can be moved around and mirrored to store the contents of some
 * resource across many running clients. A {@link DistributedResource} may have some
 * number of these chunks stored in an internal (in memory) cache. When the {@link #getData()}
 * method is called, as many chunks of data as possible are loaded from the cache. Other
 * data is then requested from a ResourceDistributionTask, if one is currently running on the
 * currently running Server. The ResourceDistributionTask stores chunks of data in a
 * hard disk cache. If more chunks of data are required for the resource to be completely
 * loaded and returned, the DistributedResource object will request this data from peers
 * that are connected to the currently running Server. These peers should respond with
 * a URI for a resource server. When the resource server is contacted, it is informed of
 * which chunks of data need to be loaded and the process of loading a DistributedResource
 * begins again on the peer machine (a call to the getData method is made). This process
 * is identical to the one just presented here. This process is repeated until the resource
 * is fully loaded.
 * 
 * @author  Michael Murray
 */
public class DistributedResource implements Resource {
	public static boolean verbose = true;  // server.resource.verbose
	public static boolean ioVerbose = true;  // server.resource.io.verbose
	
	private final int chunkSize = 500000;
	private final boolean commitAtEnd = false;
	
	private String uri, exclude;
	private long tot;
	private int size;
	private boolean[] loaded;
	private long[] toa;
	private byte[][] data;
	
	private Permissions permissions;
	
	protected DistributedResource() {
		this.size = -1;
		this.loaded = null;
		this.toa = null;
		this.data = null;
		this.permissions = new Permissions();
	}
	
	protected DistributedResource(String uri) {
		this.uri = processUri(uri);
		this.size = -1;
		this.loaded = null;
		this.toa = null;
		this.data = null;
		this.permissions = new Permissions();
	}
	
	protected DistributedResource(String uri, Permissions permissions) {
		this.uri = processUri(uri);
		this.size = -1;
		this.loaded = null;
		this.toa = null;
		this.data = null;
		this.permissions = permissions;
	}
	
	protected DistributedResource(String uri, Permissions permissions, long size) {
		this.uri = processUri(uri);
		
		this.tot = size;
		this.size = (int) (size / this.chunkSize);
		this.loaded = new boolean[this.size];
		this.toa = new long[this.size];
		this.data = new byte[this.size][0];
		this.permissions = permissions;
	}
	
	protected DistributedResource(Resource res) {
		this.uri = processUri(res.getURI());
		
		Object o = res.getData();
		if (!(o instanceof byte[][]))
			throw new IllegalArgumentException("Resource data is not byte[][].");
		
		this.data = (byte[][]) res.getData();
		
		for (int i = 0; i < this.data.length - 1; i++) {
			if (this.data[i].length != this.chunkSize)
				throw new IllegalArgumentException("Improper dimensions for resource data.");
		}
		
		this.size = this.data.length;
		this.tot = (long) this.chunkSize * (this.size - 1) +
					this.data[this.data.length - 1].length;
		this.loaded = new boolean[this.size];
		for (int i = 0; i < this.loaded.length; i++) this.loaded[i] = true;
		this.toa = new long[this.size];
		this.toa[0] = System.currentTimeMillis();
		for (int i = 1; i < this.toa.length; i++) this.toa[i] = this.toa[i - 1] + 2;
	}

	private String processUri(String uri) {
		if (uri.startsWith("/")) {
			uri = "resource://" + uri;
		}

		return uri;
	}
	
	protected boolean isLoaded() {
		if (this.data == null) return false;
		if (this.loaded == null) return false;
		for (int i = 0; i < this.loaded.length; i++)
			if (!this.loaded[i]) return false;
		
		return true;
	}
	
	protected int getSize() { return this.size; }
	
	/**
	 * Returns the size of this resource in bytes. 
	 * If the DistributedResource class is extended by a class that wishes to provide
	 * different data to a client than the data stored in the database such as dynamic
	 * content, the sub class must override this method (eg. ConcatenatedResource).
	 * 
	 * @return  The size of this resource in bytes.
	 */
	public long getTotalBytes() {
		if (verbose) {
			String loaded;
			
			if (this.isLoaded())
				loaded = " (loaded).";
			else
				loaded = " (not loaded).";
			
			System.out.println("DistributedResource (" + this.uri +
								"): Total bytes = " + this.tot +
								loaded);
		}
		
		return this.tot;
	}
	
	private void setData(int index, byte[] d) {
		if (verbose) {
			String msg = "DistributedResource (" + this.uri +
								"): Set data[" + index + "] to ";
			if (d == null)
				System.out.println(msg + "null.");
			else
				System.out.println(msg + (d.length / 1000.0) +
									" kilobyte chunk.");
		}
		
		this.data[index] = d;
		this.loaded[index] = this.data != null;
	}
	
	public Object getData() { return this.data; }
	
	protected byte[] getData(int index, boolean load) {
		return this.getData(index, load, load, true);
	}
	
	private byte[] getData(int index, boolean load,
									boolean fromRes, boolean store) {
		if (store && this.data == null) {
			this.loadFromLocalDB();
			load = false;
		}
		
		int l = 0;
		if (this.data != null) l = this.data.length;
		
		if (index < l && this.loaded[index])
			return this.data[index];
		
		if (load && (!store || index < l)) {
			byte[] b = this.loadFromLocalDB(index);
			
			if (b != null) {
				if (store) {
					this.data[index] = b;
					this.loaded[index] = true;
				}
				
				return b;
			}
		}
		
		r: if (fromRes) {
			Thread t = new Thread () {
				public void run() {
					try {
						DistributedResource.this.loadFromResourceServer();
					} catch (IOException ioe) {
						System.out.println("DistributedResource (" + DistributedResource.this.uri +
											"): IO error while loading from resource server -- " +
											ioe.getMessage());
					}
				}
			};
			
			System.out.println("DistributedResource: Starting thread " + t);
			t.start();
			System.out.println("Started");
			
			while (this.loaded == null || index >= this.loaded.length || !this.loaded[index]) {
				if (!t.isAlive()) break r;
				try { Thread.sleep(1000); } catch (InterruptedException e) { }
//				System.out.println("Slept 1000");
			}
			
			return this.data[index];
		}
		
		if (index >= l) {
			System.out.println("DistributedResource: Data is " + l +
								" chunks. Chunk " + index + " does not exist.");
			return null;
		} else if (index >= this.loaded.length) {
			System.out.println("DistributedResource: Loaded list knows " +
								this.loaded.length + " chunks. Data has " +
								this.data.length + " chunks. Chunk " + index +
								" was requested.");
			return null;
		}
		
		if (this.loaded[index])
			return this.data[index];
		else
			return null;
	}
	
	/**
	 * Calls the clearCache method and sets the data buffer for this DistributedResource
	 * to null (eliminating the cache).
	 */
	protected synchronized void clear() {
		this.clearCache();
		this.data = null;
	}
	
	/**
	 * Subtracts the size of the data buffer for this DistributedResource from
	 * the indicated size of the total cache for the current ResourceDistributionTask.
	 * This is done by calling the subtractCache method of ResourceDistributionTask.
	 * 
	 * @see ResourceDistributionTask#subtractCache(long)
	 */
	protected void clearCache() {
		if (this.tot > 0)
			ResourceDistributionTask.getCurrentTask().subtractCache(this.tot);
		
		if (verbose)
			System.out.println("DistributedResource (" + this.uri +
								"): Cleared cache.");
	}
	
	private void checkCache() {
		ResourceDistributionTask task = ResourceDistributionTask.getCurrentTask();
		
		if (this.tot > 0) {
			task.addCache(this.tot);
			task.checkFull();
		}
	}
	
	protected void setExcludeHost(String host) { this.exclude = host; }

	public synchronized void load(byte[] data, long offset, int len) {
		throw new NotImplementedException("load");
	}

	/**
	 * Reads the data from the specified InputStream and stores the data in
	 * the data buffer for this DistributedResource.
	 * 
	 * @param in  InputStream to read data from.
	 * @throws IOException  If an IO error occurs while reading.
	 */
	public synchronized void loadFromStream(InputStream in) throws IOException {
		this.clearCache();
		
		if (DistributedResource.ioVerbose) {
			System.out.println("DistributedResource (" + this.uri +
								"): Loading from stream...");
			if (this.data != null)
				System.out.println("DistributedResource (" + this.uri +
									"): Current data will be flushed.");
		}
		
		int t = Integer.MAX_VALUE;
		
		this.data = null;
		List<byte[]> l = null;
		
		if (this.data == null) {
			l = new ArrayList<byte[]>();
		} else {
			t = this.loaded.length;
		}
		
		int r = -1;
		byte[] latest = new byte[0];
		
		long lastToa = 0;
		
		i: for (int i = 0; i < t; i++) {
			if (l == null)
				this.data[i] = new byte[this.chunkSize];
			else
				latest = new byte[this.chunkSize];
			
			long toa = System.currentTimeMillis();
			if (i > 0 && toa == lastToa) toa = lastToa + 1;
			lastToa = toa;
			
			for (int j = 0; j < this.chunkSize; j++) {
				r = in.read();
				
				if (DistributedResource.ioVerbose && j == 0 && i == 0)
					System.out.println("DistributedResource (" + this.uri +
										"): Read first byte from in stream.");
				
				if (r < 0) {
					if (j == 0 && i == 0) return;
					
					byte[] d;
					
					if (l == null) {
						d = this.data[i];
						this.data[i] = new byte[j];
						System.arraycopy(d, 0, this.data[i], 0, j);
					} else {
						d = latest;
						latest = new byte[j];
						System.arraycopy(d, 0, latest, 0, j);
						
						if (DistributedResource.ioVerbose)
							System.out.println("DistributedResource (" + this.uri +
												"): Loaded " + (latest.length / 1000.0) +
												" kilobytes from stream.");
						l.add(latest);
					}
					
					if (!this.commitAtEnd) this.commitToLocalDB(toa, latest, i);
					break i;
				}
				
				if (l == null)
					this.data[i][j] = (byte) r;
				else
					latest[j] = (byte) r;
			}
			
			if (l == null) {
				this.loaded[i] = true;
				this.toa[i] = toa;
			} else {
				if (DistributedResource.ioVerbose)
					System.out.println("DistributedResource: Loaded " +
										(latest.length / 1000.0) +
										" kilobytes from stream.");
				l.add(latest);
			}
			
			if (!this.commitAtEnd) this.commitToLocalDB(toa, latest, i);
		}
		
		if (l != null) {
			long s = l.size();
			if (s != 0)
				s = (s - 1) * chunkSize + l.get((int) s - 1).length;
			this.tot = s;
			this.size = (int) (s / this.chunkSize);
			if (s % chunkSize != 0) this.size++;
			this.loaded = new boolean[this.size];
			this.toa = new long[this.size];
			this.data = new byte[this.size][0];
			
			if (verbose) {
				System.out.println("DistributedResource: tot = " + this.tot);
				System.out.println("DistributedResource: size = " + this.size);
			}
			
			Iterator<byte[]> itr = l.iterator();
			
			byte[] b;
			
			for (int i = 0; itr.hasNext(); i++) {
				b = itr.next();
				
				this.data[i] = b;
				this.loaded[i] = true;
				this.toa[i] = System.currentTimeMillis();
			}
		}
		
		this.checkCache();
		
		if (this.commitAtEnd) this.commitToLocalDB();
	}

	@Override
	public Permissions getPermissions() { return permissions; }
	
	private void loadFromLocalDB() {
		this.clearCache();
		
		OutputServer s = OutputServer.getCurrentServer();
		if (s == null) return;
		
		if (verbose)
			System.out.println("DistributedResource (" + this.uri +
								"): Loading from local DB...");
		
		String table = s.getDatabaseConnection().getTable();
		Query q = new Query(table, DatabaseConnection.indexColumn,
							DatabaseConnection.dataColumn,
							DatabaseConnection.uriColumn + " = '" +
							this.uri + "'");
		Query tq = new Query(table, DatabaseConnection.indexColumn,
							DatabaseConnection.toaColumn,
							DatabaseConnection.uriColumn + " = '" +
							this.uri + "'");
		Hashtable h = s.getDatabaseConnection().executeQuery(q);
		Hashtable th = s.getDatabaseConnection().executeQuery(tq);
		this.data = new byte[h.size()][this.chunkSize];
		this.toa = new long[this.data.length];
		this.loaded = new boolean[this.data.length];
		this.size = h.size();
		this.tot = (long) (this.size - 1) * this.chunkSize;
		
		Iterator itr = h.entrySet().iterator();
		w: while (itr.hasNext()) {
			Map.Entry e = (Map.Entry) itr.next();
			String si = e.getKey().toString();
			int i = Integer.parseInt(si);
			
			this.data[i] = (byte[]) e.getValue();
			
			if (this.data[i] == null) {
				System.out.println("DistributedResource (" + this.uri +
									"): Query result contained null row at " + i);
				continue w;
			}
			
			this.loaded[i] = true;
			this.toa[i] = Long.parseLong((String) th.get(si));
			
			if (i == this.data.length - 1)
				this.tot += this.data[i].length;
		}
		
		this.checkCache();
	}
	
	private byte[] loadFromLocalDB(int index) {
		if (verbose)
			System.out.println("DistributedResource (" + this.uri + "): Loading chunk " +
								index + " from local DB...");
		
		OutputServer s = OutputServer.getCurrentServer();
		if (s == null) return null;
		
		String table = s.getDatabaseConnection().getTable();
		Query q = new Query(table, DatabaseConnection.uriColumn,
							DatabaseConnection.dataColumn,
							DatabaseConnection.indexColumn +
							" = " + index);
		q.setValue(0, this.uri);
		Hashtable h = s.getDatabaseConnection().executeQuery(q);
		if (h == null) return null;
		
		byte[] b = (byte[]) h.get(this.uri);
		return b;
	}
	
	private void loadFromResourceServer() throws IOException {
		System.out.println("DistributedResource (" + this.uri +
							"): Loading data from resource server...");
		
		OutputServer.getCurrentServer().getNodeServer().loadResource(this, this.exclude, true);
		this.exclude = null;
	}
	
	private void commitToLocalDB() {
		OutputServer s = OutputServer.getCurrentServer();
		
		if (s == null) {
			System.out.println("DistributedResource (" + this.uri +
								"): Unable to commit (no local DB)");
			return;
		}
		
		if (DistributedResource.verbose)
			System.out.println("DistributedResource (" + this.uri +
								"): Deleting from local DB...");
		
		s.getDatabaseConnection().deleteUri(this.uri);
		
		if (DistributedResource.verbose)
			System.out.println("DistributedResource (" + this.uri +
								"): Commiting data to local DB...");
		
		int tot = 0;
		
		i: for (int i = 0; i < this.data.length; i++) {
			if (!this.loaded[i]) continue i;
			
			s.getDatabaseConnection().storeOutput(this.toa[i], this.data[i], this.uri, i);
			tot++;
		}
		
		if (DistributedResource.verbose)
			System.out.println("DistributedResource (" + this.uri +
								"): Commited " + tot + " chunks to local DB");
	}
	
	private void commitToLocalDB(int index) {
		this.commitToLocalDB(this.toa[index], this.data[index], index);
	}
	
	private void commitToLocalDB(long toa, byte[] data, int index) {
		OutputServer s = OutputServer.getCurrentServer();
		
		if (DistributedResource.verbose)
			System.out.println("DistributedResource (" + this.uri +
					"): Deleting " + index + " from local DB...");
		
		if (!s.getDatabaseConnection().deleteIndex(uri, index)) {
			System.out.println("DistributedResource (" + this.uri +
					"): DB delete failed.");
		}
		
		if (DistributedResource.verbose)
			System.out.println("DistributedResource (" + this.uri +
								"): Commiting " + index + " to local DB...");
		
		if (!s.getDatabaseConnection().storeOutput(toa, data, this.uri, index)) {
			System.out.println("DistributedResource (" + this.uri +
					"): DB store failed.");
		}
	}
	
	/**
	 * Gets and input stream for reading the data represented by this DistributedResource.
	 * If the DistributedResource class is extended by a class that wishes to provide
	 * different data to a client than the data stored in the database such as dynamic
	 * content, the sub class must override this method (eg. ConcatenatedResource).
	 */
	@Override
	public InputStream getInputStream() {
		InputStream in = new InputStream() {
			long total;
			int chunk, index;
			byte[] b;

			@Override
			public int read() {
				if (chunk == 0 && this.index == 0 && DistributedResource.ioVerbose)
					System.out.println("DistributedResource (" +
										DistributedResource.this.uri +
										"): Begin read");
				
				if (chunk >= DistributedResource.this.size &&
						DistributedResource.this.size > 0) {
					return this.eof();
				}
				
				if (index >= DistributedResource.this.chunkSize) {
					if (DistributedResource.ioVerbose)
						System.out.println("DistributedResource (" +
											DistributedResource.this.uri +
											"): Reached end of chunk " + this.chunk);
					
					this.b = null;
					this.chunk++;
				}
				
				if (this.b == null) {
					this.b = DistributedResource.this.getData(chunk, true);
					if (this.b == null) return this.eof();
					
					if (DistributedResource.ioVerbose) {
						System.out.println("DistributedResource (" +
										DistributedResource.this.uri +
										"): Input stream buffered " +
										(this.b.length / 1000.0) + " kilobytes.");
					}
					
					this.index = 0;
				}
				
				if (this.index >= this.b.length) return this.eof();
				
				int i = this.b[this.index++];
				if (i < 0) i = 256 + i;
				
				this.total++;
				
				return i;
			}
			
			public int eof() {
				if (DistributedResource.ioVerbose) {
					System.out.println("DistributedResource (" +
									DistributedResource.this.uri +
									"): EOF after " +
									(this.total / 1000.0) + " kilobytes.");
				}
				
				return -1;
			}
			
			public String toString() {
				return "InputStream for " + DistributedResource.this;
			}
			
			protected void finalize() {
				if (DistributedResource.ioVerbose) {
					System.out.println("DistributedResource (" +
									DistributedResource.this.uri +
									"): Finalizing IO stream after " +
									(this.total / 1000.0) + " kilobytes.");
				}
			}
		};
		
		return in;
	}
	
	public void setURI(String uri) { this.uri = uri; }
	public String getURI() { return this.uri; }
	
	public synchronized void load(IOStreams io) throws IOException {
		this.clearCache();
		
		int s = io.in.readInt();
		
		if (verbose)
			System.out.println("DistributedResource.load: " + s + " chunks to load.");
		
		byte[][] b = new byte[0][0];
		
		if (this.data != null && this.data.length < s) {
			b = this.data;
			this.data = null;
			
			if (verbose)
				System.out.println("DistributedResource.load: Existing data was incomplete.");
		}
		
		if (this.data == null) {
			this.data = new byte[s][this.chunkSize];
			this.toa = new long[s];
			this.loaded = new boolean[s];
		}
		
		this.tot = 0;
		
		for (int i = 0; i < b.length; i++) {
			this.setData(i, b[i]);
			if (b[i] != null) this.tot += b[i].length;
		}
		
		i: for (int i = 0; i < this.data.length; i++) {
			if (this.loaded[i]) continue i;
			
			io.out.writeInt(i);
			int si = io.in.readInt();
			
			if (si < 0) {
				System.out.println("DistributedResource: Unable to load chunk " + i);
				continue i;
			}
			
			this.data[i] = new byte[this.chunkSize];
			this.toa[i] = System.currentTimeMillis();
			
			for (int j = 0; j < si; j++)
				this.data[i][j] = io.in.readByte();
			
			this.loaded[i] = true;
			this.tot += si;
			
			if (!this.commitAtEnd) this.commitToLocalDB(i);
			
			if (verbose)
				System.out.println("DistributedResource.load: Loaded chunk " + i);
		}
		
		this.size = this.data.length;
		
		io.out.writeInt(-1);
		
		if (verbose)
			System.out.println("DistributedResource.load: Sent end.");
		
		if (commitAtEnd) this.commitToLocalDB();
		
		this.checkCache();
	}
	
	public synchronized void send(IOStreams io) throws IOException {
		if (this.data == null) this.getData(0, true);
		
		if (verbose)
			System.out.println("DistributedResource.send: " + this.data.length + " chunks.");
		
		io.out.writeInt(this.data.length);
		
		i: while (true) {
			int i = io.in.readInt();
			if (i < 0) break;
			
			if (this.loaded[i]) {
				io.out.writeInt(this.data[i].length);
			} else {
				byte[] b = this.getData(i, true);
				
				if (b == null) {
					io.out.writeInt(-1);
					continue i;
				} else {
					io.out.writeInt(this.data[i].length);
				}
			}
			
			for (int j = 0; j < this.data[i].length; j++)
				io.out.writeByte(this.data[i][j]);
			
			if (verbose)
				System.out.println("DistributedResource.send: Sent chunk " + i);
		}
		
		if (verbose)
			System.out.println("DistributedResource.send: Recieved end.");
	}

	public void cache() {
		String origUri = this.uri;

		if (this.uri.startsWith("http://")) {
			this.uri = "/http/" + this.uri.substring(7);
		} else if (this.uri.startsWith("/http/")) {
			origUri = "http://" + this.uri.substring(6);
		} else if (this.uri.startsWith("file:/")) {
			origUri = this.uri;
			this.uri = "/files/" + this.uri.substring(this.uri.lastIndexOf("/") + 1);
		}

		ResourceDistributionTask.getCurrentTask().put(this.uri, this);
	}

	@Override
	public void loadFromURI() throws IOException {
		String origUri = this.uri;
		System.out.println("\t\t" + origUri);
		
		if (this.uri.startsWith("http://")) {
			this.uri = "/http/" + this.uri.substring(7);
		} else if (this.uri.startsWith("/http/")) {
			origUri = "http://" + this.uri.substring(6);
		} else if (this.uri.startsWith("file:/")) {
			origUri = this.uri;
			this.uri = "/files/" + this.uri.substring(this.uri.lastIndexOf("/") + 1);
		}

		IOStreams io = OutputServer.getCurrentServer().getNodeServer().parseResourceUri("resource:///" + this.uri);
		
		if (io == null) {
			this.loadFromStream(new URL(origUri).openStream());
			ResourceDistributionTask.getCurrentTask().put(this.uri, this);
		} else {
			this.load(io);
		}
		
		System.out.println(this + ": Loaded from URI");
		new Exception().printStackTrace();
		
//		throw new IOException("Tried to load DistributedResource from URI -- " + this.uri);
	}

	@Override
	public void saveLocal(String file) throws IOException {
		throw new IOException("Tried to store DistributedResource to file -- " + this.uri);
	}

	@Override
	public String toString() {
		return "DistributedResource (" + this.uri + ")";
	}
	
	public static DistributedResource createDistributedResource(String uri) {
		return getResource(new DistributedResource(uri));
	}
	
	public static DistributedResource createDistributedResource(String uri, int size) {
		return getResource(new DistributedResource(uri, new Permissions(), size));
	}

	public static DistributedResource createDistributedResource(Resource r) {
		return getResource(new DistributedResource(r));
	}
	
	private static DistributedResource getResource(DistributedResource res) {
		Class c = ResourceDistributionTask.getResourceClass(
											res.getData(0, true, true, false));
		if (c.equals(DistributedResource.class)) return res;
		
		if (DistributedResource.verbose)
			System.out.println("DistributedResource: Found resource class " + c.getName());
		
		try {
			DistributedResource r = (DistributedResource) c.newInstance();
			r.setURI(res.getURI());
			return r;
		} catch (ClassCastException e) {
			System.out.println("DistributedResource: Resource class " + c +
							" is not a subclass of DistributedResource.");
		} catch (InstantiationException e) {
			System.out.println("DistributedResource: Could not instantiate resource class " +
								c + " (" + e.getMessage() + ")");
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			System.out.println("DistributedResource: Could not access resource class " +
								c + " (" + e.getMessage() + ")");
			e.printStackTrace();
		}
		
		return res;
	}
}
