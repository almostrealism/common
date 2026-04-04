package io.almostrealism.persist;

import io.almostrealism.sql.SQLConnectionProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Abstract query that executes a SQL statement and optionally fans out to additional
 * child {@link CascadingQuery} instances for each row in the result set.
 *
 * <p>The cascade map allows a single result row to trigger further queries keyed by the
 * result type, enabling hierarchical or graph-like data loading from a relational database.</p>
 *
 * @param <D> The {@link SQLConnectionProvider} supplying JDBC connections
 * @param <K> The key type passed to {@link #getQuery(Object)} and {@link #init(Object)}
 * @param <V> The result type, which must be {@link Cacheable}
 */
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
	
	/**
	 * Returns {@code true} if this query is a root query (not driven by a parent cascade).
	 * The default implementation returns {@code false}.
	 *
	 * @return {@code true} if this is a root query
	 */
	public boolean isRoot() { return false; }

	/**
	 * Initializes internal state before execution for the given key.
	 *
	 * @param key The key for the current query execution
	 */
	public abstract void init(K key);

	/**
	 * Returns the SQL query string to execute for the given key, or {@code null} to skip execution.
	 *
	 * @param key The key to construct the SQL query for
	 * @return The SQL query string, or {@code null}
	 */
	public abstract String getQuery(K key);

	/**
	 * Returns the accumulated collection of results after all rows have been processed.
	 *
	 * @param key The key used for this query execution
	 * @return The collection of result values
	 */
	public abstract Collection<V> getReturnValue(K key);

	/**
	 * Processes a single result row from the query and returns the corresponding value.
	 *
	 * @param rs        The current result set, positioned at the row to process
	 * @param arguments The key used for this query execution
	 * @param cascades  Map from result type to child queries for fan-out processing
	 * @return The value extracted from the row
	 * @throws SQLException If the result set cannot be read
	 */
	public abstract V process(ResultSet rs, K arguments, Map<Class, List<CascadingQuery>> cascades) throws SQLException;

	/**
	 * Invokes all registered child queries for the given value's type, passing the same
	 * result row and cascade map.
	 *
	 * @param rs       The current result set row
	 * @param value    The parent value whose type is used to look up child queries
	 * @param cascades Map from result type to child queries
	 * @throws SQLException If any child query fails to read from the result set
	 */
	public void processCascades(ResultSet rs, V value, Map<Class, List<CascadingQuery>> cascades) throws SQLException {
		if (value == null || cascades == null) return;
		List<CascadingQuery> l = cascades.get(value.getClass());
		if (l == null) return;
		
		for (CascadingQuery q : l) {
			q.process(rs, value, cascades);
		}
	}
}
