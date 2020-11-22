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

package org.almostrealism.util;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.MemWrapper;

import java.util.function.Function;

public class DynamicEvaluable<T> implements Evaluable<T> {
	private Function<Object[], T> function;

	public DynamicEvaluable(Function<Object[], T> function) {
		this.function = function;
	}

	/**
	 * Applies the {@link Function}.
	 */
	@Override
	public T evaluate(Object[] args) { return function.apply(args); }

	/**
	 * Does nothing.
	 */
	@Override
	public void compact() { }

	public static Function<Integer, Evaluable<? extends MemWrapper>> forMemLength() {
		return len -> {
			if (len == 2) {
				return new DynamicEvaluable<>(args -> new Pair());
			} else if (len == 3) {
				return new DynamicEvaluable<>(args -> new Vector());
			} else if (len == 6) {
				return new DynamicEvaluable<>(args -> new Ray());
			} else {
				throw new IllegalArgumentException("Mem length " + len + " unknown");
			}
		};
	}
}
