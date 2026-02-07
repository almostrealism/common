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
 * @author  Michael Murray
 */
public class UpdateLibrary<D, K> {
	private static final UpdateLibrary root = new UpdateLibrary();
	
	private final HashMap<Class, Update<? extends D, ? extends K, ?>>  updates;
	
	protected UpdateLibrary() { updates = new HashMap<>(); }
	
	public synchronized <V> void addUpdate(Class<V> type, Update<? extends D, ? extends K, V> q) {
		updates.put(type, q);
	}
	
	public <V> void put(D database, Class<V> type, K key, V data) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		Update q = null;
		
		synchronized (this) { q = updates.get(type); }
		
		q.execute(database, key, data);
	}
	
	public static UpdateLibrary root() { return root; }
}
