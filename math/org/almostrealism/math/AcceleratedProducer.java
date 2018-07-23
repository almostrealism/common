/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.math;

import org.almostrealism.util.Producer;

import java.util.HashMap;
import java.util.Map;

public class AcceleratedProducer<T extends MemWrapper> implements Producer<T> {
	private static Map<String, ThreadLocal<HardwareOperator>> operators = new HashMap<>();

	private String function;
	private boolean scalar, kernel;

	private Object allArgs[];
	private Producer inputProducers[];

	public AcceleratedProducer(String function, boolean scalar, boolean kernel, Producer<Object> inputArgs[], Object additionalArguments[]) {
		this.function = function;
		this.scalar = scalar;
		this.kernel = kernel;
		inputProducers = inputArgs;
		allArgs = new Object[inputArgs.length + additionalArguments.length];

		for (int i = inputArgs.length; i < allArgs.length; i++) {
			allArgs[i] = additionalArguments[i - inputArgs.length];
		}
	}

	@Override
	public synchronized T evaluate(Object[] args) {
		synchronized (AcceleratedProducer.class) {
			if (operators.get(function) == null) {
				operators.put(function, new ThreadLocal<>());
			}

			if (operators.get(function).get() == null) {
				operators.get(function).set(Hardware.getLocalHardware().getFunctions().getOperators().get(function, scalar, kernel, allArgs.length));
			}
		}

		for (int i = 0; i < inputProducers.length; i++) {
			try {
				allArgs[i] = inputProducers[i].evaluate(args);
			} catch (Exception e) {
				throw new RuntimeException("Function \"" + function + "\" could not complete due to exception evaluating argument " + i, e);
			}
		}

		try {
			return (T) operators.get(function).get().evaluate(allArgs);
		} finally {
			for (int i = 0; i < inputProducers.length; i++) {
				allArgs[i] = null;
			}
		}
	}

	@Override
	public synchronized void compact() {
		for (int i = 0; i < inputProducers.length; i++) {
			inputProducers[i].compact();
		}
	}
}
