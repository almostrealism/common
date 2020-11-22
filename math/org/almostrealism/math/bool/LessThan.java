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
import org.almostrealism.util.Evaluable;

import java.util.function.Function;
import java.util.function.Supplier;

public class LessThan<T extends MemWrapper> extends AcceleratedBinaryConditionAdapter<T> {
	public LessThan(int memLength,
					   Function<Integer, Supplier<Evaluable<T>>> blankValue) {
		this(memLength, blankValue, null, null, null, null);
	}

	public LessThan(int memLength,
					Function<Integer, Supplier<Evaluable<T>>> blankValue,
					Supplier<Evaluable> leftOperand,
					Supplier<Evaluable> rightOperand,
					Supplier<Evaluable<T>> trueValue,
					Supplier<Evaluable<T>> falseValue) {
		this(memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public LessThan(int memLength,
					Supplier<Evaluable<T>> blankValue,
					Supplier leftOperand,
					Supplier rightOperand,
					Supplier trueValue,
					Supplier falseValue,
					boolean includeEqual) {
		super(includeEqual ? "<=" : "<", memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue);
	}
}
