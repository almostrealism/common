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

package org.almostrealism.math.bool;

import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.Producer;

import java.util.function.Function;
import java.util.function.Supplier;

public class GreaterThan<T extends MemWrapper> extends AcceleratedBinaryConditionAdapter<T> {
	public GreaterThan(int memLength,
					   Function<Integer, Supplier<Producer<T>>> blankValue) {
		this(memLength, blankValue, null, null, null, null);
	}

	public GreaterThan(int memLength,
					   Function<Integer, Supplier<Producer<T>>> blankValue,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand,
					   Supplier<Producer<T>> trueValue,
					   Supplier<Producer<T>> falseValue) {
		this(memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public GreaterThan(int memLength,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand) {
		this(memLength, null, leftOperand, rightOperand, null, null, false);
	}

	public GreaterThan(int memLength,
					   Supplier<Producer<T>> blankValue,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand,
					   Supplier<Producer<T>> trueValue,
					   Supplier<Producer<T>> falseValue) {
		this(memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue, false);
	}
	public GreaterThan(int memLength,
					   Supplier<Producer<T>> blankValue,
					   Supplier<Producer> leftOperand,
					   Supplier<Producer> rightOperand,
					   Supplier<Producer<T>> trueValue,
					   Supplier<Producer<T>> falseValue,
					   boolean includeEqual) {
		super(includeEqual ? ">=" : ">", memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue);
	}
}
