/*
 * Copyright 2016 Michael Murray
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

import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;

/**
 * {@link SimpleUpdate} maps named columns to the POJO fields that should be populated.
 *
 * @author  Michael Murray
 */
/**
 * Abstract update that maintains a mapping from SQL column names to POJO field names,
 * enabling entity field values to be reflectively mapped to SQL update parameters.
 *
 * @param <D> The database type
 * @param <K> The argument (key) type
 * @param <V> The entity type being updated
 *
 * @author  Michael Murray
 */
public abstract class SimpleUpdate<D, K, V> implements Update<D, K, V>, Iterable<Map.Entry> {
	/** Map from SQL column names to entity field names. */
	private final Hashtable map = new Hashtable<>();

	/** The SQL update string executed by this update. */
	protected String query;

	/**
	 * Constructs a {@link SimpleUpdate} with the given SQL string.
	 *
	 * @param q The SQL update string
	 */
	public SimpleUpdate(String q) { query = q; }

	/**
	 * Registers a mapping from a SQL column name to an entity field name.
	 *
	 * @param column    The SQL column name
	 * @param fieldName The entity field name to read the value from
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
	 * Returns an iterator over the column-to-field mapping entries.
	 *
	 * @return An iterator of {@link Map.Entry} items
	 */
	public Iterator<Map.Entry> iterator() { return map.entrySet().iterator(); }
}
