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
import io.almostrealism.db.QueryHandler;
import io.flowtree.node.Client;
import org.almostrealism.io.JobOutput;
import org.almostrealism.io.OutputHandler;
import org.hsqldb.Server;

import java.io.EOFException;
import java.io.Externalizable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * The local database server for a FlowTree node. {@link OutputServer} owns the
 * JDBC connection to the node's relational store (HSQLDB by default), accepts
 * socket connections from other services that need to store or query
 * {@link org.almostrealism.io.JobOutput} objects and {@link io.almostrealism.db.Query}
 * results, and exposes the database to in-process consumers via the
 * {@link java.util.function.Consumer} interface.
 *
 * <p>On initialisation, the server reads database configuration from a
 * {@link java.util.Properties} object. If no JDBC driver and URI are supplied,
 * an embedded HSQLDB instance is started automatically. Optional
 * {@link org.almostrealism.io.OutputHandler} and
 * {@link io.almostrealism.db.QueryHandler} objects are loaded by class name
 * from the {@code db.handler} property.
 *
 * <p>A background thread handles socket requests in a simple request/response
 * protocol: the client writes the class name of an {@link java.io.Externalizable}
 * object, the object's data, and the server either executes a query and returns
 * the result {@link java.util.Hashtable}, or persists the output.
 *
 * @author  Michael Murray
 */
public class OutputServer implements Runnable, Consumer<JobOutput> {

	/** Name of the database table used to store job output data. */
	private static String outputTable;

	/** The currently active {@link OutputServer} singleton for this JVM. */
	private static OutputServer current;

	/** The FlowTree {@link io.flowtree.Server} that owns this output server. */
	private io.flowtree.Server nodeServer;

	/**
	 * When {@code true} the server operates in test mode: the database driver
	 * is loaded but DDL is not executed and no persistent schema is created.
	 */
	private boolean testMode;

	/** Server socket on which remote clients connect to submit queries or output. */
	private ServerSocket socket;

	/** Database connection used for all local storage and query operations. */
	private DatabaseConnection db;

	/**
	 * Standalone entry point. Reads a {@link java.util.Properties} file from
	 * the path given in {@code args[0]}, constructs an {@link OutputServer},
	 * and registers it as the current server.
	 *
	 * @param args command-line arguments; {@code args[0]} is the properties file path
	 */
	public static void main(String[] args) {
		try {
			Properties p = new Properties();
			p.load(new FileInputStream(args[0]));
			
			OutputServer s = new OutputServer(p);
			s.setCurrentServer();
			
			System.out.println("\nDB Server started");
		} catch (Exception e) {
			System.err.println("OutputServer: Error starting server: " + e);
		}
	}

	/**
	 * Constructs an uninitialised {@link OutputServer}. Callers must invoke
	 * {@link #init(java.util.Properties, io.flowtree.Server)} before use.
	 */
	public OutputServer() { }

	/**
	 * Constructs and initialises an {@link OutputServer} using the current
	 * {@link Client}'s server.
	 *
	 * @param p configuration properties
	 * @throws IOException if a socket or database error occurs during initialisation
	 */
	public OutputServer(Properties p) throws IOException { init(p, Client.getCurrentClient().getServer()); }

	/**
	 * Constructs and initialises an {@link OutputServer} using the supplied
	 * FlowTree server reference.
	 *
	 * @param p configuration properties
	 * @param s the FlowTree server that owns this output server
	 * @throws IOException if a socket or database error occurs during initialisation
	 */
	public OutputServer(Properties p, io.flowtree.Server s) throws IOException { init(p, s); }

	/**
	 * Initialises the {@link OutputServer}: configures the database connection,
	 * optionally starts an embedded HSQLDB instance, loads any configured
	 * output/query handlers, binds the server socket, and starts the background
	 * request-processing thread.
	 *
	 * @param p configuration properties; recognised keys include {@code db.tables.output},
	 *          {@code db.driver}, {@code db.uri}, {@code db.user}, {@code db.password},
	 *          {@code db.test}, {@code db.handler}, and {@code db.server.port}
	 * @param s the FlowTree server to associate with this output server
	 * @throws IOException if the server socket cannot be bound
	 */
	public void init(Properties p, io.flowtree.Server s) throws IOException {
		this.nodeServer = s;

		outputTable = p.getProperty("db.tables.output", "output");
		String driver = p.getProperty("db.driver");
		String dburi = p.getProperty("db.uri");
		String dbuser = p.getProperty("db.user", "rings");
		String dbpasswd = p.getProperty("db.password", "rings");
		
		if (driver == null || dburi == null) {
			System.out.println("OutputServer: Driver and/or URI not specified, " +
								"starting HSQLDB...");
			
			String[] args = new String[4];
			args[0] = "-database.0";
			args[1] = "file:flowtreedb";
			args[2] = "-dbname.0";
			args[3] = "flowtree";
			
			System.out.println("OutputServer: HSQLDB file = " + args[1]);
			System.out.println("OutputServer: HSQLDB name = " + args[3]);
			
			Server.main(args);
			
			DatabaseConnection.bytea = DatabaseConnection.hsqldbBytea;
			driver = "org.hsqldb.jdbcDriver";
			dburi = "jdbc:hsqldb:hsql://localhost/flowtree";
			dbuser = "sa";
			dbpasswd = "";
		}
		
		this.testMode = (Boolean.valueOf(p.getProperty("db.test", "false"))).booleanValue();
		
		this.db = new DatabaseConnection(driver, dburi, dbuser,
											dbpasswd, outputTable, !this.testMode);
		
		if (driver != null && this.testMode)
			this.db.loadDriver(driver, dburi, dbuser, dbpasswd);
		
		String handler = p.getProperty("db.handler");
		
		if (handler != null) {
			try {
				Object h = Class.forName(handler).newInstance();
				
				if (h instanceof OutputHandler)
					this.db.addOutputHandler((OutputHandler) h);
				
				if (h instanceof QueryHandler)
					this.db.addQueryHandler((QueryHandler) h);
			} catch (InstantiationException e) {
				System.out.println("DBS: Error instantiating db handler (" + e.getMessage() + ")");
			} catch (IllegalAccessException e) {
				System.out.println("DBS: Error accessing db handler (" + e.getMessage() + ")");
			} catch (ClassNotFoundException e) {
				System.out.println("DBS: Could not find db handler (" + e.getMessage() + ")");
			}
		}
		
		int port = Integer.parseInt(p.getProperty("db.server.port", "7788"));
		
		this.socket = new ServerSocket(port);
		
		ThreadGroup g = null;
		if (nodeServer != null) g = nodeServer.getThreadGroup();

		// TODO This needs to be a pool of many threads
		Thread t = new Thread(g, this);
		t.setName("DB Server Thread");
		t.setPriority(io.flowtree.Server.HIGH_PRIORITY);
		t.start();
		
		this.setCurrentServer();
		System.out.println("Set current DBS: " + this);
	}
	
	/**
	 * Returns the name of the output table used by the underlying database
	 * connection.
	 *
	 * @return the table name string
	 */
	public String getTable() { return this.db.getTable(); }

	/**
	 * Registers this instance as the JVM-wide current {@link OutputServer}.
	 */
	public void setCurrentServer() { OutputServer.current = this; }

	/**
	 * Returns the JVM-wide current {@link OutputServer}, or {@code null} if
	 * none has been started.
	 *
	 * @return the current server instance
	 */
	public static OutputServer getCurrentServer() { return OutputServer.current; }

	/**
	 * Returns the name of the database output table, as configured from the
	 * {@code db.tables.output} property.
	 *
	 * @return the output table name
	 */
	public static String getOutputTable() { return outputTable; }

	/**
	 * Returns the FlowTree {@link io.flowtree.Server} that owns this output
	 * server.
	 *
	 * @return the associated node server
	 */
	public io.flowtree.Server getNodeServer() { return this.nodeServer; }

	/**
	 * Registers an {@link OutputHandler} with the underlying database connection
	 * so that it is notified when new job output is stored.
	 *
	 * @param handler the handler to add
	 */
	public void addOutputHandler(OutputHandler handler) {
		this.db.addOutputHandler(handler);
	}

	/**
	 * Delegates to {@link DatabaseConnection#storeOutput()} to notify all
	 * registered output handlers.
	 *
	 * @deprecated Use {@link #accept(org.almostrealism.io.JobOutput)} instead.
	 */
	@Deprecated
	public void storeOutput() { this.db.storeOutput(); }

	/**
	 * Delegates to {@link DatabaseConnection#storeOutput(java.util.Hashtable)}
	 * to pass the given result table to all registered output handlers.
	 *
	 * @param h result hashtable to pass to output handlers
	 * @deprecated Use {@link #accept(org.almostrealism.io.JobOutput)} instead.
	 */
	@Deprecated
	public void storeOutput(Hashtable h) { this.db.storeOutput(h); }

	/**
	 * Passes the given result map to all registered output handlers.
	 *
	 * @param h result map to pass to output handlers
	 * @deprecated Use {@link #accept(org.almostrealism.io.JobOutput)} instead.
	 */
	@Deprecated
	public void storeOutput(Map h) { this.db.storeOutput(h); }

	/**
	 * Removes the specified {@link OutputHandler} from the underlying database
	 * connection.
	 *
	 * @param handler the handler to remove
	 * @return {@code true} if the handler was found and removed
	 */
	public boolean removeHandler(OutputHandler handler) {
		return this.db.removeOutputHandler(handler);
	}

	/**
	 * Removes the specified {@link QueryHandler} from the underlying database
	 * connection.
	 *
	 * @param handler the handler to remove
	 * @return {@code true} if the handler was found and removed
	 */
	public boolean removeHandler(QueryHandler handler) {
		return this.db.removeQueryHandler(handler);
	}

	/**
	 * Returns the underlying {@link DatabaseConnection} for direct access to
	 * the local database.
	 *
	 * @return the database connection
	 */
	public DatabaseConnection getDatabaseConnection() { return this.db; }

	/**
	 * Returns the total average job processing time across all stored output
	 * records.
	 *
	 * @return total average job time in milliseconds
	 */
	public double getTotalAverageJobTime() { return this.db.getTotalAverageJobTime(); }

	/**
	 * Returns the total average throughput computed across all stored output.
	 *
	 * @return total average throughput value
	 */
	public double getTotalAverageThroughput() { return this.db.getTotalAverageThroughput(); }

	/**
	 * Returns the current throughput as tracked by the database connection.
	 *
	 * @return current throughput value
	 */
	public double getThroughput() { return this.db.getThroughput(); }

	/**
	 * Stores the given {@link JobOutput} in the local database without
	 * triggering output handlers, effectively persisting the result silently.
	 *
	 * @param output the job output to persist
	 */
	@Override
	public void accept(JobOutput output) {
		this.db.storeOutput(output, false);
	}

	/**
	 * Runs the database server accept loop. For each accepted socket connection
	 * the server reads the class name of an {@link java.io.Externalizable}
	 * object, deserialises it, and then:
	 * <ul>
	 *   <li>If the object is a {@link io.almostrealism.db.Query}, executes the
	 *       query and writes the result {@link java.util.Hashtable} back to the
	 *       client.</li>
	 *   <li>If the object is a {@link org.almostrealism.io.JobOutput}, stores
	 *       it in the local database.</li>
	 * </ul>
	 * EOF, class-not-found, and I/O errors are logged and the loop continues.
	 */
	@Override
	public void run() {
		while (true) {
			boolean done = false;
			
			try (Socket s = this.socket.accept();
				ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
				ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
				
				String type = in.readUTF();
				
				Object o = Class.forName(type).newInstance();
				if (o instanceof Externalizable)
					((Externalizable)o).readExternal(in);
				else
					System.out.println("DBS: Received class that is not externalizable.");
				
//				Object o = in.readObject();
				
				if (o instanceof Query) {
					Map h = this.db.executeQuery((Query)o);
					
					out.writeObject(h);
					out.flush();
				} else if (o instanceof JobOutput) {
					this.db.storeOutput((JobOutput)o);
				} else {
					System.out.println("DBS: Recieved " + o);
				}
				
				done = true;
			} catch (EOFException eof) {
				if (!done) System.out.println("DB Server: EOF Error (" + eof.getMessage() + ")");
			} catch (ClassNotFoundException cnf) {
				System.out.println("DB Server: Received an unknown class type.");
			} catch (IOException ioe) {
				if (!done) {
					System.out.println("DB Server: IO Error (" + ioe.getMessage() + ")");
					ioe.printStackTrace(System.out);
				}
			} catch (Exception e) {
				System.out.println("DB Server: " + e);
				e.printStackTrace();
			}
		}
	}
}
