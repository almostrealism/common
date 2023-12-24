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

package org.almostrealism.collect.computations;

import io.almostrealism.code.CollectionUtils;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.function.BiFunction;
import java.util.function.Supplier;

public abstract class CollectionProducerComputationBase<I extends PackedCollection<?>, O extends PackedCollection<?>>
												extends ProducerComputationBase<I, O>
												implements CollectionProducerComputation<O>, MemoryDataComputation<O>,
														DestinationSupport<O>,
														ComputerFeatures {
	public static boolean enableDestinationLogging = false;

	private TraversalPolicy shape;
	private Supplier<? extends PackedCollection> destination;
	private BiFunction<MemoryData, Integer, O> postprocessor;
	private Evaluable<O> shortCircuit;

	protected CollectionProducerComputationBase() { }

	public CollectionProducerComputationBase(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		if (outputShape.getTotalSize() <= 0) {
			throw new IllegalArgumentException("Output shape must have a total size greater than 0");
		}

		this.shape = outputShape;
		this.destination = () -> PackedCollection.factory().apply(shape.getTotalSize()).reshape(shape);
		this.setInputs(CollectionUtils.include(new Supplier[0], new MemoryDataDestination(this, this::createDestination), arguments));
		init();
	}

	protected void setShape(TraversalPolicy shape) {
		this.shape = shape;
	}

	protected MemoryBank<?> createDestination(int len) {
		TraversalPolicy shape;

		if (isFixedCount()) {
			shape = getShape();
		} else {
			int count = len / getShape().getCount();

			// When kernel length is less than, or identical to the output count, an
			// assumption is made that the intended shape is the original shape.
			// This is a bit of a hack, but it's by far the simplest solution
			// available
			if (count == 0 || len == getShape().getCount()) {
				// It is not necessary to prepend a (usually) unnecessary dimension
				shape = getShape();
			} else {
				shape = getShape().prependDimension(count);
			}

			if (enableDestinationLogging) {
				log("createDestination(" + len +
						"): " + shape + "[" + shape.getTraversalAxis() + "]");
			}
		}

		return new PackedCollection<>(shape);
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public int getMemLength() {
		return getShape().getSize();
	}

	@Override
	public int getCount() {
		return getShape().getCount();
	}

	@Override
	public boolean isFixedCount() {
		if (!super.isFixedCount()) return false;
		return getShape().getTotalSize() != 1;
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends O>> isolate() {
		return new CollectionProducerComputation.IsolatedProcess<>(this);
	}

	@Override
	public void setDestination(Supplier<O> destination) { this.destination = destination; }

	@Override
	public Supplier<O> getDestination() { return (Supplier) destination; }

	public BiFunction<MemoryData, Integer, O> getPostprocessor() {
		return postprocessor;
	}

	public CollectionProducerComputationBase<I, O> setPostprocessor(BiFunction<MemoryData, Integer, O> postprocessor) {
		this.postprocessor = postprocessor;
		return this;
	}

	public Evaluable<O> getShortCircuit() { return shortCircuit; }

	public CollectionProducerComputationBase<I, O> setShortCircuit(Evaluable<O> shortCircuit) {
		this.shortCircuit = shortCircuit;
		return this;
	}

	/**
	 * @return  PhysicalScope#GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	protected TraversableExpression[] getTraversableArguments(Expression<?> index) {
		TraversableExpression vars[] = new TraversableExpression[getInputs().size()];
		for (int i = 0; i < vars.length; i++) {
			vars[i] = CollectionExpression.traverse(getArgumentForInput(getInputs().get(i)),
					size -> index.toInt().divide(e(getMemLength())).multiply(size));
		}
		return vars;
	}

	public CollectionVariable getCollectionArgumentVariable(int argIndex) {
		ArrayVariable<?> arg = getArgumentForInput(getInputs().get(argIndex));

		if (arg instanceof CollectionVariable) {
			return (CollectionVariable) arg;
		} else {
			return null;
		}
	}

	@Override
	public Evaluable<O> get() {
		HardwareEvaluable ev = new HardwareEvaluable<>(() -> CollectionProducerComputation.super.get(), null, shortCircuit, true);
		ev.setDestinationValidation(destination -> {
			if (destination instanceof Shape) {
				if (getShape().getSize() > 1 && ((Shape) destination).getShape().getSize() != getShape().getSize()) {
					throw new IllegalArgumentException();
				}
			}
		});
		return ev;
	}

	@Override
	public O postProcessOutput(MemoryData output, int offset) {
		return getPostprocessor() == null ? CollectionProducerComputation.super.postProcessOutput(output, offset) : getPostprocessor().apply(output, offset);
	}

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
	}

	public static void destinationLog(Runnable r) {
		boolean log = enableDestinationLogging;

		try {
			enableDestinationLogging = true;
			r.run();
		} finally {
			enableDestinationLogging = log;
		}
	}
}
