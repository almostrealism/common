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

import org.almostrealism.util.Producer;
import org.jocl.CLException;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link KernelizedProducer} is a {@link Producer} that can be evaluated
 * for a {@link MemoryBank} with one operation. The default implementation
 * of this {@link MemoryBank} evaluation simply delegates to the normal
 * {@link #evaluate(Object[])} method for each element of the
 * {@link MemoryBank}.
 *
 * @author  Michael Murray
 */
public interface KernelizedProducer<T extends MemWrapper> extends Producer<T> {
	default void kernelEvaluate(MemoryBank destination, MemoryBank args[]) {
		String name = getClass().getSimpleName();
		if (name == null || name.trim().length() <= 0) name = "anonymous";
		System.out.println("KernelizedProducer: Evaluating " + name + " kernel...");

		for (int i = 0; i < destination.getCount(); i++) {
			T r = null;

			try {
				final int fi = i;
				Object o[] = Stream.of(args)
						.map(arg -> arg.get(fi))
						.collect(Collectors.toList()).toArray();

				r = evaluate(o);
				if (r == null) r = replaceNull(o);

				destination.set(i, r);
			} catch (CLException e) {
				System.out.println("ERROR: i = " + i + " of " + destination.getCount() + ", r = " + r);
				throw e;
			}
		}
	}

	default T replaceNull(Object args[]) {
		throw new NullPointerException();
	}

	MemoryBank<T> createKernelDestination(int size);
}
