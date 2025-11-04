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

package org.almostrealism.bool;

import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.function.Supplier;

@Deprecated
public class AcceleratedConjunctionCollection extends AcceleratedConjunctionAdapter<PackedCollection<?>>
		implements AcceleratedConditionalStatement<PackedCollection<?>>,
						CollectionProducerComputation<PackedCollection<?>> {

	@Override
	public TraversalPolicy getShape() {
		return new TraversalPolicy(1);
	}

	@SafeVarargs
	public AcceleratedConjunctionCollection(Supplier trueValue, Supplier falseValue,
										AcceleratedConditionalStatement<PackedCollection<?>>... conjuncts) {
		super(1, PackedCollection.bank(new TraversalPolicy(1)), trueValue, falseValue, conjuncts);
	}
}
