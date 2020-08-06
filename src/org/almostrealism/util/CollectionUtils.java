package org.almostrealism.util;

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
