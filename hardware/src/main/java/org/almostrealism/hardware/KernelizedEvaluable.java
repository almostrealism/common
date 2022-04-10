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

package org.almostrealism.hardware;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Named;
import io.almostrealism.relation.Evaluable;
import org.jocl.CLException;

import java.util.stream.Stream;

/**
 * A {@link KernelizedEvaluable} is a {@link Evaluable} that can be evaluated
 * for a {@link MemoryBank} with one operation. The default implementation
 * of this {@link MemoryBank} evaluation simply delegates to the normal
 * {@link #evaluate(Object[])} method for each element of the
 * {@link MemoryBank}.
 *
 * @author  Michael Murray
 */
public interface KernelizedEvaluable<T extends MemoryData> extends Evaluable<T> {
	default void kernelEvaluate(MemoryBank destination, MemoryBank... args) {
		String name = this instanceof Named ? ((Named) this).getName() : OperationAdapter.operationName(null, getClass(), "function");
		if (KernelizedOperation.enableKernelLog) System.out.println("KernelizedEvaluable: Evaluating " + name + " kernel...");

		boolean enableLog = false; // name.equals("LightingEngineAggregator");

		for (int i = 0; i < destination.getCount(); i++) {
			T r = null;

			try {
				final int fi = i;
				Object o[] = Stream.of(args)
						.map(arg -> arg.get(fi)).toArray();

				r = evaluate(o);
				if (r == null) r = replaceNull(o);

				destination.set(i, r);
			} catch (UnsupportedOperationException e) {
				throw new HardwareException("i = " + i + " of " + destination.getCount() + ", r = " + r, e);
			} catch (CLException e) {
				throw new HardwareException("i = " + i + " of " + destination.getCount() + ", r = " + r, e);
			}

			if (enableLog && (i + 1) % 100 == 0) System.out.println((i + 1) + " kernel results collected");
		}
	}

	default T replaceNull(Object args[]) {
		throw new NullPointerException();
	}

	MemoryBank<T> createKernelDestination(int size);
}
