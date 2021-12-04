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
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.ScalarBankDotProduct;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.jni.NativeInstructionSet;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Deprecated
public abstract class NativeScalarBankDotProduct extends ScalarBankDotProduct implements NativeInstructionSet {
	private static final Map<Integer, Evaluable<? extends Scalar>> evaluables = new HashMap<>();

	public NativeScalarBankDotProduct(int count) {
		super(count, new PassThroughProducer(2 * count, 0),
				new PassThroughProducer<>(2 * count, 1));
	}

	protected NativeScalarBankDotProduct(int count, Supplier<ScalarBank> temp) {
		super(count, new PassThroughProducer(2 * count, 0),
				new PassThroughProducer<>(2 * count, 1), temp);
	}

	public synchronized static Evaluable<? extends Scalar> get(int count) {
		if (!evaluables.containsKey(count)) {
			evaluables.put(count, create(count).get());
		}

		return evaluables.get(count);
	}

	public static ScalarBankDotProduct create(int count, Object... args) {
		if (!Hardware.getLocalHardware().isNativeSupported()) {
			return new ScalarBankDotProduct(count,
					new PassThroughProducer(2 * count, 0),
					new PassThroughProducer(2 * count, 1));
		}

		try {
			// TODO Class cls[] = Stream.of(args).map(Object::getClass).toArray(Class[]::new);
			Class cls[] = args.length == 0 ? new Class[0] : new Class[] { Supplier.class };
			Object c = Class.forName(NativeScalarBankDotProduct.class.getName() + count)
					.getConstructor(cls).newInstance(args);
			return (ScalarBankDotProduct) c;
		} catch (ClassNotFoundException e) {
			return new ScalarBankDotProduct(count,
					new PassThroughProducer(2 * count, 0),
					new PassThroughProducer(2 * count, 1));
		} catch (NoSuchMethodException | InstantiationException |
				IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	public static ScalarBankDotProduct create(int count, Supplier<Scalar> destination, Object... args) {
		ScalarBankDotProduct p = create(count, args);
		p.setDestination(destination);
		return p;
	}
}
