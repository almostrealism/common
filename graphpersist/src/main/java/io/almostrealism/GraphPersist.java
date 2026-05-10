/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.db.DatabaseConnection;
import io.almostrealism.db.Query;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.hsqldb.Server;

import java.util.Properties;

/**
 * {@link GraphPersist} is the primary entry point for persisting and retrieving
 * {@link PackedCollection} data to and from a relational database.
 *
 * <p>On startup, it reads database configuration from system properties ({@code db.driver},
 * {@code db.uri}, {@code db.user}, {@code db.password}). If no driver or URI is configured,
 * an embedded HSQLDB instance is started automatically.</p>
 *
 * <p>The {@link #local()} method returns the singleton instance configured from the current
 * system properties. Use {@link #loadProperties(java.util.Properties)} before calling
 * {@link #local()} to configure a custom database.</p>
 *
 * <p>Data is stored as binary blobs in the output table of the configured database,
 * keyed by a URI string.</p>
 *
 * @author Michael Murray
 * @see DatabaseConnection
 */
public class GraphPersist {
	/** System properties used to configure the database connection and output table. */
	private static Properties properties = new Properties();
	/** The singleton GraphPersist instance configured from {@link #properties}. */
	private static final GraphPersist local;

	static {
		String outputTable = properties.getProperty("db.tables.output", "output");
		String driver = properties.getProperty("db.driver");
		String dburi = properties.getProperty("db.uri");
		String dbuser = properties.getProperty("db.user", "root");
		String dbpasswd = properties.getProperty("db.password", "root");

		if (driver == null || dburi == null) {
			Console.root().println("GraphPersist: Driver and/or URI not specified, " +
					"starting HSQLDB...");

			String[] args = new String[4];
			args[0] = "-database.0";
			args[1] = "file:graphpersistdb";
			args[2] = "-dbname.0";
			args[3] = "graphpersist";

			Console.root().println("OutputServer: HSQLDB file = " + args[1]);
			Console.root().println("OutputServer: HSQLDB name = " + args[3]);

			Server.main(args);

			DatabaseConnection.bytea = DatabaseConnection.hsqldbBytea;
			driver = "org.hsqldb.jdbcDriver";
			dburi = "jdbc:hsqldb:hsql://localhost/graphpersist";
			dbuser = "sa";
			dbpasswd = "";
		}

		boolean testMode = (Boolean.valueOf(properties.getProperty("db.test", "false"))).booleanValue();

		local = new GraphPersist(new DatabaseConnection(driver, dburi, dbuser,
									dbpasswd, outputTable, !testMode));
	}

	/** The database connection used for all persistence operations. */
	private final DatabaseConnection db;

	/**
	 * Constructs a {@link GraphPersist} instance backed by the given database connection.
	 *
	 * @param db The database connection to use for persistence
	 */
	private GraphPersist(DatabaseConnection db) {
		this.db = db;
	}

	/**
	 * Persists a {@link PackedCollection} to the database under the specified key.
	 *
	 * @param key   The URI key used to identify this collection in the database
	 * @param value The collection to persist
	 */
	public void save(String key, PackedCollection value) {
		db.storeOutput(System.currentTimeMillis(), value.persist(), key, 0);
	}

	/**
	 * Reads a {@link PackedCollection} from the database by its URI key.
	 *
	 * @param key   The URI key identifying the collection to retrieve
	 * @param shape The traversal policy (shape) to apply to the retrieved data
	 * @return The deserialized {@link PackedCollection}
	 */
	public PackedCollection read(String key, TraversalPolicy shape) {
		String table = db.getTable();
		Query q = new Query(table, DatabaseConnection.indexColumn,
				DatabaseConnection.dataColumn,
				DatabaseConnection.uriColumn + " = '" + key + "'");
		PackedCollection r = new PackedCollection(shape);
		r.read((byte[]) db.executeQuery(q).get("0"));
		return r;
	}

	/**
	 * Returns the singleton {@link GraphPersist} instance configured from system properties.
	 *
	 * @return The local GraphPersist instance
	 */
	public static GraphPersist local() {
		return local;
	}

	/**
	 * Overrides the system properties used to configure the database connection.
	 *
	 * <p>Must be called before the {@link #local} singleton is initialized.</p>
	 *
	 * @param p The properties containing database configuration
	 */
	public static void loadProperties(Properties p) {
		properties = p;
	}
}
