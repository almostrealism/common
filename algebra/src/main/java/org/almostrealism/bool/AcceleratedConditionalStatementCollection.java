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

import io.almostrealism.code.CollectionUtils;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

public interface AcceleratedConditionalStatementCollection extends AcceleratedConditionalStatement<PackedCollection<?>>, CollectionProducer<PackedCollection<?>> {
	default AcceleratedConjunctionCollection and(AcceleratedConditionalStatement<PackedCollection<?>> operand, Supplier<Evaluable<PackedCollection<?>>> trueValue, Supplier<Evaluable<PackedCollection<?>>> falseValue) {
		return and(trueValue, falseValue, operand);
	}

	default AcceleratedConjunctionCollection and(Supplier<Evaluable<PackedCollection<?>>> trueValue, Supplier<Evaluable<PackedCollection<?>>> falseValue, AcceleratedConditionalStatement<PackedCollection<?>>... operands) {
		return new AcceleratedConjunctionCollection(trueValue, falseValue,
				CollectionUtils.include(new AcceleratedConditionalStatement[0], this, operands));
	}
}
