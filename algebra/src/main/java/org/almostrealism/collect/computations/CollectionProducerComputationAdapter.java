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
import io.almostrealism.code.ProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationConsolidationArgumentMap;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.function.Supplier;

public abstract class CollectionProducerComputationAdapter<I extends PackedCollection<?>, O extends PackedCollection<?>>
												extends ProducerComputationAdapter<I, O>
												implements CollectionProducerComputation<O>, MemoryDataComputation<O>,
														KernelizedProducer<O>, DestinationSupport<O>,
														ComputerFeatures {
	private TraversalPolicy shape;
	private Supplier<? extends PackedCollection> destination;

	public CollectionProducerComputationAdapter(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		this.shape = outputShape;
		this.destination = () -> new PackedCollection(shape);
		this.setInputs(CollectionUtils.include(new Supplier[0], new MemoryDataDestination(this, len -> new PackedCollection(getShape().prependDimension(len))), arguments));
		init();
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public int getMemLength() {
		return getShape().getTotalSize();
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
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
		if (destination instanceof DestinationConsolidationArgumentMap.DestinationThreadLocal) {
			((DestinationConsolidationArgumentMap.DestinationThreadLocal) destination).destroy();
		}
	}
}
