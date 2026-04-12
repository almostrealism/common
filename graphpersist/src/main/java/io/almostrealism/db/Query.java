/*
 * Copyright 2017 Michael Murray
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

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Query object represents a database query. The default values for the parameters
 * are "output" for table name, {@link DatabaseConnection#toaColumn} for column one
 * (the key column in the returned {@link Hashtable}), {@link DatabaseConnection#dataColumn}
 * for column two (the value column in the returned {@link Hashtable}, and "true" for
 * condition.
 * 
 * @author  Michael Murray
 */
// TODO  Should extend io.almostrealism.query.Query
public class Query implements Externalizable {
	/** When {@code true}, logs each serialization and deserialization to standard output. */
	public static boolean verbose = false;

	/**
	 * Callback interface for receiving individual key-value results from a database query.
	 */
	public interface ResultHandler {
		/**
		 * Called for each row where the value column contains a {@link String}.
		 *
		 * @param key   The key column value
		 * @param value The string value column value
		 */
		void handleResult(String key, String value);

		/**
		 * Called for each row where the value column contains binary data.
		 *
		 * @param key   The key column value
		 * @param value The binary value column value
		 */
		void handleResult(String key, byte[] value);
	}

	/** Separator character used by {@link #toString(Hashtable)} and {@link #fromString(String)}. */
	public static final String sep = "%";
	/** The table to query. */
	private String table;
	/** The key column name. */
	private String col1;
	/** The value column name. */
	private String col2;
	/** Optional filter value for the key column. */
	private String val1;
	/** Optional filter value for the value column. */
	private String val2;
	/** The SQL WHERE condition. */
	private String con;
	/** The relay hop count for distributed query routing. */
	private int relay;

	/** Optional result handler invoked for each row returned by the query. */
	private ResultHandler handler;
	
	/**
	 * Constructs a new {@link Query} using defaults for all parameters.
	 */
	public Query() {
		this.table = "output";
		this.col1 = DatabaseConnection.toaColumn;
		this.col2 = DatabaseConnection.dataColumn;
		this.con = "true";
	}
	
	/**
	 * Constructs a new {@link Query} using the specified table and defaults
	 * for all other parameters.
	 * 
	 * @param table  Name of table to query.
	 */
	public Query(String table) {
		this.table = table;
		this.col1 = DatabaseConnection.toaColumn;
		this.col2 = DatabaseConnection.dataColumn;
		this.con = "true";
	}
	
	/**
	 * Constructs a new {@link Query} using the specified table and condition
	 * and defaults for all other parameters.
	 * 
	 * @param table  Name of table to query.
	 * @param con  Condition for query.
	 */
	public Query(String table, String con) {
		this.table = table;
		this.col1 = DatabaseConnection.toaColumn;
		this.col2 = DatabaseConnection.dataColumn;
		this.con = con;
	}
	
	/**
	 * Constructs a new {@link Query} using the specified parameters.
	 * 
	 * @param table  Name of the table for this query.
	 * @param col1  Name of the key column for this query.
	 * @param col2  Name of the value column for this query.
	 */
	public Query(String table, String col1, String col2) {
		this.table = table;
		this.col1 = col1;
		this.col2 = col2;
		this.con = null;
	}
	
	/**
	 * Constructs a new Query object using the specified paramaters.
	 * 
	 * @param table  Name of the table for this query.
	 * @param col1  Name of the key column for this query.
	 * @param col2  Name of the value column for this query.
	 * @param con  SQL condition for this query.
	 */
	public Query(String table, String col1, String col2, String con) {
		this.table = table;
		this.col1 = col1;
		this.col2 = col2;
		this.con = con;
	}
	
	/**
	 * Sets the result handler that will receive each row returned by this query.
	 *
	 * @param h The result handler to use, or {@code null} to remove the current handler
	 */
	public void setResultHandler(ResultHandler h) { this.handler = h; }

	/**
	 * Returns the result handler registered for this query.
	 *
	 * @return The current result handler, or {@code null} if none is set
	 */
	public ResultHandler getResultHandler() { return this.handler; }
	
	/**
	 * Returns the name of the table for this query.
	 *
	 * @return The table name
	 */
	public String getTable() { return this.table; }

	/**
	 * Returns the name of the column at the specified index for this query.
	 *
	 * @param i Column index: 0 for the key column, any other value for the value column
	 * @return The column name at the given index
	 */
	public String getColumn(int i) {
		if (i == 0) {
			return this.col1;
		} else {
			return this.col2;
		}
	}
	
	/**
	 * Sets the name of the specified column (0 for key, 1 for value).
	 *
	 * @param i     Column index: 0 for the key column, any other value for the value column
	 * @param value The column name to set
	 */
	public void setColumn(int i, String value) {
		if (i == 0)
			this.col1 = value;
		else
			this.col2 = value;
	}
	
	/**
	 * Sets a filter value for the specified column (0 for key column, 1 for value column).
	 *
	 * @param i     Column index: 0 for the key column, any other value for the value column
	 * @param value The filter value to set
	 */
	public void setValue(int i, String value) {
		if (i == 0)
			this.val1 = value;
		else
			this.val2 = value;
	}
	
	/**
	 * Returns the filter value for the specified column.
	 *
	 * @param i Column index: 0 for the key column, any other value for the value column
	 * @return The filter value for that column, or {@code null} if not set
	 */
	public String getValue(int i) {
		if (i == 0)
			return this.val1;
		else
			return this.val2;
	}
	
	/**
	 * Returns the SQL condition for this query.
	 *
	 * @return The SQL WHERE condition string
	 */
	public String getCondition() { return this.con; }
	
	/**
	 * Serializes the query fields to a compact byte array for network transmission.
	 * The first 5 bytes encode the lengths of the four string fields and the relay count.
	 *
	 * @return A byte array encoding this query's table, columns, condition, and relay count
	 */
	public byte[] getBytes() {
		byte[] t = this.table.getBytes();
		byte[] cl1 = this.col1.getBytes();
		byte[] cl2 = this.col2.getBytes();
		byte[] cn = this.con.getBytes();
		
		byte[] data = new byte[t.length + cl1.length + cl2.length + cn.length + 5];
		
		data[0] = (byte) t.length;
		data[1] = (byte) cl1.length;
		data[2] = (byte) cl2.length;
		data[3] = (byte) cn.length;
		data[4] = (byte) this.relay;
		
		int index = 5;
		
		System.arraycopy(t, 0, data, index, t.length);
		index = index + t.length;
		System.arraycopy(cl1, 0, data, index, cl1.length);
		index = index + cl1.length;
		System.arraycopy(cl2, 0, data, index, cl2.length);
		index = index + cl2.length;
		System.arraycopy(cn, 0, data, index, cn.length);
		
		if (verbose)
			System.out.println("Write " + data.length + " bytes " + this);
		
		return data;
	}
	
	/**
	 * Deserializes query fields from a byte array previously produced by {@link #getBytes()}.
	 *
	 * @param b The byte array to parse
	 */
	public void setBytes(byte[] b) {
		this.relay = b[4];
		int index = 5;
		this.table = new String(b, index, b[0]);
		index = index + b[0];
		this.col1 = new String(b, index, b[1]);
		index = index + b[1];
		this.col2 = new String(b, index, b[2]);
		index = index + b[2];
		this.con = new String(b, index, b[3]);
		index = index + b[3];
		
		if (index < b.length)
			System.out.println("Query: " + (b.length - index) + " extra bytes (" +
								b[0] + ", " + b[1] + ", " + b[2] + ", " + b[3] + ").");
		
		if (verbose)
			System.out.println("Read " + b.length + " bytes " + this);
	}
	
	/**
	 * Sets the relay hop count for distributed routing of this query.
	 *
	 * @param r The relay count to set
	 */
	public void setRelay(int r) { this.relay = r; }

	/**
	 * Returns the relay hop count for this query.
	 *
	 * @return The relay count
	 */
	public int getRelay() { return this.relay; }

	/**
	 * Decrements the relay hop count by one and returns the new value.
	 *
	 * @return The decremented relay count
	 */
	public int deincrementRelay() { return --this.relay; }
	
	
	/**
	 * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
	 */
	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		if (verbose) System.out.println("Write " + this);
		
		out.writeInt(this.relay);
		out.writeUTF(this.table);
		out.writeUTF(this.col1);
		out.writeUTF(this.col2);
		out.writeUTF(this.con);
	}

	/**
	 * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
	 */
	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		this.relay = in.readInt();
		this.table = in.readUTF();
		this.col1 = in.readUTF();
		this.col2 = in.readUTF();
		this.con = in.readUTF();
		
		if (verbose) System.out.println("Read " + this);
	}

	@Override
	public String toString() {
		return "Query[" + this.table + ", " + this.col1 + ", " +
						this.col2 + ", " + this.con + ", " + this.relay + "]";
	}
	
	/**
	 * Serializes a map of query results to a {@link #sep}-delimited string
	 * of alternating key-value pairs.
	 *
	 * @param h the map to serialize
	 * @return A delimited string encoding the entries, or an empty string if the map is empty
	 */
	public static String toString(Map h) {
		if (h.size() <= 0) return "";
		
		StringBuilder b = new StringBuilder();
		Iterator itr = h.entrySet().iterator();
		
		Map.Entry ent = (Map.Entry) itr.next();
		b.append(convertToString(ent.getKey()));
		b.append(Query.sep);
		b.append(convertToString(ent.getValue()));
		
		while (itr.hasNext()) {
			ent = (Map.Entry) itr.next();
			b.append(Query.sep);
			b.append(convertToString(ent.getKey()));
			b.append(Query.sep);
			b.append(convertToString(ent.getValue()));
		}
		
		return b.toString();
	}

	/**
	 * Converts an object to its string representation, handling {@code byte[]} by
	 * constructing a new {@link String} from the bytes.
	 *
	 * @param o The object to convert
	 * @return The string representation of {@code o}
	 */
	private static String convertToString(Object o) {
		if (o instanceof byte[]) {
			return new String((byte[]) o);
		} else {
			return o.toString();
		}
	}

	/**
	 * Deserializes a {@link #sep}-delimited string of alternating key-value pairs into
	 * a new {@link LinkedHashMap}.
	 *
	 * @param data The delimited string to parse
	 * @return A hashtable containing the parsed key-value pairs
	 */
	public static LinkedHashMap<String, String> fromString(String data) {
		LinkedHashMap<String, String> h = new LinkedHashMap<>();
		fromString(data, h);
		return h;
	}

	/**
	 * Deserializes a {@link #sep}-delimited string of alternating key-value pairs into
	 * the supplied map.
	 *
	 * @param data The delimited string to parse
	 * @param h    The map to populate with the parsed key-value pairs
	 */
	public static void fromString(String data, Map<String, String> h) {
		int index = data.indexOf(Query.sep);
		String s = data;
		
		List<String> l = new ArrayList();
		w: while (true) {
			if (index < 0) {
				l.add(s);
				break;
			} else {
				l.add(s.substring(0, index));
			}
			
			s = s.substring(index + 1);
			index = s.indexOf(Query.sep);
		}
		
		Iterator<String> itr = l.iterator();
		w: while (itr.hasNext()) {
			String key = itr.next();
			if (!itr.hasNext()) break;
			h.put(key, itr.next());
		}
	}
}
