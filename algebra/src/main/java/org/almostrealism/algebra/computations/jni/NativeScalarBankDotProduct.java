/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.algebra.computations.jni;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarBankDotProduct;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.jni.NativeComputationEvaluable;
import org.almostrealism.hardware.jni.NativeSupport;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public abstract class NativeScalarBankDotProduct extends ScalarBankDotProduct implements NativeSupport<NativeComputationEvaluable> {
	private static final Map<Integer, Evaluable<? extends Scalar>> evaluables = new HashMap<>();

	public NativeScalarBankDotProduct(int count) {
		super(count, new PassThroughProducer(2 * count, 0),
				new PassThroughProducer<>(2 * count, 1));
		initNative();
	}

	@Override
	public NativeComputationEvaluable<Scalar> get() {
		return new NativeComputationEvaluable<>(this);
	}

	public synchronized static Evaluable<? extends Scalar> get(int count) {
		if (!evaluables.containsKey(count)) {
			try {
				Object c = Class.forName(NativeScalarBankDotProduct.class.getName() + count)
						.getConstructor().newInstance();
				evaluables.put(count, ((Supplier<Evaluable<? extends Scalar>>) c).get());
			} catch (ClassNotFoundException | NoSuchMethodException | InstantiationException |
						IllegalAccessException | InvocationTargetException e) {
				e.printStackTrace();
			}
		}

		return evaluables.get(count);
	}
}
