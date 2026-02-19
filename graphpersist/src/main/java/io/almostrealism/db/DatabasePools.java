package io.almostrealism.db;

import com.mchange.v2.c3p0.ComboPooledDataSource;

import java.beans.PropertyVetoException;
import java.util.HashMap;
import java.util.Set;

/** The DatabasePools class. */
public class DatabasePools {
	private static final HashMap<String, ComboPooledDataSource> pools =
						new HashMap<>();

	/** Performs the open operation. */
	public static boolean open(String url, String user, String password) {
		ComboPooledDataSource pool = new ComboPooledDataSource();

		try {
			pool.setDriverClass("com.mysql.jdbc.Driver");
		} catch (PropertyVetoException e) {
			e.printStackTrace();
		}

		pool.setJdbcUrl(url);
		pool.setUser(user);
		pool.setPassword(password);
		pools.put(url, pool);

		return true;
	}

	/** Performs the get operation. */
	public static ComboPooledDataSource get(String name) {
		return pools.get(name);
	}

	/** Performs the keys operation. */
	public static Set<String> keys() {
		return pools.keySet();
	}
}
