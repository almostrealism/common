/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

@Deprecated
public abstract class DynamicCollectionProducerComputationAdapter<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends CollectionProducerComputationBase<I, O> {

	protected DynamicCollectionProducerComputationAdapter() { }

	public DynamicCollectionProducerComputationAdapter(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		super(outputShape, arguments);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		// Result should always be first
		// TODO  This causes cascading issues, as the output variable is reused by the referring
		// TODO  producer and then multiple arguments are sorted to be "first"
		ArrayVariable arg = getArgumentForInput(getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);
	}

	@Override
	public Scope<O> getScope() {
		Scope<O> scope = super.getScope();
		IntStream.range(0, getMemLength())
				.mapToObj(getRelativeAssignmentFunction(getOutputVariable()))
				.forEach(v -> scope.getVariables().add((Variable) v));
		return scope;
	}

	// TODO  Assign the "relative" index i to the "relative" value i
	// TODO  Switching this out for absolute indices, by delegating to
	// TODO  getValueAt will simply not work
	protected IntFunction<Variable<Double, ?>> getRelativeAssignmentFunction(Variable<?, ?> outputVariable) {
		return i -> new Variable(((ArrayVariable) outputVariable).valueAt(i).getSimpleExpression(),
				false, getValue(i).simplify(), outputVariable.getRootDelegate());
	}

	// @Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	// @Override
	public Expression<Double> getValueAt(Expression index) {
		OptionalInt i = index.intValue();

		if (i.isPresent()) {
			return getValue(i.getAsInt());
		} else {
			return null;
		}
	}

	@Deprecated
	public abstract Expression getValue(int pos);
}
