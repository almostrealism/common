package io.almostrealism.db;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import org.almostrealism.io.Console;

import java.beans.PropertyVetoException;
import java.util.HashMap;
import java.util.Set;

/**
 * Registry of c3p0 connection pools keyed by JDBC URL.
 *
 * <p>Call {@link #open(String, String, String)} to register a new pool for a MySQL URL,
 * then {@link #get(String)} to retrieve the pool for use with JDBC. Each URL maps to at
 * most one pool.</p>
 */
public class DatabasePools {
	/** Map from JDBC URL to the corresponding c3p0 connection pool. */
	private static final HashMap<String, ComboPooledDataSource> pools =
						new HashMap<>();

	/**
	 * Opens a new c3p0 connection pool for the given MySQL JDBC URL and registers it
	 * in the pool map.
	 *
	 * @param url      The JDBC URL of the MySQL database
	 * @param user     The database user name
	 * @param password The database password
	 * @return {@code true} if the pool was registered (always returns {@code true})
	 */
	public static boolean open(String url, String user, String password) {
		ComboPooledDataSource pool = new ComboPooledDataSource();

		try {
			pool.setDriverClass("com.mysql.jdbc.Driver");
		} catch (PropertyVetoException e) {
			Console.root().warn("DatabasePools: Failed to set driver class: " + e.getMessage(), null);
		}

		pool.setJdbcUrl(url);
		pool.setUser(user);
		pool.setPassword(password);
		pools.put(url, pool);

		return true;
	}

	/**
	 * Returns the connection pool registered for the given JDBC URL.
	 *
	 * @param name The JDBC URL used as the pool key
	 * @return The {@link ComboPooledDataSource} for that URL, or {@code null} if none
	 */
	public static ComboPooledDataSource get(String name) {
		return pools.get(name);
	}

	/**
	 * Returns the set of JDBC URLs for which pools have been registered.
	 *
	 * @return The set of registered pool keys
	 */
	public static Set<String> keys() {
		return pools.keySet();
	}
}
