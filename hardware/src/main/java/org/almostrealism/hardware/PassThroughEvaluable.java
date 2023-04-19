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

package org.almostrealism.hardware;

import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import java.lang.reflect.InvocationTargetException;

@Deprecated
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

	@Deprecated
	public static <T> Producer<T> of(int index) {
		return of(null, index);
	}

	// TODO  Move to PassThroughProducer
	@Deprecated
	public static <T> Producer<T> of(Class<? extends T> type, int index) {
		return of(type, index, 0);
	}

	// TODO  Move to PassThroughProducer
	@Deprecated
	public static <T> Producer<T> of(Class<? extends T> type, int index, int kernelDimension) {
		if (type == null || MemoryBank.class.isAssignableFrom(type)) {
//			return () -> new PassThroughEvaluable<>(index);
			if (kernelDimension == 0) {
				return new PassThroughProducer(1, index);
			} else {
				return new PassThroughProducer(1, index, kernelDimension);
			}
		} else if (MemoryData.class.isAssignableFrom(type)) {
			try {
				MemoryData m = (MemoryData) type.getConstructor().newInstance();
				return new PassThroughProducer(m.getMemLength(), index, kernelDimension);
			} catch (InstantiationException | IllegalAccessException |
					InvocationTargetException | NoSuchMethodException e) {
				System.out.println("WARN: Unable to determine memory length for " + type.getName());
				if (e instanceof InvocationTargetException) e.printStackTrace();
				return () -> new PassThroughEvaluable<>(index);
			}
		} else {
			return () -> new PassThroughEvaluable<>(index);
		}
	}
}
