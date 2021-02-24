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

public class CollectionUtils {
	public static <T> T[] include(T[] empty, T r, T... p) {
		List<T> res = new ArrayList<>();
		res.add(r);

		for (int i = 0; i < p.length; i++) {
			res.add(p[i]);
		}

		return res.toArray(empty);
	}

	/**
	 * Returns a {@link List} with the specified element removed.
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
