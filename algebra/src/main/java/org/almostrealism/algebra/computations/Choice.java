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

import io.almostrealism.collect.Shape;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Consumer;
import java.util.function.Supplier;

// TODO  Why can't this be a child of TraversableComputationBase?
public class Choice<T extends PackedCollection<?>> extends CollectionProducerComputationBase<T, T> {
	private int choiceCount;

	public Choice(int memLength, int choiceCount, Supplier<Evaluable<? extends Scalar>> decision,
				  Supplier<Evaluable<? extends MemoryBank<T>>> choices) {
		super(null, new TraversalPolicy(memLength).traverse(0), (Supplier) decision, (Supplier) adjustChoices(memLength, choices));
		this.choiceCount = choiceCount;
	}

	public Scope<T> getScope(KernelStructureContext context) {
		HybridScope<T> scope = new HybridScope<>(this);
		scope.getVariables().addAll(getVariables());
		Consumer<String> code = scope.code();

		ArrayVariable<?> output = getArgument(0, getMemLength());
		ArrayVariable<?> input = getArgument(2, getMemLength() * choiceCount);
		Expression decision = getArgument(1, 2).valueAt(0);
		Expression choices = new DoubleConstant((double) choiceCount);
		Expression decisionChoice = decision.multiply(choices).floor().multiply(getMemLength());

		for (int i = 0; i < getMemLength(); i++) {
			code.accept(output.referenceRelative(i).getSimpleExpression(getLanguage()) + " = " +
					input.referenceRelative(decisionChoice.add(i)).getSimpleExpression(getLanguage()) + ";\n");
		}

		return scope;
	}

	protected static <T extends PackedCollection<?>> Supplier<Evaluable<? extends MemoryBank<T>>>
			adjustChoices(int memLength, Supplier<Evaluable<? extends MemoryBank<T>>> choices) {
		if (!(choices instanceof Shape)) return choices;

		TraversalPolicy shape = ((Shape) choices).getShape();
		if (shape.getSize() == memLength && shape.getTraversalAxis() > 0) {
			return CollectionFeatures.getInstance().traverse(shape.getTraversalAxis() - 1, (Producer) choices);
		}

		return choices;
	}
}
