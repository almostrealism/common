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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.KernelizedEvaluable;

import java.util.function.Supplier;

public class PackedCollectionFromPackedCollection extends ValueFromPackedCollection<PackedCollection<?>> implements CollectionProducer<PackedCollection<?>> {
	public PackedCollectionFromPackedCollection(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection>> collection, Supplier<Evaluable<? extends Scalar>> index) {
		super(shape, PackedCollection.blank(1), PackedCollection.bank(new TraversalPolicy(1)),
				collection, index);
	}

	public TraversalPolicy getShape() {
		return shape(1);
	}

	@Override
	public KernelizedEvaluable<PackedCollection<?>> get() {
		Evaluable<? extends PackedCollection> out = getInputs().get(0).get();
		Evaluable<? extends PackedCollection> c = getInputs().get(1).get();
		Evaluable<? extends Scalar> i = (Evaluable) getInputs().get(2).get();

		return shortCircuit(args -> {
			PackedCollection<?> collection = c.evaluate(args);
			Scalar index = i.evaluate(args);
			PackedCollection dest = out.evaluate(args);
			dest.setMem(collection.toDouble((int) index.getValue()));
			return dest;
		});
	}
}
