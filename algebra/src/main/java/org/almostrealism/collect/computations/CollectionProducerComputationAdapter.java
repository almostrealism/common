/*
 * Copyright 2022 Michael Murray
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
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationConsolidationArgumentMap;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.function.Supplier;

public abstract class CollectionProducerComputationAdapter<I extends PackedCollection<?>, O extends PackedCollection<?>>
												extends ProducerComputationBase<I, O>
												implements CollectionProducerComputation<O>, MemoryDataComputation<O>,
														KernelizedProducer<O>, DestinationSupport<O>,
														ComputerFeatures {
	public static boolean enableEmbeddedInputs = true;

	private TraversalPolicy shape;
	private Supplier<? extends PackedCollection> destination;

	protected CollectionProducerComputationAdapter() { }

	public CollectionProducerComputationAdapter(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		if (outputShape.getTotalSize() <= 0) {
			throw new IllegalArgumentException("Output shape must have a total size greater than 0");
		}

		this.shape = outputShape;
		this.destination = () -> new PackedCollection(shape);
		this.setInputs(CollectionUtils.include(new Supplier[0], new MemoryDataDestination(this, this::createKernelDestination), arguments));
		init();
	}

	protected void setShape(TraversalPolicy shape) {
		this.shape = shape;
	}

	protected MemoryBank<?> createKernelDestination(int len) {
		if (len > 1 && len % getShape().getCount() != 0) {
			throw new IllegalArgumentException("Kernel length must be a multiple of the shape count");
		}

		int count = len / getShape().getCount();

		// When kernel length as 1, an assumption is made that the intended shape
		// is the original shape. This is a bit of a hack, but it's by far the
		// simplest solution available
//		if (count == 0 || (len == getShape().length(0) && count == 1)) {
		if (count == 0 || len == getShape().getCount()) {
			// It is not necessary to prepend a (usually) unnecessary dimension
			return new PackedCollection<>(getShape());
		} else {
			return new PackedCollection<>(getShape().prependDimension(count));
		}
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
	public void setDestination(Supplier<O> destination) { this.destination = destination; }

	@Override
	public Supplier<O> getDestination() { return (Supplier) destination; }

	/**
	 * @return  PhysicalScope#GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public Expression<Double> getInputValue(int index, int pos) {
		if (enableEmbeddedInputs) {
			if (getInputs().get(index) instanceof TraversableExpression) {
				Expression<Double> value = ((TraversableExpression) getInputs().get(index)).getValueAt(e(pos));

				// if (!(value instanceof InstanceReference)) return value;
				if (value != null) return value;
			}
		}

		return super.getInputValue(index, pos);
	}

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
		if (destination instanceof DestinationConsolidationArgumentMap.DestinationThreadLocal) {
			((DestinationConsolidationArgumentMap.DestinationThreadLocal) destination).destroy();
		}
	}
}
