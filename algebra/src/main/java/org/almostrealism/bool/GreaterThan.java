/*
 * Copyright 2020 Michael Murray
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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class GreaterThan<T extends PackedCollection<?>> extends AcceleratedBinaryConditionAdapter<T> {
	public GreaterThan(int memLength,
					   IntFunction<MemoryBank<T>> kernelDestination,
					   Supplier<Evaluable> leftOperand,
					   Supplier<Evaluable> rightOperand,
					   Supplier<Evaluable<? extends T>> trueValue,
					   Supplier<Evaluable<? extends T>> falseValue,
					   boolean includeEqual) {
		super(includeEqual ? ">=" : ">", memLength, kernelDestination,
				leftOperand, rightOperand, trueValue, falseValue);
	}
}
