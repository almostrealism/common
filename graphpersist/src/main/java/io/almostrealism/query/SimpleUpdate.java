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
public abstract class SimpleUpdate<D, K, V> implements Update<D, K, V>, Iterable<Map.Entry> {
	private final Hashtable map = new Hashtable<>();

	protected String query;
	
	public SimpleUpdate(String q) { query = q; }

	public void put(String column, String fieldName) { map.put(column, fieldName); }

	public String get(String name) { return (String) map.get(name); }
	
	public Iterator<Map.Entry> iterator() { return map.entrySet().iterator(); }
}
