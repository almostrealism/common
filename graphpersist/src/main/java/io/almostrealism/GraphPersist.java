package io.almostrealism;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.db.DatabaseConnection;
import io.almostrealism.db.Query;
import org.almostrealism.collect.PackedCollection;
import org.hsqldb.Server;

import java.util.Properties;

public class GraphPersist {
	private static Properties properties = new Properties();
	private static final GraphPersist local;

	static {
		String outputTable = properties.getProperty("db.tables.output", "output");
		String driver = properties.getProperty("db.driver");
		String dburi = properties.getProperty("db.uri");
		String dbuser = properties.getProperty("db.user", "root");
		String dbpasswd = properties.getProperty("db.password", "root");

		if (driver == null || dburi == null) {
			System.out.println("GraphPersist: Driver and/or URI not specified, " +
					"starting HSQLDB...");

			String[] args = new String[4];
			args[0] = "-database.0";
			args[1] = "file:graphpersistdb";
			args[2] = "-dbname.0";
			args[3] = "graphpersist";

			System.out.println("OutputServer: HSQLDB file = " + args[1]);
			System.out.println("OutputServer: HSQLDB name = " + args[3]);

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

	private final DatabaseConnection db;

	private GraphPersist(DatabaseConnection db) {
		this.db = db;
	}

	public void save(String key, PackedCollection value) {
		db.storeOutput(System.currentTimeMillis(), value.persist(), key, 0);
	}

	public PackedCollection read(String key, TraversalPolicy shape) {
		String table = db.getTable();
		Query q = new Query(table, DatabaseConnection.indexColumn,
				DatabaseConnection.dataColumn,
				DatabaseConnection.uriColumn + " = '" + key + "'");
		PackedCollection r = new PackedCollection(shape);
		r.read((byte[]) db.executeQuery(q).get("0"));
		return r;
	}

	public static GraphPersist local() {
		return local;
	}

	public static void loadProperties(Properties p) {
		properties = p;
	}
}
