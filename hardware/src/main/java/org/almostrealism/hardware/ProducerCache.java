/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.mem.Heap;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@link ProducerCache} provides static methods for keeping track
 * of the last result of a {@link Evaluable} in the current {@link Thread}.
 * Based on the assumption that a thread processes only one set of arguments
 * at a time, this allows {@link Evaluable} evaluation to be short-circuited
 * when the arguments have not changed.
 *
 * @author  Michael Murray
 */
public class ProducerCache {
	public static boolean enableEvaluableCache = true;

	private static ThreadLocal<Map<Supplier, Evaluable>> evaluableCache = new ThreadLocal<>();

	/** This type is not to be instantiated. */
	private ProducerCache() { }

	public static <T> Evaluable<? extends Multiple<T>> getEvaluableForArrayVariable(ArrayVariable<T> argument) {
		return getEvaluableForSupplier(argument.getProducer());
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
		if (producer == null) {
			throw new IllegalArgumentException();
		}

		if (enableEvaluableCache) {
			return getEvaluableCache().computeIfAbsent(producer, p -> {
				Heap.addOperation(p);
				return (Evaluable) p.get();
			});
		} else {
			return producer.get();
		}
	}

	public static <T> void purgeEvaluableCache(Supplier<Evaluable<? extends T>> producer) {
		if (enableEvaluableCache) {
			getEvaluableCache().remove(producer);
		}
	}

	public static void destroyEvaluableCache() {
		getEvaluableCache().clear();
		evaluableCache.remove();
		evaluableCache = new ThreadLocal<>();
	}

	private static Map<Supplier, Evaluable> getEvaluableCache() {
		if (evaluableCache.get() == null) {
			evaluableCache.set(new IdentityHashMap<>());
		}

		return evaluableCache.get();
	}
}
