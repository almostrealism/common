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

package org.almostrealism.hardware;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@link ProducerCache} provides static methods for keeping track
 * of the last result of a {@link Evaluable} in the current {@link Thread}.
 * Based on the assumption that a thread processes only one set of arguments
 * at a time, this allows {@link Evaluable} evaluation to be short circuited
 * when the arguments have not changed.
 *
 * @author  Michael Murray
 */
public class ProducerCache {
	public static boolean enableResultCache = false;

	private static ThreadLocal<Map<Supplier, Object>> resultCache = new ThreadLocal<>();
	private static ThreadLocal<Map<Supplier, Evaluable>> evaluableCache = new ThreadLocal<>();

	private static ThreadLocal<Object[]> lastParameter = new ThreadLocal<>();

	/**
	 * This type is not to be instantiated.
	 */
	private ProducerCache() { }

	public static <T> T evaluate(Supplier<Evaluable<? extends T>> p, Object args[]) {
		checkArgs(args);

		// If the result is already known, return it
		// TODO  There should be a way to indicate that
		//       a producer is not cacheable (if its
		//       results are non-deterministic or
		//       random, etc).
		if (enableResultCache && getResultCache().containsKey(p)) {
			return (T) getResultCache().get(p);
		}

		try {
			T result = getEvaluableForSupplier(p).evaluate(args);

			if (enableResultCache) getResultCache().put(p, result);
			return result;
		} catch (ClassCastException e) {
			throw new IllegalArgumentException(String.valueOf(p.get().getClass()), e);
		}
	}

	/**
	 * This provides a way to obtain an already available {@link Evaluable} for
	 * the given {@link Supplier}. The {@link Evaluable} is not to be used with
	 * other {@link Thread}s and callers of this method can be sure that the
	 * returned {@link Evaluable} will not have been returned for any other
	 * {@link Thread} than the current one. If an {@link Evaluable} for this
	 * {@link Thread} has not already been obtained, the {@link Supplier#get()}
	 * method will be used to obtain one, and it will be kept to later be
	 * returned by this method if it is called again.
	 */
	public static <T> Evaluable<? extends T> getEvaluableForSupplier(Supplier<Evaluable<? extends T>> producer) {
		if (!getEvaluableCache().containsKey(producer)) {
			Evaluable ev = producer.get();
			if (ev instanceof OperationAdapter) {
				((OperationAdapter) ev).compile();
			}

			getEvaluableCache().put(producer, ev);
		}

		return getEvaluableCache().get(producer);
	}

	public static void clear() { getResultCache().clear(); }

	private static void checkArgs(Object args[]) {
		if (lastParameter.get() != args) {
			lastParameter.set(args);
			clear();
		}
	}

	private static Map<Supplier, Object> getResultCache() {
		if (resultCache.get() == null) {
			resultCache.set(new HashMap<>());
		}

		return resultCache.get();
	}

	private static Map<Supplier, Evaluable> getEvaluableCache() {
		if (evaluableCache.get() == null) {
			evaluableCache.set(new HashMap<>());
		}

		return evaluableCache.get();
	}
}
