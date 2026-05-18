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

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

/**
 * Registry mapping entity types to their {@link Update} implementations, enabling
 * type-safe persistence of entity changes without requiring separate DAO classes.
 *
 * @param <D> The database type
 * @param <K> The key type used to identify entities in the database
 *
 * @author  Michael Murray
 */
public class UpdateLibrary<D, K> {
	/** The global root library instance shared across the application. */
	private static final UpdateLibrary root = new UpdateLibrary();

	/** Map from entity class to the registered update for that type. */
	private final HashMap<Class, Update<? extends D, ? extends K, ?>>  updates;

	/**
	 * Constructs a new empty {@link UpdateLibrary}.
	 */
	protected UpdateLibrary() { updates = new HashMap<>(); }

	/**
	 * Registers an update implementation for the given entity type.
	 *
	 * @param <V>  The entity type
	 * @param type The class of the entity type
	 * @param q    The update to register
	 */
	public synchronized <V> void addUpdate(Class<V> type, Update<? extends D, ? extends K, V> q) {
		updates.put(type, q);
	}

	/**
	 * Executes the registered update for the given entity type, writing the entity's data
	 * to the database under the given key.
	 *
	 * @param <V>      The entity type
	 * @param database The database to update
	 * @param type     The class of the entity type
	 * @param key      The key identifying the entity in the database
	 * @param data     The entity data to write
	 * @throws IllegalAccessException    If reflection fails accessing entity fields
	 * @throws InvocationTargetException If reflection fails invoking entity methods
	 * @throws NoSuchMethodException     If a required method is not found on the entity
	 */
	public <V> void put(D database, Class<V> type, K key, V data) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		Update q = null;
		
		synchronized (this) { q = updates.get(type); }
		
		q.execute(database, key, data);
	}
	
	/**
	 * Returns the global root {@link UpdateLibrary} instance.
	 *
	 * @return The root library
	 */
	public static UpdateLibrary root() { return root; }
}
