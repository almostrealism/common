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
}
