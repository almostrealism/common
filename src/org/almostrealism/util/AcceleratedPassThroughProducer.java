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

package org.almostrealism.util;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.math.MemWrapper;

public class AcceleratedPassThroughProducer<T extends MemWrapper>
		extends DynamicAcceleratedProducerAdapter<T> implements ProducerArgumentReference {
	private int argIndex;
	private int kernelIndex;

	public AcceleratedPassThroughProducer(int memLength, int argIndex) {
		this(memLength, argIndex, 0);
	}

	public AcceleratedPassThroughProducer(int memLength, int argIndex, int kernelIndex) {
		super(memLength);
		this.argIndex = argIndex;
		this.kernelIndex = kernelIndex;
		inputProducers = arguments(Scalar.blank(), this);
		initArgumentNames();
	}

	/**
	 * Returns the argument at the index specified to the constructor of
	 * {@link AcceleratedPassThroughProducer}.
	 */
	@Override
	public T evaluate(Object[] args) {
		return (T) args[argIndex];
	}

	@Override
	public void compact() {
		// Avoid recursion, do not compact children
	}

	@Override
	public String getValue(Argument arg, int pos) {
		return getArgumentValueName(1, pos, kernelIndex);
	}

	@Override
	public int getReferencedArgumentIndex() { return argIndex; }
}
