/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * An abstract extension of {@link RankedChoiceEvaluable} for memory-based data types.
 * This class provides the foundation for hardware-accelerated ranked choice operations
 * on types that implement {@link MemoryData}.
 *
 * @param <T> the type of memory data produced
 * @author Michael Murray
 * @see RankedChoiceEvaluable
 * @see MemoryData
 */
public abstract class RankedChoiceEvaluableForMemoryData<T extends MemoryData> extends RankedChoiceEvaluable<T> implements Evaluable<T> {
	/**
	 * Constructs a RankedChoiceEvaluableForMemoryData with the specified epsilon.
	 *
	 * @param e the epsilon threshold
	 */
	public RankedChoiceEvaluableForMemoryData(double e) {
		super(e);
	}

	/**
	 * Constructs a RankedChoiceEvaluableForMemoryData with the specified epsilon.
	 *
	 * @param e the epsilon threshold
	 * @param tolerateNull whether to allow null results
	 */
	public RankedChoiceEvaluableForMemoryData(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	/**
	 * Returns a hardware-accelerated version of this evaluable.
	 * Subclasses should override this to provide actual acceleration.
	 *
	 * @param memLength the memory length of the output type
	 * @param blankValue a supplier for blank output values
	 * @param forKernel a function to create memory banks for kernel execution
	 * @return an accelerated evaluable
	 * @throws UnsupportedOperationException if acceleration is not implemented
	 */
	public Evaluable<T> getAccelerated(int memLength, Supplier<T> blankValue, IntFunction<MemoryBank<T>> forKernel) {
		throw new UnsupportedOperationException();
	}
}
