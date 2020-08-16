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

package org.almostrealism.math;

import org.almostrealism.util.Producer;

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
		kernelEvaluate(destination, args, 0, destination.getCount());
	}

	default void kernelEvaluate(MemoryBank destination, MemoryBank args[], int offset, int length) {
		for (int i = 0; i < length; i++) {
			final int fi = i;
			destination.set(offset + i,
					evaluate(Stream.of(args)
							.map(arg -> arg.get(offset + fi))
							.collect(Collectors.toList()).toArray()));
		}
	}
}
