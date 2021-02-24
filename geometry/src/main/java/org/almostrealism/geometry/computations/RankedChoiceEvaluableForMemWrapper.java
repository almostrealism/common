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

package org.almostrealism.geometry.computations;

import org.almostrealism.hardware.DynamicProducerForMemWrapper;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class RankedChoiceEvaluableForMemWrapper<T extends MemWrapper> extends RankedChoiceEvaluable<T> implements KernelizedEvaluable<T> {
	public RankedChoiceEvaluableForMemWrapper(double e) {
		super(e);
	}

	public RankedChoiceEvaluableForMemWrapper(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	public AcceleratedRankedChoiceEvaluable<T> getAccelerated(int memLength, Supplier<T> blankValue, IntFunction<MemoryBank<T>> forKernel) {
		return new AcceleratedRankedChoiceEvaluable(memLength, blankValue, forKernel, this,
												new DynamicProducerForMemWrapper<>(blankValue),
												getEpsilon(), blankValue);
	}
}
