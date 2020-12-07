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

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.expressions.Expression;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

public class AcceleratedPassThroughProducer<T extends MemWrapper>
		extends DynamicAcceleratedProducerAdapter<T, T> implements ProducerArgumentReference {
	private int argIndex;
	private int kernelIndex;

	public AcceleratedPassThroughProducer(int memLength, int argIndex) {
		this(memLength, argIndex, 0);
	}

	public AcceleratedPassThroughProducer(int memLength, int argIndex, int kernelIndex) {
		super(memLength, null);
		this.argIndex = argIndex;
		this.kernelIndex = kernelIndex;

		ArrayVariable result = new ArrayVariable(this, "", null);
		result.setSortHint(-1);

		List<ArrayVariable<? extends T>> args = new ArrayList<>();
		args.add(result);
		args.addAll(Arrays.asList(arguments(this)));
		setArguments(args);
		initArgumentNames();
	}

	@Override
	public Evaluable<T> get() { return compileProducer(this); }
//
//	/**
//	 * Returns an empty scope, as this is not intended to be converted.
//	 */
//	@Override
//	public Scope<T> getScope(NameProvider p) {
//		return new Scope<>();
//	}

	@Override
	public void compact() {
		// Avoid recursion, do not compact children
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> new Expression<>(Double.class, getArgumentValueName(1, pos, kernelIndex), getArgument(1));
	}

	@Override
	public int getReferencedArgumentIndex() { return argIndex; }
}
