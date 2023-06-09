/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CacheManager<T> {
	private HashMap<CachedValue<T>, Long> values;
	private Runnable access;
	private Consumer<T> clear;

	public CacheManager() {
		values = new HashMap<>();
	}

	public void setAccessListener(Runnable listener) {
		this.access = listener;
	}

	public void setClear(Consumer<T> clear) {
		this.clear = clear;
	}

	public List<CachedValue<T>> getCachedOrdered() {
		List<CachedValue<T>> values = new ArrayList<>(this.values.keySet().stream()
				.filter(CachedValue::isCached).collect(Collectors.toList()));
		values.sort((a, b) -> (int) (this.values.get(a) - this.values.get(b)));
		return values;
	}

	public CachedValue<T> get(Evaluable<T> source) {
		CachedValue<T> v = new CachedValue<>(null, clear);
		v.setEvaluable(args -> {
			values.put(v, System.currentTimeMillis());
			if (access != null) access.run();
			return source.evaluate(args);
		});
		return v;
	}

	public static <T> Runnable maxCachedEntries(CacheManager<T> mgr, int max) {
		return () -> {
			List<CachedValue<T>> ordered = mgr.getCachedOrdered();

			int count = ordered.size() - max;
			if (count <= 0) return;

			for (int i = 0; i < count; i++) ordered.get(i).clear();
			// System.out.println("CacheManager: Cleared " + count + " cached values");
		};
	}
}
