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

package io.almostrealism.db;

import io.almostrealism.db.Query.ResultHandler;
import org.almostrealism.io.JobOutput;
import org.almostrealism.io.OutputHandler;
import org.almostrealism.util.KeyValueStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * @author  Michael Murray
 */
public class DatabaseConnection {
	public static final String postgresBytea = "bytea";
	public static final String hsqldbBytea = "blob"; // "binary";
	public static String bytea = "bytea";

	public static final String usrColumn = "usr";
	
	public static final String toaColumn = "toa";
	public static final String dataColumn = "data";
	public static final String uriColumn = "uri";
	public static final String indexColumn = "ind";
	public static final String refColumn = "refs";
	public static final String dupColumn = "dups";
	public static final String uidColumn = "usrid";
	public static final String tidColumn = "tid";

	public static boolean verbose = false;
	
	protected class DefaultOutputHandler implements OutputHandler {
		private final String table;
		
		public DefaultOutputHandler(String table) { this.table = table; }

		@Override
		public void storeOutput(long time, int uid, JobOutput output) {
			try {
				synchronized (DatabaseConnection.this.storeOutput) {
					DatabaseConnection.this.storeOutput.setLong(1, time);
					DatabaseConnection.this.storeOutput.setInt(2, uid);
					DatabaseConnection.this.storeOutput.setString(3, output.getTaskId());
					DatabaseConnection.this.storeOutput.setBytes(4, output.encode().getBytes());
					DatabaseConnection.this.storeOutput.executeUpdate();
				}
			} catch (SQLException sqle) {
				System.out.println("DatabaseConnection.DefaultOutputHandler: SQL Error (" +
									sqle.getMessage() + ")");
			}
		}
	}
	
	protected class DefaultQueryHandler implements QueryHandler {
		@Override
		public Hashtable executeQuery(Query q) {
			if (DatabaseConnection.this.db == null) {
				System.out.println("DBS: Not connected.");
				return null;
			}
			
			Statement s = null;
			ResultSet r = null;
			
			ResultHandler handler = q.getResultHandler();
			
			try {
				String c0 = q.getColumn(0);
				String c1 = q.getColumn(1);
				String v0 = q.getValue(0);
//				String v1 = q.getValue(1);
				
				s = DatabaseConnection.this.db.createStatement();
				StringBuffer sql = new StringBuffer();
				sql.append("SELECT ");
				if (c0 != null) sql.append(c0);
				if (c0 != null && c1 != null) sql.append(",");
				if (c1 != null) sql.append(c1);
				sql.append(" FROM ");
				sql.append(q.getTable());
				
				String con = "";
				String qcon = q.getCondition();
				
				if (v0 != null) con = c0 + " = '" + v0 + "'";
				if (qcon != null && v0 != null) con = con + " AND ";
				if (qcon != null) con = con + qcon;
				
					
				if (con.length() > 0) {
					sql.append(" WHERE ");
					sql.append(con);
				}
				
				r = s.executeQuery(sql.toString());
				
				if (DatabaseConnection.verbose)
					System.out.println("DBS DefaultOutputHandler: Executed SQL -- " + sql);
				
				Hashtable h = new Hashtable();
				
				int i = 0;
				
				Object key = null;
				Object value = null;
				
				w: while(r.next()) {
					if (c1 == null) {
						key = Integer.valueOf(i++);
						
						if (c0.equals(DatabaseConnection.dataColumn))
							value = r.getBytes(c0);
						else
							value = r.getString(c0);
					} else {
						key = r.getString(c0);
						
						if (c1.equals(DatabaseConnection.dataColumn))
							value = r.getBytes(c1);
						else
							value = r.getString(c1);
					}
					
					if (key == null) continue w;
					
					if (handler == null && value != null) {
						h.put(key, value);
					} else if (value instanceof byte[]){
						handler.handleResult(key.toString(), (byte[]) value);
					} else if (handler != null) {
						handler.handleResult(key.toString(), (String) value);
					}
				}
				
				if (DatabaseConnection.verbose && handler == null)
					System.out.println("DBS DefaultOutputHandler: Query returned " +
										h.size() + " entries.");
				
				return h;
			} catch (SQLException sqle) {
				System.out.println("DatabaseConnection.DefaultQueryHandler: " + sqle);
				return null;
			} finally {
				try {
					if (r != null) r.close();
					if (s != null) s.close();
				} catch (Exception e) { e.printStackTrace(); }
			}
		}
	}
	
	private final String userTable = "users";
	private final String outputTable;
	private Connection db;
	private PreparedStatement selectAll, binaryInsert, storeOutput, selectUser,
								deleteUri, deleteIndex, deleteToa, updateDup, configJob;
	
	private final Set<OutputHandler> outputHandlers;
	private final Set<QueryHandler> queryHandlers;
	private int totalRecieved, currentRecieved;
	private long totalJobTime;
	private long lastChecked, firstRecieved;
	
	/**
	 * Constructs a new {@link DatabaseConnection} that can be used to store output and execute queries.
	 * 
	 * @param driver  JDBC driver class name.
	 * @param dburi  URI to database.
	 * @param dbuser  Database user.
	 * @param dbpasswd  Database password.
	 * @param outputTable  Name of output table.
	 */
	public DatabaseConnection(String driver, String dburi, String dbuser, String dbpasswd, String outputTable) {
		this(driver, dburi, dbuser, dbpasswd, outputTable, true);
	}
	
	/**
	 * Constructs  a new DatabaseConnection object that can be used to store output and execute queries.
	 * 
	 * @param driver  JDBC driver class name.
	 * @param dburi  URI to database.
	 * @param dbuser  Database user.
	 * @param dbpasswd  Database password.
	 * @param outputTable  Name of output table.
	 * @param useDb  True if a default output handler should be added that stores output in output table.
	 */
	public DatabaseConnection(String driver, String dburi, String dbuser, String dbpasswd, String outputTable, boolean useDb) {
		this.outputHandlers = new HashSet();
		this.queryHandlers = new HashSet();
		
		this.outputTable = outputTable;
		
		this.lastChecked = System.currentTimeMillis();
		
		if (useDb) {
			this.addOutputHandler(new DefaultOutputHandler(outputTable));
			this.addQueryHandler(new DefaultQueryHandler());
			this.loadDriver(driver, dburi, dbuser, dbpasswd);
		}
	}
	
	public void loadDriver(String driver, String dburi, String dbuser, String dbpasswd) {
		try {
			System.out.print("DBS: Loading " + driver + "... ");
			Class.forName(driver);
			System.out.println("Done");
			
			System.out.print("DBS: Opening " + dburi + " as user " + dbuser + "... ");
			this.db = DriverManager.getConnection(dburi, dbuser, dbpasswd);
			System.out.println(" Connected");
			
			try {
				System.out.println("DatabaseConnection: Creating table...");
				this.createOutputTable();
			} catch (SQLException sqle) {
				System.out.println("DatabaseConnection: " + sqle.getMessage());
			}
			
			System.out.print("DBS: Preparing statements... ");
			
			StringBuffer buf = new StringBuffer();
			buf.append("SELECT * FROM ");
			buf.append(this.outputTable);
			this.selectAll = this.db.prepareStatement(buf.toString());
			
			buf = new StringBuffer();
			buf.append("INSERT INTO ");
			buf.append(this.outputTable);
			buf.append(" (");
			buf.append(DatabaseConnection.toaColumn);
			buf.append(",");
			buf.append(DatabaseConnection.dataColumn);
			buf.append(", ");
			buf.append(DatabaseConnection.uriColumn);
			buf.append(", ");
			buf.append(DatabaseConnection.indexColumn);
			buf.append(") VALUES (?, ?, ?, ?)");
			this.binaryInsert = this.db.prepareStatement(buf.toString());
			
			buf = new StringBuffer();
			buf.append("INSERT INTO ");
			buf.append(this.outputTable);
			buf.append(" (");
			buf.append(DatabaseConnection.toaColumn);
			buf.append(", ");
			buf.append(DatabaseConnection.uidColumn);
			buf.append(", ");
			buf.append(DatabaseConnection.tidColumn);
			buf.append(", ");
			buf.append(DatabaseConnection.dataColumn);
			buf.append(") VALUES (?, ?, ?, ?)");
			this.storeOutput = this.db.prepareStatement(buf.toString());
			
			buf = new StringBuffer();
			buf.append("DELETE FROM ");
			buf.append(this.outputTable);
			buf.append(" WHERE ");
			buf.append(DatabaseConnection.toaColumn);
			buf.append(" = ?");
			this.deleteToa = this.db.prepareStatement(buf.toString());
			
			buf = new StringBuffer();
			buf.append("DELETE FROM ");
			buf.append(this.outputTable);
			buf.append(" WHERE ");
			buf.append(DatabaseConnection.uriColumn);
			buf.append(" = ? AND ");
			buf.append(DatabaseConnection.indexColumn);
			buf.append(" = ?");
			this.deleteIndex = this.db.prepareStatement(buf.toString());
			
			buf = new StringBuffer();
			buf.append("DELETE FROM ");
			buf.append(this.outputTable);
			buf.append(" WHERE ");
			buf.append(DatabaseConnection.uriColumn);
			buf.append(" = ?");
			this.deleteUri = this.db.prepareStatement(buf.toString());
			
			buf = new StringBuffer();
			buf.append("UPDATE ");
			buf.append(this.outputTable);
			buf.append(" SET ");
			buf.append(DatabaseConnection.dupColumn);
			buf.append(" = ? ");
			buf.append(" WHERE ");
			buf.append(DatabaseConnection.toaColumn);
			buf.append(" = ?");
			this.updateDup = this.db.prepareStatement(buf.toString());
			
			buf = new StringBuffer();
			buf.append("SELECT ");
			buf.append(DatabaseConnection.uriColumn);
			buf.append(",");
			buf.append(DatabaseConnection.indexColumn);
			buf.append(",");
			buf.append(DatabaseConnection.dataColumn);
			buf.append(" FROM ");
			buf.append(this.outputTable);
			buf.append(" WHERE ");
			buf.append(DatabaseConnection.toaColumn);
			buf.append(" = ?");
			this.configJob = this.db.prepareStatement(buf.toString());
			
			try {
				buf = new StringBuffer();
				buf.append("SELECT * FROM ");
				buf.append(this.userTable);
				buf.append(" WHERE ");
				buf.append(DatabaseConnection.usrColumn);
				buf.append(" = ?");
				this.selectUser = this.db.prepareStatement(buf.toString());
			} catch (SQLException sqle) {
				System.out.println("\nDatabaseConnection: " + sqle);
			}
			
			System.out.println(" Done");
		} catch (ClassNotFoundException cnf) {
			System.out.println("\nDatabaseConnection: JDBC driver not found");
		} catch (SQLException sqle) {
			System.out.println("\nDatabaseConnection: " + sqle);
		}
	}
	
	public String getTable() { return this.outputTable; }
	
	/**
	 * Adds an output handler that will be notified when output is to be stored.
	 * 
	 * @param h  OutputHandler implementation to use.
	 */
	public void addOutputHandler(OutputHandler h) { this.outputHandlers.add(h); }
	
	/**
	 * Adds a query handler that will be notified when a query is submitted.
	 * 
	 * @param h  QueryHandler implementation to use.
	 */
	public void addQueryHandler(QueryHandler h) { this.queryHandlers.add(h); }
	
	/**
	 * Removes the specified output handler from this DatabaseConnection object.
	 * 
	 * @param h  The OutputHandler object to remove.
	 */
	public boolean removeOutputHandler(OutputHandler h) { return this.outputHandlers.remove(h); }
	
	/**
	 * Removes the specified query handler from this DatabaseConnection object.
	 * 
	 * @param h  The QueryHandler object to remove.
	 */
	public boolean removeQueryHandler(QueryHandler h) { return this.queryHandlers.remove(h); }
	
	public boolean createOutputTable() throws SQLException {
		try (Statement s = this.db.createStatement()) {
			s.executeUpdate("CREATE TABLE " + this.outputTable +
							" (" + DatabaseConnection.toaColumn +
							" numeric(15,0), " +
							DatabaseConnection.uidColumn +
							" numeric(8,0), " +
							DatabaseConnection.tidColumn +
							" varchar(64), " +
							DatabaseConnection.dataColumn +
							" " + bytea + ", " +
							DatabaseConnection.uriColumn +
							" varchar(10000), " +
							DatabaseConnection.indexColumn +
							" numeric(8,0), " +
							DatabaseConnection.refColumn +
							" numeric(8,0), " +
							DatabaseConnection.dupColumn +
							" numeric(8,0))");
		}
		
		return true;
	}
	
	public boolean createUserTable() throws SQLException {
		// TODO  Add createUserTable
		
//		Statement s = this.db.createStatement();
//		s.executeUpdate("CREATE TABLE " + this.userTable +
//						" (" + DatabaseConnection.
		return false;
	}
	
	/**
	 * Queries the output table and notifies all output handlers of the output
	 * that is stored in these rows.
	 */
	// TODO It is unclear if this method is a viable way to do anything at scale
	@Deprecated
	public void storeOutput() {
		try {
			ResultSet r;
			
			synchronized (this.selectAll) {
				r = this.selectAll.executeQuery();
			}
			
			while(r.next()) {
				long time = r.getLong(toaColumn);
				int uid = r.getInt(uidColumn);
				String tid = r.getString(tidColumn);
				String output = r.getString(DatabaseConnection.dataColumn);
				this.storeOutput(time, uid, tid, output);
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConnection: " + sqle);
		}
	}

	// TODO  It is unclear what this is for
	@Deprecated
	public void storeOutput(Hashtable h) {
		Iterator itr = h.entrySet().iterator();
		
		while (itr.hasNext()) {
			Map.Entry ent = (Map.Entry) itr.next();
			this.storeOutput(Long.parseLong((String)ent.getKey()), -1, null,
							(String)ent.getValue());
		}
	}
	
	/**
	 * Notifies all output handlers stored by this DatabaseConnection object to store
	 * the specified output string. This method does not check the users table for
	 * correct username and password.
	 * 
	 * @param time  Time in milliseconds at the time the output was recieved.
	 * @param uid  The user id (from the users table) for the user who submitted the output.
	 * @param tid  The task id for the task associated with the job that generated the output.
	 * @param output  The string output.
	 */
	public void storeOutput(long time, int uid, String tid, String output) {
		JobOutput o = JobOutput.decode(output);
		
		if (this.firstRecieved == 0) {
			this.firstRecieved = System.currentTimeMillis();
			this.totalJobTime = o.getTime();
		} else if (this.totalJobTime < 0) {
			this.totalJobTime = o.getTime();
			this.totalRecieved = 0;
		} else {
			this.totalJobTime = this.totalJobTime + o.getTime();
		}
		
		this.totalRecieved++;
		this.currentRecieved++;

		outputHandlers.forEach(oh -> oh.storeOutput(time, uid, o));
	}
	
	public boolean storeOutput(long time, byte[] data, String uri, int index) {
		if (this.binaryInsert == null) return false;
		
		try {
			synchronized (this.binaryInsert) {
				this.binaryInsert.setLong(1, time);
				this.binaryInsert.setBytes(2, data);
				this.binaryInsert.setString(3, uri);
				this.binaryInsert.setInt(4, index);
				this.binaryInsert.executeUpdate();
				
				if (verbose)
					System.out.println("DatabaseConnection: Executed binary insert.");
				
				return true;
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConnection.DefaultOutputHandler: SQL Error (" +
								sqle.getMessage() + ")");
		}
		
		return false;
	}


	/**
	 * Stores the output from the specified {@link JobOutput}. This involves
	 * checking username and password with the users table and then notifying
	 * all output handlers that are stored by this {@link DatabaseConnection}.
	 *
	 * @param o  {@link JobOutput} to store.
	 */
	public void storeOutput(JobOutput o) {
		storeOutput(o, true);
	}

	/**
	 * Stores the output from the specified {@link JobOutput}. If the auth check
	 * is enabled, this involves checking username and password with the users
	 * table. All output handlers that are stored by this {@link DatabaseConnection}
	 * will be notified.
	 * 
	 * @param o  {@link JobOutput} to store.
	 */
	public void storeOutput(JobOutput o, boolean authCheck) {
		try {
			boolean accepted = false;
			
			int id = -1;
			long t = System.currentTimeMillis();
			// String output = o.getOutput();
			
			if (this.db != null && authCheck) {
				ResultSet r1;
				
				synchronized (this.selectUser) {
					this.selectUser.setString(1, o.getUser());
					r1 = this.selectUser.executeQuery();
				}
				
				if (!r1.next()) return;
				String passwd = r1.getString("passwd");
				id = r1.getInt("uid");
				
				if (o.getPassword().equals(passwd)) accepted = true;
			} else {
				accepted = true;
			}
			
			if (accepted) {
				if (this.firstRecieved == 0) {
					this.firstRecieved = System.currentTimeMillis();
					this.totalJobTime = o.getTime();
				} else if (this.totalJobTime < 0) {
					this.totalJobTime = o.getTime();
					this.totalRecieved = 0;
				} else {
					this.totalJobTime = this.totalJobTime + o.getTime();
				}
				
				this.totalRecieved++;
				this.currentRecieved++;

				int fid = id;
				outputHandlers.forEach(oh -> oh.storeOutput(t, fid, o));
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConnection: " + sqle);
		}
	}
	
	/**
	 * Executes a query and returns the result in the form of a hashtable.
	 * 
	 * @param q  A Query object defining what exactly is to be returned from the database.
	 * @return  A Hashtable object storing key value pairs returned by the query.
	 */
	public Hashtable executeQuery(Query q) {
		if (DatabaseConnection.verbose) {
			System.out.println("DatabaseConnection: Executing " + q);
		}
		
		Hashtable result = new Hashtable();
		queryHandlers.stream().map(h -> h.executeQuery(q)).forEach(result::putAll);
		return result;
	}
	
	public void updateDuplication(long toa, int dup) {
		if (DatabaseConnection.verbose) {
			System.out.println("DatabaseConnection (" + this.outputTable +
								"): Updating duplication to " + dup + ". ");
		}
		
		try {
			synchronized (this.updateDup) {
				this.updateDup.setInt(1, dup);
				this.updateDup.setLong(2, toa);
				this.updateDup.executeUpdate();
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConenction(" + this.outputTable +
								"): Error executing update (" +
								sqle.getMessage() + ")");
		}
	}
	
	public boolean configureProperties(KeyValueStore j, long toa) {
		try {
			synchronized (this.configJob) {
				this.configJob.setLong(1, toa);
				if (DatabaseConnection.verbose)
					System.out.println("DatabaseConnection (" + this.outputTable +
										"): Executing config job SQL (" + toa + ")");
				
				ResultSet s = this.configJob.executeQuery();
				
				if (s.next()) {
					j.set("uri", s.getString(DatabaseConnection.uriColumn));
					j.set("i", String.valueOf(s.getInt(DatabaseConnection.indexColumn)));
					j.set("data", new String(s.getBytes(DatabaseConnection.dataColumn)));
					
					return true;
				} else {
					return false;
				}
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConenction(" + this.outputTable +
								"): Error configuring job (" +
								sqle.getMessage() + ")");
			return false;
		}
	}
	
	public void deleteUri(String uri) {
		try {
			synchronized (this.deleteUri) {
				if (DatabaseConnection.verbose)
					System.out.println("DatabaseConnection (" + this.outputTable +
										"): Executing delete uri SQL (" + uri + ")");
				
				this.deleteUri.setString(1, uri);
				this.deleteUri.executeUpdate();
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConnection(" + this.outputTable +
								"): Error deleting " + uri + " (" +
								sqle.getMessage() + ")");
		}
	}
	
	public boolean deleteIndex(String uri, int index) {
		if (this.deleteIndex == null) return false;
		
		try {
			synchronized (this.deleteIndex) {
				if (DatabaseConnection.verbose)
					System.out.println("DatabaseConnection (" + this.outputTable +
										"): Executing delete uri SQL (" + uri + "  " +
										index + ")");
				
				this.deleteIndex.setString(1, uri);
				this.deleteIndex.setInt(2, index);
				this.deleteIndex.executeUpdate();
				return true;
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConnection(" + this.outputTable +
								"): Error deleting " + uri + "  " + index +
								" (" + sqle.getMessage() + ")");
		}
		
		return false;
	}
	
	public void deleteToa(long toa) {
		try {
			synchronized (this.deleteToa) {
				if (DatabaseConnection.verbose)
					System.out.println("DatabaseConnection (" + this.outputTable +
										"): Executing delete toa SQL (" + toa + ")");
				
				this.deleteToa.setLong(1, toa);
				this.deleteToa.executeUpdate();
			}
		} catch (SQLException sqle) {
			System.out.println("DatabaseConnection(" + this.outputTable +
								"): Error deleting " + toa + " (" +
								sqle.getMessage() + ")");
		}
	}
	
	public static String prepareString(String s) {
		int index = 0;
		
		while ((index = s.indexOf("'", index)) > 0) {
			s = s.substring(0, index) + "'" + s.substring(index);
			index += 2;
		}
		
		return s;
	}
	
	public double getTotalAverageJobTime() {
		return this.totalJobTime / ((double)this.totalRecieved);
	}
	
	public double getTotalAverageThroughput() {
		long time = System.currentTimeMillis();
		double d = (time - this.firstRecieved) / 60000.0;
		double t = this.totalRecieved / d;
		
		return t;
	}
	
	/**
	 * @return  The average number of JobOutput objects handled per minute since
	 *          the last time this method was called.
	 */
	public double getThroughput() {
		long time = System.currentTimeMillis();
		double d = (time - this.lastChecked) / 60000.0;
		double t = this.currentRecieved / d;
		
		this.currentRecieved = 0;
		this.lastChecked = time;
		
		return t;
	}
}
