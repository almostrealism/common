/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.collect.computations;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.code.Computation;

import java.util.function.BiFunction;

public class DefaultCollectionEvaluable<T extends PackedCollection> extends AcceleratedComputationEvaluable<T> implements CollectionEvaluable<T> {
	private TraversalPolicy shape;
	private BiFunction<MemoryData, Integer, T> postprocessor;

	public DefaultCollectionEvaluable(TraversalPolicy shape, Computation<T> c, BiFunction<MemoryData, Integer, T> postprocessor) {
		super(c);
		this.shape = shape;
		this.postprocessor = postprocessor;
	}

	@Override
	protected T postProcessOutput(MemoryData output, int offset) {
		if (postprocessor == null) {
			return (T) new PackedCollection(shape, 0, output, offset);
		} else {
			return postprocessor.apply(output, offset);
		}
	}

	@Override
	public MemoryBank<T> createKernelDestination(int len) {
//		return (MemoryBank) new PackedCollection(shape.prependDimension(size));

		int count = len / this.shape.getCount();

		TraversalPolicy shape;

		// When kernel length is less than, or identical to the output count, an
		// assumption is made that the intended shape is the original shape.
		// This is a bit of a hack, but it's by far the simplest solution
		// available
		if (count == 0 || len == this.shape.getCount()) {
			// It is not necessary to prepend a (usually) unnecessary dimension
			shape = this.shape;
		} else {
			shape = this.shape.prependDimension(count);
		}

		return new PackedCollection<>(shape);
	}

	@Override
	public boolean isAggregatedInput() { return true; }
}
