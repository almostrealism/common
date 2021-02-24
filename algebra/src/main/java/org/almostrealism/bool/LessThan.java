/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class LessThan<T extends MemWrapper> extends AcceleratedBinaryConditionAdapter<T> {
	public LessThan(int memLength,
					Supplier<T> blankValue,
					IntFunction<MemoryBank<T>> kernelDestination,
					Supplier leftOperand, Supplier rightOperand,
					Supplier trueValue, Supplier falseValue,
					boolean includeEqual) {
		super(includeEqual ? "<=" : "<", memLength, blankValue, kernelDestination, leftOperand, rightOperand, trueValue, falseValue);
	}
}
