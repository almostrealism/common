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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class Choice<T extends MemoryData> extends ProducerComputationBase<T, T> implements DestinationSupport<MemoryData>, ComputerFeatures {
	private int memLength;
	private int choiceCount;
	private Supplier<MemoryData> destination;

	public Choice(int memLength, int choiceCount, Supplier<T> blankValue,
				  IntFunction<MemoryBank<T>> kernelDestination,
				  Supplier<Evaluable<? extends Scalar>> decision,
				  Supplier<Evaluable<? extends MemoryBank<T>>> choices) {
		this.memLength = memLength;
		this.choiceCount = choiceCount;
		this.destination = (Supplier) blankValue;

		List inputs = new ArrayList();
		inputs.add(new MemoryDataDestination(this, kernelDestination));
		inputs.add(decision);
		inputs.add(choices);
		setInputs(inputs);

		init();
	}

	/** @return  GLOBAL */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public void setDestination(Supplier<MemoryData> destination) { this.destination = destination; }

	@Override
	public Supplier<MemoryData> getDestination() { return destination; }

	public Scope<T> getScope() {
		HybridScope<T> scope = new HybridScope<>(this);
		scope.getVariables().addAll(getVariables());
		Consumer<String> code = scope.code();

		ArrayVariable<?> output = getArgument(0, memLength);
		ArrayVariable<?> input = getArgument(2, memLength * choiceCount);
		String decision = getArgument(1, 2).valueAt(0).getExpression();
		String choices = stringForDouble(choiceCount);
		String decisionChoice = "floor(" + decision + " * " + choices + ") * " + memLength;

		for (int i = 0; i < memLength; i++) {
			code.accept(output.valueAt(i).getExpression() + " = " + input.get(decisionChoice + " + " + i).getExpression() + ";\n");
		}

		return scope;
	}
}
