/*
 * Copyright 2019 Michael Murray
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
import io.almostrealism.db.Query.ResultHandler;
import io.almostrealism.db.QueryHandler;
import io.almostrealism.persist.ResourceHeaderParser;
import io.almostrealism.relation.Graph;
import io.almostrealism.resource.Resource;
import io.flowtree.Server;
import io.flowtree.Server.ResourceProvider;
import io.flowtree.job.AbstractJobFactory;
import io.flowtree.job.Job;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.JobOutput;
import org.almostrealism.io.OutputHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A ResourceDistributionTask object maintains a collection of Job instances that are cycled
 * into the queue of a running Rings client as necessary for moving chunks of data (from
 * DistributedResource objects that have been loaded) to peer machines.
 * These Job instances (ResourceDistributionJob) are designed to select the least
 * recently/frequently accessed pieces of data and move them to peer computers.
 * ResourceDistributionTask also implements the OutputHandler interface. When a
 * ResourceDistributionJob instance is used to move stored data to a peer node this
 * OutputHandler instance on the remote machine is used to commit the data using what
 * ever storage method is configured for the Rings client and also to retrieve the
 * data when it is requested by a ResourceDistributionJob for transmitting to a remote
 * client. The storage method could be a relational database using DefaultOutputHandler,
 * a collection of data files in a local directory or any other way of storing information 
 * on the local machine.
 * 
 * @author  Michael Murray
 */
public class ResourceDistributionTask extends AbstractJobFactory implements OutputHandler, QueryHandler,
													NodeProxy.EventListener, Server.ResourceProvider,
													Graph<Resource> {
	/**
	 * When {@code true}, diagnostic messages about resource operations such as
	 * directory creation, deletion, and peer notifications are printed to
	 * standard output.
	 */
	public static boolean verbose = false;

	/**
	 * When {@code true}, query execution details including result keys and
	 * duplication counts are printed to standard output.
	 */
	public static boolean queryVerbose = false;

	/**
	 * Maximum total in-memory cache size in bytes before the eviction policy
	 * is triggered. Default is 250 MB.
	 */
	public static long maxCache = 250 * 1000 * 1000;

	/** The singleton {@link ResourceDistributionTask} for this JVM. */
	private static ResourceDistributionTask current;

	/** Registry of {@link ResourceHeaderParser} instances used to select the concrete resource type. */
	private static final List resourceTypes = new ArrayList();

	/**
	 * Listener notified whenever the resource inventory changes, so that any
	 * external caches or views can be invalidated.
	 */
	public interface InvalidateListener {
		/** Called when the resource inventory has been modified. */
		void fireInvalidate();
	}

	/**
	 * Internal representation of a directory entry in the distributed file
	 * system. A directory may have an optional {@link ResourceProvider} that
	 * supplies resources on demand.
	 */
	private static class Directory {
		/** URI of the directory, always ending with {@code /}. */
		String uri;

		/**
		 * Optional provider that serves resources under this directory URI.
		 * When non-null, lookup for entries under this directory is delegated
		 * to the provider.
		 */
		ResourceProvider provider;
	}

	/**
	 * A background {@link Runnable} that loads a {@link DistributedResource}
	 * from a piped input stream, allowing callers to stream data into the
	 * distributed file system asynchronously.
	 */
	private static class Loader implements Runnable {

		/** The resource that will receive the streamed data. */
		DistributedResource res;

		/** Source input stream that provides the resource byte content. */
		InputStream in;

		/**
		 * Constructs a {@link Loader} for the given resource and input stream.
		 *
		 * @param res the target {@link DistributedResource}
		 * @param in  the source byte stream to load from
		 */
		public Loader(DistributedResource res, InputStream in) {
			this.res = res;
			this.in = in;
		}

		/**
		 * Loads the resource from the input stream via
		 * {@link DistributedResource#loadFromStream(InputStream)}, logging
		 * start and end events when {@link DistributedResource#ioVerbose} is
		 * enabled.
		 */
		@Override
		public void run() {
			if (DistributedResource.ioVerbose) {
				System.out.println("ResourceDistributionTask.Loader (" +
									this.res + "): Started");
			}
			
			try {
				this.res.loadFromStream(in);
			} catch (IOException e) {
				System.out.println("ResourceDistributionTask.Loader: " + e);
			}
			
			if (DistributedResource.ioVerbose) {
				System.out.println("ResourceDistributionTask.Loader (" +
									this.res + "): Ended");
			}
		}
	}
	
	/**
	 * A {@link Query.ResultHandler} that scans database query results to find
	 * the chunk with the fewest duplications (lowest replication count). The
	 * matching time-of-arrival (TOA) is returned via {@link #getToa()} and the
	 * duplication count is incremented atomically so that the next selection
	 * picks a different chunk.
	 */
	private static class CustomResultHandler implements Query.ResultHandler {

		/** Tracks the minimum duplication count seen so far. */
		private int fewest = Integer.MAX_VALUE;

		/** Time-of-arrival of the chunk with the fewest duplications, or {@code -1}. */
		private long toa = -1;

		/**
		 * Processes one query result row. If the duplication count for the
		 * given key is less than the current minimum, records this key as the
		 * new best candidate.
		 *
		 * @param key   time-of-arrival timestamp string
		 * @param value duplication count string (treated as 0 if null or empty)
		 */
		@Override
		public void handleResult(String key, String value) {
			if (value == null || value.length() <= 0) value = "0";
			int dup = Integer.parseInt(value);
			
			if (dup < fewest) {
				toa = Long.parseLong(key);
				fewest = dup;
			}
			
			if (ResourceDistributionTask.queryVerbose)
				System.out.println("ResourceDistributionTask.CustomResultHandler: "
									+ key + " " + value);
		}
		
		/**
		 * Handles a byte-array result, which is unexpected in this context.
		 * Logs a warning and takes no action.
		 *
		 * @param key   result key
		 * @param value unexpected byte array value
		 */
		@Override
		public void handleResult(String key, byte[] value) {
			System.out.println("ResourceDistributionTask.CustomResultHandler: " +
								"Recieved bytes when string was expected.");
		}

		/**
		 * Returns the time-of-arrival of the chunk with the fewest duplications.
		 * As a side effect, increments the duplication count in the database for
		 * the returned chunk. Returns {@code -1} if no results were processed.
		 *
		 * @return the TOA of the best candidate, or a value {@code <= 0} if none
		 */
		public long getToa() {
			if (this.toa <= 0) return this.toa;
			
			OutputServer.getCurrentServer().
				getDatabaseConnection().
				updateDuplication(this.toa, fewest + 1);
			
			return this.toa;
		}
	}
	
	/**
	 * A reusable {@link Job} that selects the least-replicated chunk from the
	 * local database and encodes it for transmission to a remote peer. When the
	 * job runs on the remote node the {@link #set(String, String)} method stores
	 * the received chunk data in that node's local {@link OutputServer}.
	 *
	 * <p>The job is never marked complete — it sleeps for a configured duration
	 * after each run and then marks itself available for reuse.
	 */
	protected static class ResourceDistributionJob implements Job {

		/** Unique job identifier. */
		private String id;

		/** Duration in milliseconds to sleep after each run before becoming available again. */
		private int sleep;

		/** {@code true} while this job is currently in flight and should not be reused. */
		private boolean use;

		/** URI of the chunk being transferred. */
		private String uri;

		/** Raw chunk data encoded as a string for transport. */
		private String data;

		/** Chunk index within the resource identified by {@link #uri}. */
		private int index;

		/** Completion future; never completed because this job loops indefinitely. */
		private final CompletableFuture<Void> future = new CompletableFuture<>();

		/** Parent task used to query the database for the next chunk to distribute. */
		private ResourceDistributionTask task;

		/**
		 * Constructs a {@link ResourceDistributionJob} with no associated task.
		 * The task must be set before {@link #encode()} is called.
		 */
		public ResourceDistributionJob() { }

		/**
		 * Constructs a {@link ResourceDistributionJob} associated with the
		 * given distribution task.
		 *
		 * @param task the owning {@link ResourceDistributionTask}
		 */
		public ResourceDistributionJob(ResourceDistributionTask task) { this.task = task; }

		/**
		 * Sets the sleep duration (in milliseconds) this job will pause after
		 * each run before marking itself available for reuse.
		 *
		 * @param sleep sleep duration in milliseconds
		 */
		public void setSleep(int sleep) { this.sleep = sleep; }

		/**
		 * Returns {@code true} if this job is currently in flight and should
		 * not be dispatched again.
		 *
		 * @return {@code true} if in use
		 */
		public boolean isInUse() { return this.use; }

		/**
		 * Sets the in-use flag for this job.
		 *
		 * @param use {@code true} to mark the job as in flight
		 */
		public void setInUse(boolean use) { this.use = use; }

		/**
		 * Sets the raw chunk data to carry during the next transmission.
		 *
		 * @param data encoded chunk data string
		 */
		public void setData(String data) { this.data = data; }
		
		/**
		 * Requests a piece of data from the ResourceDistributionTask and converts
		 * it to a String. The String also includes valid information to reconstruct
		 * the ResourceDistributionJob within a remotely running Server instance.
		 * The remote Server instance will reference the set method of this class
		 * to reconstruct the state of the object. When the set method is called
		 * with the value String containing the piece of data, the data will be
		 * sent to the output server specified by the remote Server.
		 */
		@Override
		public String encode() {
			if (this.task == null) return null;
			
			if (!this.task.loadJob(this)) {
				System.out.println("ResourceDistributionJob: No data available.");
				return null;
			}
			
			this.task.loadJob(this);

			String b = this.getClass().getName() +
					":uri=" +
					this.uri +
					":i=" +
					this.index +
					":RAW:" +
					this.data;
			
			return b;
		}

		@Override
		public void set(String key, String value) {
			if (key.equals("uri")) {
				this.uri = value;
			} else if (key.equals("i")) {
				this.index = Integer.parseInt(value);
			} else if (key.equals("data")) {
				this.data = value;
			} else {
				OutputServer s = OutputServer.getCurrentServer();
				if (s == null) return;
				
				s.getDatabaseConnection().storeOutput(System.currentTimeMillis(),
														value.getBytes(),
														this.uri, this.index);
			}
		}
		
		/**
		 * Returns the unique task identifier for this job.
		 *
		 * @return the task id
		 */
		@Override
		public String getTaskId() { return this.id; }

		/**
		 * Returns a human-readable description of this job.
		 *
		 * @return task string
		 */
		@Override
		public String getTaskString() { return "ResourceDistributionTask (" + this.id + ")"; }

		/** Since this is a reusable Job, it is never marked complete. */
		@Override
		public CompletableFuture<Void> getCompletableFuture() { return future; }

		/**
		 * Sleeps for a set amount of time and then marks this ResourceDistributionJob
		 * as ready to be reused.
		 */
		@Override
		public void run() {
			if (this.task == null) return;
			
			try {
				Thread.sleep(this.sleep);
			} catch (InterruptedException ie) { }
			
			this.use = false;
		}
	}
	
	/** Task identifier for this distribution task. */
	private String id;

	/** Pool of reusable {@link ResourceDistributionJob} instances. */
	private final Set jobs;

	/** Time in milliseconds each job sleeps before becoming available for reuse. */
	private final int sleep;

	/** Running total of bytes currently held in the in-memory resource cache. */
	private long cacheTot;

	/** Listener notified when the resource inventory changes. */
	private InvalidateListener inListen;

	/**
	 * Master inventory of resources and directories, keyed by normalised URI.
	 * Values are either {@link DistributedResource} instances or
	 * {@link Directory} instances.
	 */
	private final Map items;

	/** The local {@link OutputServer} used for database-backed storage. */
	private OutputServer server;

	/** Lifecycle completion future for this task; never completed in normal operation. */
	private CompletableFuture<Void> future;

	/**
	 * Constructs a new {@link ResourceDistributionTask}, registers it as the
	 * JVM-wide singleton, initialises the default resource types, loads the
	 * existing file list from the local database, and creates the job pool.
	 *
	 * @param server the local {@link OutputServer} providing database access
	 * @param jobs   number of reusable {@link ResourceDistributionJob} instances
	 *               to maintain in the pool
	 * @param sleep  milliseconds each job sleeps between runs
	 */
	public ResourceDistributionTask(OutputServer server, int jobs, int sleep) {
		ResourceDistributionTask.current = this;
		
		this.jobs = new HashSet();
		this.items = new LinkedHashMap();
		this.sleep = sleep;
		
		this.initDefaultResourceTypes();
		this.initFiles(server);
		this.initJobs(jobs);
	}
	
	/**
	 * Registers the built-in resource types with the type registry. Currently
	 * registers {@link ConcatenatedResource} via its
	 * {@link ConcatenatedResource.ConcatenatedResourceHeaderParser}.
	 */
	protected void initDefaultResourceTypes() {
		ResourceDistributionTask.addResourceClass(
				new ConcatenatedResource.ConcatenatedResourceHeaderParser());
		System.out.println("ResourceDistributionTask: Added ConcatenatedResource type.");
	}
	
	/**
	 * Initialises the file inventory by creating the virtual {@code /files/}
	 * directory entry and loading all previously stored resource URIs from the
	 * local database into the in-memory items map.
	 *
	 * @param server the {@link OutputServer} providing the database connection
	 */
	protected void initFiles(OutputServer server) {
		Directory fdir = new Directory();
		fdir.uri = "/files/";
		this.items.put(fdir.uri, fdir);
		
		this.server = server;
		
		System.out.println("ResourceDistributionTask: Loading file list from local DB.");
		
		DatabaseConnection db = this.server.getDatabaseConnection();
		
		Query q = new Query(db.getTable());
		q.setColumn(0, DatabaseConnection.uriColumn);
		q.setColumn(1, null);
		q.setResultHandler(new ResultHandler() {
			@Override
			public void handleResult(String key, String value) {
				if (value == null) {
					System.out.println("WARN: Null value for key " + key);
					return;
				}

				if (!ResourceDistributionTask.this.items.containsKey(value)) {
					DistributedResource res = DistributedResource.createDistributedResource(value);
					ResourceDistributionTask.this.items.put(value, res);
					
					if (ResourceDistributionTask.verbose)
						System.out.println("ResourceDistributionTask: Loaded " + value +
											" from local DB.");
				}
			}

			@Override
			public void handleResult(String key, byte[] value) {
					System.out.println("ResourceDistributionTask: Received bytes " +
										"when String was expected.");
			}
		});
		
		db.executeQuery(q);
	}
	
	/**
	 * Populates the job pool with the specified number of
	 * {@link ResourceDistributionJob} instances, clearing any previously
	 * existing pool entries.
	 *
	 * @param tot total number of jobs to create
	 */
	protected void initJobs(int tot) {
		this.jobs.clear();
		
		for (int i = 0; i < tot; i++) {
			this.jobs.add(new ResourceDistributionJob(this));
		}
	}
	
	/**
	 * This is a direct put to the items {@link Hashtable}. Be careful.
	 * 
	 * @param uri  URI to use as key.
	 * @param d  DistributedResource to store.
	 */
	protected void put(String uri, DistributedResource d) { this.items.put(uri, d); }
	
	/**
	 * Queries the local database for the chunk with the fewest replications
	 * and configures the given {@link ResourceDistributionJob} with that chunk's
	 * URI and index so it can be encoded and sent to a peer.
	 *
	 * @param j the job to populate with chunk data
	 * @return {@code true} if a suitable chunk was found and the job was
	 *         configured; {@code false} if no data is available
	 */
	protected boolean loadJob(ResourceDistributionJob j) {
		String table = this.server.getDatabaseConnection().getTable();
		Query q = new Query(table, DatabaseConnection.toaColumn,
							DatabaseConnection.dupColumn, null);
		CustomResultHandler handler = new CustomResultHandler();
		q.setResultHandler(handler);
		
		DatabaseConnection db = this.server.getDatabaseConnection();
		db.executeQuery(q);
		
		long toa = handler.getToa();
		if (toa <= 0) return false;
		
		return db.configureProperties(j, toa);
	}
	
	/**
	 * Creates a new {@link DistributedResource} for the given URI and adds it
	 * to the inventory, notifying all connected peers of the new URI. Returns
	 * {@code null} if the URI already exists in the inventory.
	 *
	 * @param uri the URI of the new resource
	 * @return the created {@link DistributedResource}, or {@code null} if it
	 *         already existed
	 */
	public synchronized DistributedResource createResource(String uri) {
		if (this.items.containsKey(uri)) return null;
		
		DistributedResource res = DistributedResource.createDistributedResource(uri);
		this.items.put(uri, res);
		
		this.notifyPeers(uri, Message.DistributedResourceUri);
		
		return res;
	}
	
	/**
	 * Creates a new virtual directory at the specified URI. The URI is
	 * normalised to end with {@code /}. Returns {@code null} if the directory
	 * already exists.
	 *
	 * @param uri the directory URI; a trailing {@code /} is added if absent
	 * @return the normalised directory URI, or {@code null} if it already existed
	 */
	public synchronized String createDirectory(String uri) {
		if (!uri.endsWith("/")) uri = uri + "/";
		if (this.items.containsKey(uri)) return null;
		
		Directory res = new Directory();
		res.uri = uri;
		
		if (ResourceDistributionTask.verbose)
			System.out.println("ResourceDistributionTask: Create dir " + uri);
		
		this.items.put(uri, res);
		return uri;
	}
	
	/**
	 * Associates a {@link ResourceProvider} with a directory URI so that
	 * resource lookups under that URI are delegated to the provider. The
	 * directory must already exist in the inventory.
	 *
	 * @param dir URI of the target directory
	 * @param p   the provider to associate
	 * @return {@code true} if the provider was set; {@code false} if the URI
	 *         does not identify an existing directory
	 */
	public synchronized boolean setResourceProvider(String dir, ResourceProvider p) {
		Object o = null;
		
		if (this.isDirectory(dir)) o = this.items.get(dir);
		
		if (!(o instanceof Directory)) {
			System.out.println("ResourceDistributionTask.setResourceProvider: " + dir +
								" is not a directory.");
			return false;
		}
		
		Directory d = (Directory) o;
		d.provider = p;
		
		return true;
	}

	/**
	 * Removes the resource at the given URI from the inventory, notifies all
	 * peers of the invalidation, and deletes the corresponding rows from the
	 * local database. Returns {@code false} if the URI is not found or is the
	 * root {@code /}.
	 *
	 * @param uri the URI of the resource to delete
	 * @return {@code true} if the resource was found and deleted
	 */
	public synchronized boolean deleteResource(String uri) {
		if (!this.items.containsKey(uri)) return false;
		if (uri.equals("/")) return false;
		
		this.items.remove(uri);
		this.notifyPeers(uri, Message.DistributedResourceInvalidate);
		this.removeFromLocalDB(uri);
		
		if (ResourceDistributionTask.verbose)
			System.out.println("ResourceDistributionTask: Deleted " + uri);
		
		return true;
	}
	
	/**
	 * Deletes the specified directory and recursively deletes the contents
	 * from the distributed database.
	 * 
	 * @param uri  Path to directory.
	 * @return  True if the directory and contents was successfully deleted, false otherwise.
	 */
	public synchronized boolean deleteDirectory(String uri) {
		String[] s = this.getChildren(uri);
		
		boolean deleted = true;
		
		for (int i = 0; i < s.length; i++) {
			if (this.isDirectory(s[i])) {
				if (!this.deleteDirectory(s[i])) deleted = false;
			} else {
				if (!this.deleteResource(s[i])) deleted = false;
			}
		}
		
		return deleted;
	}
	
	/**
	 * Returns the parent directory URI of the given URI, or {@code null} if
	 * the URI is the root {@code /}.
	 *
	 * @param uri the child URI
	 * @return the parent URI string, or {@code null} for the root
	 */
	public synchronized String getParent(String uri) {
		if (uri.equals("/")) return null;
		
		if (uri.endsWith("/"))
			uri = uri.substring(0, uri.length() - 1);
		
		return uri.substring(0, uri.lastIndexOf("/"));
	}
	
	/**
	 * Returns the direct children of the given directory URI. Each returned
	 * path is the longest prefix up to the first slash after the directory's
	 * own prefix, effectively returning immediate children only (not
	 * grandchildren).
	 *
	 * @param uri the parent directory URI
	 * @return an array of child URI strings; never {@code null}
	 */
	public synchronized String[] getChildren(String uri) {
		List names = new ArrayList();
		Iterator itr = this.items.keySet().iterator();
		
		if (!uri.endsWith("/")) uri = uri + "/";
		
		while (itr.hasNext()) {
			String s = (String) itr.next();
			while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
			
			if (s.startsWith(uri) && !s.equals(uri) && !names.contains(s)) {
				String ss = s.substring(uri.length() + 1);
				int si = ss.indexOf("/");
				
				if (si < 0 || si >= ss.length() - 1) {
					names.add(s);
				} else {
					s = s.substring(0, uri.length() + 1 + si);
					if (!names.contains(s)) names.add(s);
				}
			}
		}
		
		if (ResourceDistributionTask.verbose)
			System.out.println("ResourceDistributionTask: Got " + names.size() + " children " + uri);
		
		return (String[]) names.toArray(new String[0]);
	}

	@Override
	public Collection<Resource> neighbors(Resource node) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int countNodes() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns {@code true} if the given URI refers to a directory in the
	 * distributed file system. The root {@code /} is always a directory. A URI
	 * that has children but lacks an explicit {@link Directory} entry is
	 * lazily registered as a directory.
	 *
	 * @param uri the URI to test
	 * @return {@code true} if the URI is a directory
	 */
	public synchronized boolean isDirectory(String uri) {
		if (ResourceDistributionTask.verbose)
			System.out.println("ResourceDistributionTask: Is dir? " + uri);
		
		if (uri.equals("/")) return true;
		if (!uri.endsWith("/")) uri = uri + "/";
		if (this.items.get(uri) instanceof Directory) return true;
		
		if (this.getChildren(uri).length > 0) {
			Directory d = new Directory();
			d.uri = uri;
			this.items.put(d.uri, d);
			return true;
		}
		
		return false;
	}
	
	/**
	 * This method calls getResource using the specified uri.
	 * 
	 * @see  #getResource(String)
	 */
	@Override
	public Resource loadResource(String uri) { return this.getResource(uri); }

	/**
	 * This method calls getResource using the specified uri and exclude string.
	 *
	 * @see  #getResource(String, String)
	 */
	@Override
	public Resource loadResource(String uri, String exclude) {
		return this.getResource(uri, exclude);
	}
	
	/**
	 * Returns an {@link OutputStream} for writing data to the specified URI.
	 * If the resource does not yet exist it is created. Data written to the
	 * returned stream is loaded into the resource asynchronously via a
	 * {@link Loader} background thread.
	 *
	 * @param uri the URI of the target resource
	 * @return an output stream connected to the resource via a pipe
	 * @throws IOException if the pipe cannot be created
	 */
	public OutputStream getOutputStream(String uri) throws IOException {
		DistributedResource res = this.getResource(uri);
		if (res == null) res = this.createResource(uri);
		if (res == null) return null;
		
		PipedOutputStream out = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream(out);
		
		Thread t = new Thread(new Loader(res, in));
		t.start();
		
		return out;
	}
	
	/**
	 * Evicts a randomly selected resource from the in-memory cache if the
	 * total cached bytes exceed {@link #maxCache}. The evicted resource's data
	 * is cleared via {@link DistributedResource#clear()}.
	 */
	protected void checkFull() {
		//TODO Add intelligent clutter removal...
		
		if (this.cacheTot > maxCache) {
			Object[] o = this.items.entrySet().toArray();
			Map.Entry ent = (Map.Entry) o[(int) (Math.random() * o.length)];
			DistributedResource r = (DistributedResource) ent.getValue();
			r.clear();
		}
	}
	
	/**
	 * Increases the tracked in-memory cache total by the given number of bytes.
	 *
	 * @param tot bytes to add to the cache total
	 */
	protected void addCache(long tot) { this.cacheTot += tot; }

	/**
	 * Decreases the tracked in-memory cache total by the given number of bytes.
	 *
	 * @param tot bytes to subtract from the cache total
	 */
	protected void subtractCache(long tot) { this.cacheTot -= tot; }

	/**
	 * Deletes all database rows for the specified URI from the local
	 * {@link OutputServer}. If no local database is available the operation is
	 * logged and skipped.
	 *
	 * @param uri the resource URI whose rows should be deleted
	 */
	protected void removeFromLocalDB(String uri) {
		OutputServer s = OutputServer.getCurrentServer();
		
		if (s == null) {
			System.out.println("ResourceDistributionTask: Unable to remove " +
								uri + " (No local DB)");
			return;
		}
		
		s.getDatabaseConnection().deleteUri(uri);
	}
	
	/**
	 * Returns the {@link DistributedResource} registered at the given URI,
	 * applying no host exclusion. Equivalent to calling
	 * {@link #getResource(String, String)} with {@code exclude = null}.
	 *
	 * @param uri  the resource path (a leading {@code /} is added if absent)
	 * @return     the corresponding {@link DistributedResource}, or {@code null}
	 *             if no resource is registered at that URI
	 */
	public synchronized DistributedResource getResource(String uri) {
		return this.getResource(uri, null);
	}

	/**
	 * Returns the {@link DistributedResource} registered at the given URI,
	 * optionally excluding a particular peer host when the resource must be
	 * fetched from a remote node. If the registered item is a {@link Directory}
	 * with an associated {@link ResourceProvider}, the provider's
	 * {@code loadResource} method is called to obtain a concrete resource.
	 *
	 * @param uri      the resource path (a leading {@code /} is added if absent)
	 * @param exclude  hostname of the peer to skip during remote retrieval,
	 *                 or {@code null} to allow any peer
	 * @return         the corresponding {@link DistributedResource}, or
	 *                 {@code null} if nothing is registered at that URI
	 */
	public synchronized DistributedResource getResource(String uri, String exclude) {
		if (ResourceDistributionTask.verbose)
			System.out.println("ResourceDistributionTask: Get " + uri);
		
		if (!uri.startsWith("/")) uri = "/" + uri;
		
		Object res = this.items.get(uri);
		if (res instanceof Directory) {
			ResourceProvider p = ((Directory) res).provider;
			if (p != null) res = p.loadResource(uri, exclude);
		}
		
		DistributedResource dres = null;
		
		if (res instanceof DistributedResource)
			dres = (DistributedResource) res;
		else if (res instanceof Resource) {
			System.out.println("ResourceDistributionTask: Item cache contained " + res.getClass());
			dres = new DistributedResource((Resource) res);
		} else
			return null;
		
		dres.setExcludeHost(exclude);
		return dres;
	}
	
	/**
	 * Notifies all currently connected peers of every {@link DistributedResource}
	 * in the local inventory by sending a {@link io.flowtree.msg.Message#DistributedResourceUri}
	 * message for each entry.
	 *
	 * @return the number of resources for which peer notifications were sent
	 */
	public int notifyPeers() {
		Iterator itr = this.items.entrySet().iterator();
		
		int tot = 0;
		
		w: while (itr.hasNext()) {
			Map.Entry ent = (Map.Entry) itr.next();
			
			if (!(ent.getValue() instanceof DistributedResource))
				continue w;
			
			this.notifyPeers((String) ent.getKey(), Message.DistributedResourceUri);
			tot++;
		}
		
		return tot;
	}
	
	/**
	 * Registers a listener to be notified whenever the resource inventory
	 * changes (resources added, deleted, or invalidated).
	 *
	 * @param listener the listener to register; replaces any previous listener
	 */
	public void setInvalidateListener(InvalidateListener listener) { this.inListen = listener; }

	/**
	 * Fires the invalidation event on the registered {@link InvalidateListener},
	 * if one is set.
	 */
	protected void fireInvalidate() {
		if (this.inListen != null)
			this.inListen.fireInvalidate();
	}
	
	/**
	 * Fires the invalidation event and notifies all connected peers of a
	 * resource URI change. The {@code type} parameter controls whether this is
	 * an addition ({@link io.flowtree.msg.Message#DistributedResourceUri}) or
	 * an invalidation ({@link io.flowtree.msg.Message#DistributedResourceInvalidate}).
	 *
	 * @param uri  the resource URI to announce
	 * @param type message type constant indicating the change
	 * @return the number of peers successfully notified
	 */
	protected int notifyPeers(String uri, int type) {
		this.fireInvalidate();
		
		NodeProxy[] p = OutputServer.getCurrentServer().getNodeServer().getNodeGroup().getServers();
		int tot = 0;
		
		for (int i = 0; i < p.length; i++) {
			if (this.notifyPeer(uri, type, p[i]))
				tot++;
		}
		
		if (Message.verbose)
			System.out.println("ResourceDistributionTask: Notified " + tot +
								" peers of " + uri);
		
		return tot;
	}
	
	/**
	 * Sends a notification message for the given URI and type to a single
	 * peer node proxy.
	 *
	 * @param uri  the resource URI to announce
	 * @param type message type constant
	 * @param np   the target peer
	 * @return {@code true} if the message was sent successfully; {@code false}
	 *         on I/O error
	 */
	protected boolean notifyPeer(String uri, int type, NodeProxy np) {
		try {
			if (Message.verbose)
				System.out.println("ResourceDistributionTask: Notifing " + np + " of " + uri);
			
			Message m = new Message(type, -3, np);
			m.setQueueBypass(true);
			m.setString(uri);
			m.send(-3);
			
			return true;
		} catch (IOException ioe) {
			System.out.println("ResourceDistributionTask: IO error notifing " +
								np + " of " + uri + " (" + ioe.getMessage() + ")");
			return false;
		}
	}
	
	/**
	 * Instantiates a {@link Job} from its encoded string representation by
	 * delegating to {@link Server#instantiateJobClass(String)}.
	 *
	 * @param data  the encoded job string as produced by {@link Job#encode()}
	 * @return      a new {@link Job} instance, or {@code null} if the class
	 *              cannot be resolved
	 */
	@Override
	public Job createJob(String data) { return Server.instantiateJobClass(data); }

	/**
	 * Returns the wire-format encoding of this task. {@link ResourceDistributionTask}
	 * is not transmitted over the job protocol, so this always returns {@code null}.
	 *
	 * @return {@code null}
	 */
	@Override
	public String encode() { return null; }
	
	/**
	 * Sets the scheduling priority for this task. This implementation is a
	 * no-op; priority is fixed at {@code 1.0}.
	 *
	 * @param p  the requested priority (ignored)
	 */
	@Override
	public void setPriority(double p) { }

	/**
	 * Returns the scheduling priority of this task.
	 *
	 * @return always {@code 1.0}
	 */
	@Override
	public double getPriority() { return 1.0; }

	/**
	 * Returns the {@link CompletableFuture} that completes when this task's
	 * background distribution loop finishes.
	 *
	 * @return the task's completion future
	 */
	@Override
	public CompletableFuture<Void> getCompletableFuture() { return future; }

	/**
	 * Returns the fractional completeness of this task. Because resource
	 * distribution runs indefinitely, this always returns {@code 0}.
	 *
	 * @return {@code 0}
	 */
	@Override
	public double getCompleteness() { return 0; }

	/**
	 * Returns whether this task has finished. Because resource distribution
	 * runs indefinitely, this always returns {@code false}.
	 *
	 * @return {@code false}
	 */
	@Override
	public boolean isComplete() { return false; }

	/**
	 * Returns the human-readable name of this task, including the task id.
	 *
	 * @return a string of the form {@code ResourceDistributionTask (<id>)}
	 */
	@Override
	public String getName() { return "ResourceDistributionTask (" + this.id + ")"; }

	/**
	 * Returns the unique identifier for this task instance.
	 *
	 * @return the task id string assigned at construction
	 */
	@Override
	public String getTaskId() { return this.id; }
	
	/**
	 * Returns the next available {@link ResourceDistributionJob} from the
	 * internal pool, or {@code null} if no job is free or if the required
	 * infrastructure (an {@link OutputServer} with at least one peer) is not
	 * yet available. The returned job is marked as in-use and will not be
	 * returned again until it completes and resets its state.
	 *
	 * @return an idle {@link ResourceDistributionJob} ready for dispatch, or
	 *         {@code null} if none are available
	 */
	@Override
	public Job nextJob() {
		if (OutputServer.getCurrentServer() == null)
			return null;

		if (OutputServer.getCurrentServer().getNodeServer().getPeers().length <= 0)
			return null;

		
		Iterator itr = this.jobs.iterator();
		
		while (itr.hasNext()) {
			ResourceDistributionJob j = (ResourceDistributionJob) itr.next();
			
			if (!j.isInUse()) {
				j.setSleep(this.sleep);
				j.setInUse(true);
				return j;
			}
		}
		
		return null;
	}
	
	/**
	 * Applies a key-value configuration pair decoded from the job wire format.
	 * This implementation is a no-op because {@link ResourceDistributionTask}
	 * has no configurable parameters delivered via the job protocol.
	 *
	 * @param key    the configuration key (ignored)
	 * @param value  the configuration value (ignored)
	 */
	@Override
	public void set(String key, String value) { }
	
	/**
	 * Method called by the OutputServer when data arrives to be persisted into
	 * the disk cache maintained by this ResourceDistributionTask. The ResourceDistributionTask
	 * does not actually perform the storage and this method is provided only so that
	 * the ResourceDistributionTask will be notified when the state of the cache changes
	 * (used for creating popularity ratings).
	 */
	@Override
	public void storeOutput(long time, int uid, JobOutput output) {
	}

	/**
	 * Handles an {@link OutputServer} query for a specific chunk of a
	 * {@link DistributedResource}. The query is expected to contain the
	 * resource URI as its first column value and the integer chunk index as
	 * its second. If the chunk is available in the local cache the raw bytes
	 * are returned in a {@link Hashtable} keyed by chunk index; otherwise
	 * {@code null} is returned.
	 *
	 * @param q  the incoming query carrying the resource URI and chunk index
	 * @return   a single-entry {@link Hashtable} mapping chunk index to byte
	 *           data, or {@code null} if the URI column does not match, the
	 *           index is negative, or the chunk is not locally cached
	 */
	@Override
	public Map executeQuery(Query q) {
		if (!DatabaseConnection.uriColumn.equals(q.getColumn(0))) return null;
		String uri = q.getValue(0);

		int index = Integer.parseInt(q.getValue(1));
		if (index < 0) return null;

		DistributedResource r = (DistributedResource) this.items.get(uri);
		byte[] b = r.getData(index, false);

		Map h = null;

		if (b != null) {
			h = new LinkedHashMap();
			h.put(Integer.valueOf(index), b);
		}

		return h;
	}
	
	/**
	 * Adds the specified ResourceHeaderParser to the list of parser to be
	 * checked when a new resource is loaded.
	 * 
	 * @param p ResourceHeaderParser
	 */
	public static void addResourceClass(ResourceHeaderParser p) {
		resourceTypes.add(p);
	}
	
	/**
	 * Checks the known ResourceHeaderParsers to see if any of them indicate
	 * that an alternative class should be used for the resource with the
	 * specified header.
	 * 
	 * @param b  Header for resource.
	 * @return  Class to use.
	 */
	public static Class getResourceClass(byte[] b) {
		if (b == null) return DistributedResource.class;
		
		Iterator itr = resourceTypes.iterator();
		
		while (itr.hasNext()) {
			ResourceHeaderParser p = (ResourceHeaderParser) itr.next();
			
			if (p.doesHeaderMatch(b)) return p.getResourceClass();
		}
		
		return DistributedResource.class;
	}
	
	/**
	 * Returns the most recently created {@link ResourceDistributionTask}
	 * instance, or {@code null} if no instance has been constructed yet.
	 *
	 * @return the current singleton {@link ResourceDistributionTask}
	 */
	public static ResourceDistributionTask getCurrentTask() {
		return ResourceDistributionTask.current;
	}
	
	/**
	 * Called when a new peer connection is established. Notifies the new peer
	 * of all resources currently in the local inventory so that it can
	 * populate its own distributed file-system view.
	 *
	 * @param p the newly connected peer
	 */
	@Override
	public void connect(NodeProxy p) {
		synchronized (this.items) {
			Iterator itr = this.items.entrySet().iterator();
			
			w: while (itr.hasNext()) {
				Map.Entry ent = (Map.Entry) itr.next();
				Object o = ent.getValue();
				if (!(o instanceof DistributedResource)) continue w;
				this.notifyPeer((String) ent.getKey(), Message.DistributedResourceUri, p);
			}
		}
	}
	
	/**
	 * Called when a peer disconnects. No action is required for the distributed
	 * file system.
	 *
	 * @param p the disconnected peer
	 * @return {@code 0}
	 */
	@Override
	public int disconnect(NodeProxy p) { return 0; }

	/**
	 * Handles incoming {@link io.flowtree.msg.Message#DistributedResourceUri}
	 * and {@link io.flowtree.msg.Message#DistributedResourceInvalidate} messages.
	 * On URI messages, if the resource is unknown it is added to the inventory
	 * and peers are notified (gossip). On invalidate messages the resource is
	 * removed from the local inventory and database. Returns {@code false} for
	 * any other message type.
	 *
	 * @param m        the received message
	 * @param reciever the index of the receiving node (unused)
	 * @return {@code true} if the message was handled; {@code false} otherwise
	 */
	@Override
	public synchronized boolean recievedMessage(Message m, int reciever) {
		if (!(m.getType() == Message.DistributedResourceUri ||
			m.getType() == Message.DistributedResourceInvalidate)) return false;
		
		String data = m.getData();
		int size = -1;
		String uri = data;
		int index = data.lastIndexOf(ENTRY_SEPARATOR);
		
		if (index > 0) {
			uri = data.substring(0, index);
			size = Integer.parseInt(data.substring(index + ENTRY_SEPARATOR.length()));
		}
		
		if (m.getType() == Message.DistributedResourceInvalidate) {
			Object o = this.items.remove(uri);
			
			if (o != null) {
				System.out.println("ResourceDistributionTask: " + o + " was invalidated.");
				this.notifyPeers(uri, Message.DistributedResourceInvalidate);
				this.removeFromLocalDB(uri);
			}
		} else if (this.items.containsKey(uri)) {
			if (DistributedResource.verbose)
				System.out.println("ResourceDistributionTask: " + uri + " exists.");
			
			DistributedResource cr = (DistributedResource) this.items.get(uri);
			
			if (size >= 0 && cr.getSize() != size)
				System.out.println("ResourceDistributionTask: Size disagreement for " + cr +
									" (" + cr.getSize() + " != " + size + ")");
		} else {
			DistributedResource r = null;
			
			if (size < 0)
				r = DistributedResource.createDistributedResource(uri);
			else
				r = DistributedResource.createDistributedResource(uri, size);
			
			this.items.put(uri, r);
			System.out.println("ResourceDistributionTask: Added " + r);
			this.notifyPeers(uri, Message.DistributedResourceUri);
		}
		
		return true;
	}
	
	/**
	 * Returns a human-readable description of this task showing the number of
	 * pooled {@link ResourceDistributionJob} instances and the inter-job sleep
	 * interval.
	 *
	 * @return a string of the form {@code ResourceDistributionTask (<jobs>, <sleep>)}
	 */
	@Override
	public String toString() {
		return "ResourceDistributionTask (" + this.jobs.size() + ", " + this.sleep + ")";
	}
}