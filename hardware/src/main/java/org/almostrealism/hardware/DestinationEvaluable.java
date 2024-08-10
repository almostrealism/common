/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Named;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.jocl.CLException;

import java.util.stream.Stream;

public class DestinationEvaluable<T extends MemoryBank> implements Evaluable<T>, ConsoleFeatures {
	private Evaluable<T> operation;
	private MemoryBank destination;

	public DestinationEvaluable(Evaluable<T> operation, MemoryBank destination) {
		this.operation = operation;
		this.destination = destination;

		if (operation instanceof HardwareEvaluable) {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public T evaluate(Object... args) {
		if (operation instanceof AcceleratedOperation && ((AcceleratedOperation) operation).isKernel()) {
			((AcceleratedOperation) operation).kernelOperate(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
		} else {
			String name = operation instanceof Named ? ((Named) operation).getName() : OperationAdapter.operationName(null, getClass(), "function");
			if (HardwareOperator.enableKernelLog) log("Evaluating " + name + " kernel...");

			boolean enableLog = false;

			for (int i = 0; i < destination.getCountLong(); i++) {
				T r = null;

				try {
					final int fi = i;
					Object o[] = Stream.of(args)
							.map(arg -> ((MemoryBank) arg).get(fi)).toArray();

					r = operation.evaluate(o);
					if (r == null) r = replaceNull(o);

					destination.set(i, r);
				} catch (UnsupportedOperationException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCountLong() + ", r = " + r, e);
				} catch (CLException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCountLong() + ", r = " + r, e);
				}

				if (enableLog && (i + 1) % 100 == 0) log((i + 1) + " kernel results collected");
			}
		}

		return (T) destination;
	}

	public T replaceNull(Object[] o) {
		if (operation instanceof NullProcessor) {
			return (T) ((NullProcessor) operation).replaceNull(o);
		} else {
			throw new NullPointerException();
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
