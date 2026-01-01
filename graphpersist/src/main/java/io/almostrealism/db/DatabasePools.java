package io.almostrealism.db;

import com.mchange.v2.c3p0.ComboPooledDataSource;

import java.beans.PropertyVetoException;
import java.util.HashMap;
import java.util.Set;

public class DatabasePools {
	private static final HashMap<String, ComboPooledDataSource> pools =
						new HashMap<>();

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

	public static ComboPooledDataSource get(String name) {
		return pools.get(name);
	}

	public static Set<String> keys() {
		return pools.keySet();
	}
}
