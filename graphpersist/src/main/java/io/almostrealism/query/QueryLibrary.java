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

package io.almostrealism.query;

import io.almostrealism.persist.CascadingQuery;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link QueryLibrary} tracks {@link Query}s for various object types,
 * making it trivial to retrieve data without the need for creating separate
 * DAO types for your POJOs.
 *
 * @author  Michael Murray
 */
public class QueryLibrary<D> {
	private static final QueryLibrary root = new QueryLibrary();
	
	private final HashMap<KeyValueTypes, List<Query<? extends D, ?, ?>>>  queries;
	
	protected QueryLibrary() { queries = new HashMap<>(); }

	public synchronized <V, K> void addQuery(Class<V> type, Class<K> argumentType, Query<? extends D, ? extends K, V> q) {
		KeyValueTypes t = new KeyValueTypes(argumentType, type);
		if (queries.get(t) == null) queries.put(t, new ArrayList<Query<? extends D, ?, ?>>());
		queries.get(t).add(q);
	}
	
	public <V> Collection<V> get(D database, Class type) throws IllegalAccessException, InvocationTargetException {
		return get(database, type, null, null);
	}
	
	public <V, K> Collection<V> get(D database, Class<V> type, Class<K> argumentType, K arguments) throws IllegalAccessException, InvocationTargetException {
		List<Query<? extends D, ?, ?>> ql = null;
		
		synchronized (this) { ql = queries.get(new KeyValueTypes(argumentType, type)); }
		
		if (ql == null) return null;
		
		List<V> l = new ArrayList<V>();
		for (Query q : ql) {
			Collection<V> v = q.execute(database, arguments, getCascades(type, new HashMap<Class, List<CascadingQuery>>()));
			if (v != null) l.addAll(v);
		}
		return l;
	}
	
	public Map<Class, List<CascadingQuery>> getCascades(Class type, Map<Class, List<CascadingQuery>> m) {
		for (KeyValueTypes k : queries.keySet()) {
			if (k.keyType == type) {
				for (Query q : queries.get(k)) {
					if (q instanceof CascadingQuery && !((CascadingQuery) q).isRoot()) {
						List<CascadingQuery> l = m.get(k.keyType);
						if (l == null) {
							l = new ArrayList<CascadingQuery>();
							m.put(k.keyType, l);
						}
						
						l.add((CascadingQuery) q);
						getCascades(k.valueType, m);
					}
				}
			}
		}
		
		return m;
	}
	
	public static QueryLibrary root() { return root; }
	
	private static class KeyValueTypes {
		Class keyType;
		Class valueType;
		
		public KeyValueTypes(Class keyType, Class valueType) {
			this.keyType = keyType;
			this.valueType = valueType;
		}
		
		public boolean equals(Object o) {
			if (!(o instanceof KeyValueTypes)) return false;
			
			if (((KeyValueTypes) o).keyType == null && keyType != null) return false;
			if (keyType == null && ((KeyValueTypes) o).keyType != null) return false;
			if (keyType != null && ((KeyValueTypes) o).keyType != null &&
					!((KeyValueTypes) o).keyType.equals(keyType)) return false;
			
			if (((KeyValueTypes) o).valueType == null && valueType != null) return false;
			if (valueType == null && ((KeyValueTypes) o).valueType != null) return false;
			return valueType == null || ((KeyValueTypes) o).valueType == null ||
					((KeyValueTypes) o).valueType.equals(valueType);
		}
		
		public int hashCode() { return valueType.hashCode(); }
	}
}
