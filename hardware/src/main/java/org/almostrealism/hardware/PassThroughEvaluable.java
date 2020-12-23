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

package org.almostrealism.hardware;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import java.lang.reflect.InvocationTargetException;

public class PassThroughEvaluable<T> implements Evaluable<T>, ProducerArgumentReference, HardwareFeatures {
	private int argIndex = -1;

	public PassThroughEvaluable(int argIndex) {
		this.argIndex = argIndex;
	}

	/** Returns the argument at the index specified to the constructor of {@link PassThroughEvaluable}. */
	@Override
	public T evaluate(Object[] args) {
		return (T) args[argIndex];
	}

	@Override
	public int getReferencedArgumentIndex() { return argIndex; }

	public static <T> Producer<T> of(Class<? extends T> type, int index) {
		return of(type, index, 0);
	}

	public static <T> Producer<T> of(Class<? extends T> type, int index, int kernelDimension) {
		if (MemWrapper.class.isAssignableFrom(type)) {
			try {
				MemWrapper m = (MemWrapper) type.getConstructor().newInstance();
				return new AcceleratedPassThroughProducer(m.getMemLength(), index, kernelDimension);
			} catch (InstantiationException | IllegalAccessException |
					InvocationTargetException | NoSuchMethodException e) {
				System.out.println("WARN: Unable to determine memory length for " + type.getName());
				return () -> new PassThroughEvaluable<>(index);
			}
		} else {
			return () -> new PassThroughEvaluable<>(index);
		}
	}
}
