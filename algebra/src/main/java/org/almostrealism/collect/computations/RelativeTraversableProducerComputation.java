/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.RelativeArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class RelativeTraversableProducerComputation<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends CollectionProducerComputationBase<I, O>
		implements TraversableExpression<Double> {

	protected RelativeTraversableProducerComputation() { }

	public RelativeTraversableProducerComputation(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		super(null, outputShape, arguments);
	}

	protected List<ArrayVariable<Double>> getInputArguments(Expression index) {
		List<ArrayVariable<Double>> args = getInputArguments();
		List<ArrayVariable<Double>> relativeArgs = new ArrayList<>();

		for (ArrayVariable v : args) {
			int size = v instanceof CollectionVariable ? ((CollectionVariable) v).getShape().getSize() : getMemLength();

			Expression dim = index.toInt().divide(e(getMemLength())).multiply(size);
			relativeArgs.add(new RelativeArrayVariable(v, dim));
		}

		return relativeArgs;
	}

	@Override
	public Scope<O> getScope(KernelStructureContext context) {
		Scope<O> scope = super.getScope(context);

		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		for (int i = 0; i < getMemLength(); i++) {
			scope.getVariables().add(output.referenceRelative(i).assign(getValueRelative(e(i))));
		}

		return scope;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		OptionalInt i = index.intValue();

		if (i.isPresent()) {
			return getValueFunction().apply(i.getAsInt());
		}

		return null;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		List<ArrayVariable<Double>> args = getInputArguments(index);
		index = index.toInt().mod(e(getMemLength()), false);

		Expression value = getValue(args, 0);

		for (int j = 1; j < getMemLength(); j++) {
			value = conditional(index.eq(e(j)), getValue(args, j), value);
		}

		return value;
	}

	@Override
	public boolean isRelative() { return true; }

	@Deprecated
	public abstract IntFunction<Expression<Double>> getValueFunction();

	// TODO
//	public abstract Expression<Double> getValue(List<ArrayVariable<Double>> args, int index);

	public Expression<Double> getValue(List<ArrayVariable<Double>> args, int index) {
		// System.out.println("WARN: Using default getValue implementation");
		// return getValueFunction().apply(index);
		throw new UnsupportedOperationException();
	}

	/**
	 * Converts this relative traversable computation into a {@link RepeatedProducerComputationAdapter}
	 * for execution using the repeated computation framework.
	 * 
	 * <p>This method enables relative traversable computations to be executed using the
	 * repeated computation pattern, which can provide performance benefits for certain
	 * types of operations and hardware platforms.
	 * 
	 * <p>The conversion is particularly useful for:
	 * <ul>
	 *   <li>Operations that benefit from sequential memory access patterns</li>
	 *   <li>Integration with repeated computation pipelines</li>
	 *   <li>Memory-optimized execution strategies</li>
	 *   <li>Kernel optimization opportunities</li>
	 * </ul>
	 * 
	 * <p><strong>Example Usage:</strong>
	 * <pre>{@code
	 * // Create a relative traversable computation
	 * RelativeTraversableProducerComputation<PackedCollection<?>> relativeOp = 
	 *     new RelativeTraversableProducerComputation<>(...);
	 * 
	 * // Convert to repeated computation for different execution strategy
	 * RepeatedProducerComputationAdapter<PackedCollection<?>> repeatedOp = 
	 *     relativeOp.toRepeated();
	 * 
	 * // Execute using repeated computation framework
	 * PackedCollection<?> result = repeatedOp.get().evaluate(inputs...);
	 * }</pre>
	 * 
	 * <p>The resulting adapter maintains a dependent lifecycle relationship with this
	 * computation to ensure proper coordination of resource management and cleanup.
	 * 
	 * @return A new {@link RepeatedProducerComputationAdapter} that evaluates this
	 *         relative traversable computation using the repeated computation framework
	 * 
	 * @see RepeatedProducerComputationAdapter
	 * @see #addDependentLifecycle(Object)
	 */
	@Override
	public RepeatedProducerComputationAdapter<O> toRepeated() {
		RepeatedProducerComputationAdapter result = new RepeatedProducerComputationAdapter<>(getShape(), this,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
		result.addDependentLifecycle(this);
		return result;
	}
}
