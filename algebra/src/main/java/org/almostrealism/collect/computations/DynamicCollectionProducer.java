/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerForMemoryData;

import java.util.function.Function;
import java.util.stream.Stream;

public class DynamicCollectionProducer<T extends PackedCollection<?>> extends DynamicProducerForMemoryData<T> implements CollectionProducer<T> {
	private TraversalPolicy shape;
	private boolean kernel;
	private boolean fixedCount;

	private Function<PackedCollection<?>[], Function<Object[], T>> inputFunction;
	private Producer<?> args[];

	public DynamicCollectionProducer(TraversalPolicy shape, Function<Object[], T> function) {
		this(shape, function, true);
	}

	public DynamicCollectionProducer(TraversalPolicy shape, Function<Object[], T> function, boolean kernel) {
		this(shape, function, kernel, true);
	}

	public DynamicCollectionProducer(TraversalPolicy shape, Function<Object[], T> function,
									 boolean kernel, boolean fixedCount) {
		this(shape, inputs -> function, kernel, fixedCount, new Producer[0]);
	}

	public DynamicCollectionProducer(TraversalPolicy shape, Function<PackedCollection<?>[], Function<Object[], T>> function,
										boolean kernel, boolean fixedCount, Producer argument, Producer<?>... args) {
		this(shape, function, kernel, fixedCount, Stream.concat(Stream.of(argument), Stream.of(args)).toArray(Producer[]::new));
	}

	public DynamicCollectionProducer(TraversalPolicy shape, Function<PackedCollection<?>[], Function<Object[], T>> function,
									 boolean kernel, boolean fixedCount, Producer args[]) {
		super(args.length > 0 ? null : function.apply(null),
				len -> new PackedCollection(shape.prependDimension(len)));
		this.shape = shape;
		this.kernel = kernel;
		this.fixedCount = fixedCount;

		this.inputFunction = function;
		this.args = args;
	}

	@Override
	public TraversalPolicy getShape() { return shape; }

	@Override
	public long getOutputSize() { return getShape().getTotalSize(); }

	@Override
	public boolean isFixedCount() { return fixedCount; }

	@Override
	protected Function<Object[], T> getFunction() {
		if (args.length > 0) {
			Evaluable eval[] = Stream.of(args).map(Producer::get).toArray(Evaluable[]::new);
			return args -> {
				PackedCollection[] inputs = Stream.of(eval)
						.map(ev -> ev.evaluate(args))
						.toArray(PackedCollection[]::new);
				Function<Object[], T> func = inputFunction.apply(inputs);
				return func.apply(args);
			};
		}

		return super.getFunction();
	}

	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	@Override
	public CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}

	@Override
	public Evaluable<T> get() {
		if (kernel) {
			return super.get();
		} else {
			return getFunction()::apply;
		}
	}
}
