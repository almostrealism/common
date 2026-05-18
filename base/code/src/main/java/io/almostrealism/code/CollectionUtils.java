/*
 * Copyright 2020 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.code;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link CollectionUtils} provides utility methods for working with collections and arrays.
 * These methods simplify common collection manipulation operations used throughout the
 * Almost Realism framework.
 *
 * <p>This class contains static utility methods and is not meant to be instantiated.
 *
 * @author Michael Murray
 */
public class CollectionUtils {

	/**
	 * Creates a new array containing the specified elements, with an additional element
	 * prepended at the beginning.
	 *
	 * <p>This method is useful for building arrays where a specific element needs to be
	 * at the front, followed by additional elements.
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * String[] result = CollectionUtils.include(new String[0], "first", "second", "third");
	 * // result = ["first", "second", "third"]
	 * }</pre>
	 *
	 * @param <T> the type of elements in the array
	 * @param empty an empty array of the target type, used for array creation via {@link List#toArray(Object[])}
	 * @param r the element to include at the beginning of the array
	 * @param p additional elements to include after the first element
	 * @return a new array containing all specified elements
	 */
	@SafeVarargs
	public static <T> T[] include(T[] empty, T r, T... p) {
		List<T> res = new ArrayList<>();
		res.add(r);

		for (int i = 0; i < p.length; i++) {
			res.add(p[i]);
		}

		return res.toArray(empty);
	}

	/**
	 * Returns a new {@link List} containing all elements from the input collection
	 * except for the specified element to exclude.
	 *
	 * <p>Element comparison is done using reference equality ({@code !=}), not
	 * {@link Object#equals(Object)}. This means only the exact same object instance
	 * will be excluded.
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * List<String> items = Arrays.asList("a", "b", "c");
	 * List<String> result = CollectionUtils.separate("b", items);
	 * // result = ["a", "c"] (assuming "b" is the same instance)
	 * }</pre>
	 *
	 * @param <T> the type of elements in the collection
	 * @param element the element to exclude from the result (compared by reference)
	 * @param all the source collection to filter
	 * @return a new list containing all elements except the specified one
	 */
	public static <T> List<T> separate(T element, Iterable<T> all) {
		ArrayList<T> difference = new ArrayList<>();

		for (T o : all) {
			if (element != o) {
				difference.add(o);
			}
		}

		return difference;
	}
}
