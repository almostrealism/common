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
public abstract class SimpleQuery<D, K, V> implements Query<D, K, V>, Iterable<Map.Entry> {
	private final Hashtable map = new Hashtable<>();

	protected String query;
	
	protected Factory<V> factory;
	
	public SimpleQuery(String q, Factory<V> f) { query = q; factory = f; }

	public void put(String column, String fieldName) { map.put(column, fieldName); }

	public String get(String name) { return (String) map.get(name); }
	
	protected V createEntity() { return factory.construct(); }
	
	public Iterator<Map.Entry> iterator() { return map.entrySet().iterator(); }
}