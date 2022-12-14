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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;

import java.util.function.Supplier;

public class ScalarFromPackedCollection extends ValueFromPackedCollection<Scalar> implements ScalarProducer {

	public ScalarFromPackedCollection(TraversalPolicy shape, Supplier<Evaluable<? extends PackedCollection>> collection, Supplier<Evaluable<? extends Scalar>> index) {
		super(shape, Scalar.blank(), ScalarBank::new, collection, index);
	}
}
