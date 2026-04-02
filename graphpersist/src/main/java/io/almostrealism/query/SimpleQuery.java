package io.almostrealism.query;

import io.almostrealism.relation.Factory;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;

/**
 * {@link SimpleQuery} maps named columns to the POJO fields that should be populated.
 *
 * @author  Michael Murray
 */
/**
 * Abstract query that maintains a mapping from SQL column names to POJO field names,
 * enabling result rows to be reflectively mapped to entity objects.
 *
 * @param <D> The database type
 * @param <K> The argument (key) type
 * @param <V> The result entity type
 *
 * @author  Michael Murray
 */
public abstract class SimpleQuery<D, K, V> implements Query<D, K, V>, Iterable<Map.Entry> {
	/** Map from SQL column names to entity field names. */
	private final Hashtable map = new Hashtable<>();

	/** The SQL query string executed by this query. */
	protected String query;

	/** Factory used to create new entity instances for each result row. */
	protected Factory<V> factory;

	/**
	 * Constructs a {@link SimpleQuery} with the given SQL string and entity factory.
	 *
	 * @param q The SQL query string
	 * @param f The factory for creating result entities
	 */
	public SimpleQuery(String q, Factory<V> f) { query = q; factory = f; }

	/**
	 * Registers a mapping from a SQL column name to an entity field name.
	 *
	 * @param column    The SQL column name
	 * @param fieldName The entity field name to populate from that column
	 */
	public void put(String column, String fieldName) { map.put(column, fieldName); }

	/**
	 * Returns the entity field name mapped to the given column name.
	 *
	 * @param name The SQL column name to look up
	 * @return The mapped entity field name, or {@code null} if not registered
	 */
	public String get(String name) { return (String) map.get(name); }

	/**
	 * Creates a new entity instance using the registered factory.
	 *
	 * @return A new entity of type {@code V}
	 */
	protected V createEntity() { return factory.construct(); }

	/**
	 * Returns an iterator over the column-to-field mapping entries.
	 *
	 * @return An iterator of {@link Map.Entry} items
	 */
	public Iterator<Map.Entry> iterator() { return map.entrySet().iterator(); }
}