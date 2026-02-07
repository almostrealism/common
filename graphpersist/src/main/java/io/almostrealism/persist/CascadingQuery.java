package io.almostrealism.persist;

import io.almostrealism.sql.SQLConnectionProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public abstract class CascadingQuery<D extends SQLConnectionProvider, K, V extends Cacheable> extends CacheableQuery<D, K, V> {
	@Override
	public Collection<V> execute(D database, K key, Map<Class, List<CascadingQuery>> cascades) {
		init(key);
		
		try (Connection c = database.getSQLConnection(); Statement s = c.createStatement()) {
			String q = getQuery(key);
			if (q == null) return null;
			
//			System.out.println("CascadingQuery: " + q);
			ResultSet rs = s.executeQuery(q);
			
			while (rs.next()) {
				process(rs, key, cascades);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		
		return getReturnValue(key);
	}
	
	public boolean isRoot() { return false; }
	
	public abstract void init(K key);
	
	public abstract String getQuery(K key);
	
	public abstract Collection<V> getReturnValue(K key);
	
	public abstract V process(ResultSet rs, K arguments, Map<Class, List<CascadingQuery>> cascades) throws SQLException;
	
	public void processCascades(ResultSet rs, V value, Map<Class, List<CascadingQuery>> cascades) throws SQLException {
		if (value == null || cascades == null) return;
		List<CascadingQuery> l = cascades.get(value.getClass());
		if (l == null) return;
		
		for (CascadingQuery q : l) {
			q.process(rs, value, cascades);
		}
	}
}
