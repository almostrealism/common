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

package io.almostrealism.relation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public interface Named {
	String getName();

	static <T extends Named> List<T> removeDuplicates(List<T> list) {
		return removeDuplicates(list, (a, b) -> a);
	}

	static <T extends Named> List<T> removeDuplicates(List<T> list, BiFunction<T, T, T> chooser) {
		List<T> values = new ArrayList<>();
		list.stream().filter(Objects::nonNull).forEach(values::add);

		List<String> names = new ArrayList<>();
		Map<String, T> chosen = new HashMap<>();

		values.forEach(v -> {
			String name = v.getName();

			if (names.contains(name)) {
				chosen.put(name, chooser.apply(chosen.get(name), v));
			} else {
				names.add(name);
				chosen.put(name, v);
			}
		});

		return names.stream().map(chosen::get).collect(Collectors.toList());
	}
}
