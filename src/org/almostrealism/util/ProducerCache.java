/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.util;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@link ProducerCache} provides static methods for keeping track
 * of the last result of a {@link Producer} in the current {@link Thread}.
 * Based on the assumption that a thread processes only one set of arguments
 * at a time, this allows {@link Producer} evaluation to be short circuited
 * when the arguments have not changed.
 *
 * @author  Michael Murray
 */
public class ProducerCache {
	private static ThreadLocal<Map<Producer, Object>> cache = new ThreadLocal<>();

	private static ThreadLocal<Object[]> lastParameter = new ThreadLocal<>();

	/**
	 * This type is not to be instantiated.
	 */
	private ProducerCache() { }

	public static <T> T evaluate(Producer<T> p, Object args[]) {
		checkArgs(args);

		if (getCache().containsKey(p)) {
			return (T) getCache().get(p);
		}

		T result = p.evaluate(args);
		getCache().put(p, result);
		return result;
	}

	public static void clear() { getCache().clear(); }

	private static void checkArgs(Object args[]) {
		if (lastParameter.get() != args) {
			lastParameter.set(args);
			clear();
		}
	}

	private static Map<Producer, Object> getCache() {
		if (cache.get() == null) {
			cache.set(new HashMap<>());
		}

		return cache.get();
	}
}
