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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;

import java.util.function.Supplier;

public class GreaterThanCollection extends GreaterThan<PackedCollection<?>> implements AcceleratedConditionalStatementCollection {
	public GreaterThanCollection(
			Supplier leftOperand,
			Supplier rightOperand) {
		super(1, () -> new PackedCollection<>(1), PackedCollection.bank(new TraversalPolicy(1)),
				leftOperand, rightOperand, null, null, false);
	}

	public GreaterThanCollection(
			Supplier leftOperand,
			Supplier rightOperand,
			Supplier trueValue,
			Supplier falseValue) {
		this(leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public GreaterThanCollection(
			Supplier leftOperand,
			Supplier rightOperand,
			Supplier trueValue,
			Supplier falseValue,
			boolean includeEqual) {
		super(1, () -> new PackedCollection<>(1), PackedCollection.bank(new TraversalPolicy(1)),
				leftOperand, rightOperand,
				trueValue, falseValue, includeEqual);
	}

	@Override
	public TraversalPolicy getShape() {
		return new TraversalPolicy(1);
	}
}
