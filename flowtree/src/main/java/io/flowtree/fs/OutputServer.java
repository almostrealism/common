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
import java.util.Properties;
import java.util.function.Consumer;

/**
 * @author  Michael Murray
 */
public class OutputServer implements Runnable, Consumer<JobOutput> {
	private static String outputTable;

	private static OutputServer current;

	private io.flowtree.Server nodeServer;
	
	private boolean testMode;
	private ServerSocket socket;
	private DatabaseConnection db;
	
	public static void main(String[] args) {
		try {
			Properties p = new Properties();
			p.load(new FileInputStream(args[0]));
			
			OutputServer s = new OutputServer(p);
			s.setCurrentServer();
			
			System.out.println("\nDB Server started");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public OutputServer() { }
	public OutputServer(Properties p) throws IOException { init(p, Client.getCurrentClient().getServer()); }
	public OutputServer(Properties p, io.flowtree.Server s) throws IOException { init(p, s); }
	
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
	
	public String getTable() { return this.db.getTable(); }
	
	public void setCurrentServer() { OutputServer.current = this; }
	
	public static OutputServer getCurrentServer() { return OutputServer.current; }

	public static String getOutputTable() { return outputTable; }

	public io.flowtree.Server getNodeServer() { return this.nodeServer; }

	public void addOutputHandler(OutputHandler handler) {
		this.db.addOutputHandler(handler);
	}

	@Deprecated
	public void storeOutput() { this.db.storeOutput(); }

	@Deprecated
	public void storeOutput(Hashtable h) { this.db.storeOutput(h); }
	
	public boolean removeHandler(OutputHandler handler) {
		return this.db.removeOutputHandler(handler);
	}
	
	public boolean removeHandler(QueryHandler handler) {
		return this.db.removeQueryHandler(handler);
	}
	
	public DatabaseConnection getDatabaseConnection() { return this.db; }
	
	public double getTotalAverageJobTime() { return this.db.getTotalAverageJobTime(); }
	
	public double getTotalAverageThroughput() { return this.db.getTotalAverageThroughput(); }
	
	public double getThroughput() { return this.db.getThroughput(); } 

	@Override
	public void accept(JobOutput output) {
		this.db.storeOutput(output, false);
	}

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
					Hashtable h = this.db.executeQuery((Query)o);
					
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
