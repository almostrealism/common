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
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemWrapper;

import java.util.function.Function;

public class AcceleratedStaticProducer<T extends MemWrapper> extends DynamicAcceleratedProducerAdapter<T> {
	private T value;

	public AcceleratedStaticProducer(T value, Producer<T> output) {
		super(value.getMemLength(), output);
		this.value = value;
	}

	/**
	 * Short circuit the evaluation of a CL program by simply returning
	 * the value.
	 */
	@Override
	public T evaluate(Object args[]) {
		return value;
	}

	/**
	 * Provided to support compact operations of other {@link DynamicAcceleratedProducerAdapter}s,
	 * this is not actually used by {@link #evaluate(Object[])}.
	 */
	@Override
	public Function<Integer, String> getValueFunction() {
		return pos -> {
			Pair p = MemWrapper.fromMem(value.getMem(), value.getOffset() + pos, 1);

			String s = stringForDouble(p.getA());
			if (s.contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			return s;
		};
	}

	/**
	 * Returns true.
	 */
	@Override
	public boolean isStatic() { return true; }
}
