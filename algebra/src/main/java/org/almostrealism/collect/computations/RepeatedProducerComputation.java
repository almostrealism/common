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

import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class RepeatedProducerComputation<T extends PackedCollection<?>> extends CollectionProducerComputationBase<T, T> {

	protected BiFunction<TraversableExpression[], Expression, Expression> initial;
	private BiFunction<TraversableExpression[], Expression, Expression> condition;
	protected BiFunction<TraversableExpression[], Expression, Expression> expression;
	private int memLength;

	@SafeVarargs
	public RepeatedProducerComputation(String name, TraversalPolicy shape,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(name, shape, 1, initial, condition, expression, args);
	}

	@SafeVarargs
	public RepeatedProducerComputation(String name, TraversalPolicy shape, int size,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(name, shape, (Supplier[]) args);
		this.initial = initial;
		this.condition = condition;
		this.expression = expression;
		this.memLength = size;
	}

	protected void setInitial(BiFunction<TraversableExpression[], Expression, Expression> initial) {
		this.initial = initial;
	}

	protected void setCondition(BiFunction<TraversableExpression[], Expression, Expression> condition) {
		this.condition = condition;
	}

	@Override
	public int getMemLength() { return memLength; }

	protected OptionalInt getIndexLimit() {
		return OptionalInt.empty();
	}

	@Override
	public OperationMetadata getMetadata() {
		OperationMetadata metadata = super.getMetadata();
		if (metadata == null)
			metadata = new OperationMetadata(getFunctionName(), "Repeated");
		return metadata;
	}

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Repeated<T> scope = new Repeated<>(getFunctionName(), getMetadata());
		scope.setInterval(e(getMemLength()));

		String i = getVariablePrefix() + "_i";
		scope.setIndex(new Variable<>(i));

		DefaultIndex ref = new DefaultIndex(i);
		getIndexLimit().ifPresent(ref::setLimit);
		scope.setCondition(condition.apply(getTraversableArguments(ref), ref));

		Expression index = new KernelIndex(context).divide(e(getShape().getSize())).multiply(e(getShape().getSize()));

		if (initial != null) {
			for (int j = 0; j < getMemLength(); j++) {
				Expression<?> out = getDestination(new KernelIndex(context), e(0), e(j));
				Expression<?> val = initial.apply(getTraversableArguments(index), ref.add(j));
				scope.getStatements().add(out.assign(val));
			}
		}

		OperationMetadata bodyMetadata = new OperationMetadata
				(getFunctionName() + "_body",
				"Repeated (Body)");

		Scope<T> body = new Scope<>(getFunctionName() + "_body", bodyMetadata);
		for (int j = 0; j < getMemLength(); j++) {
			Expression<?> out = getDestination(new KernelIndex(context), ref, e(j));
			Expression<?> val = getExpression(index, ref.add(j));
			body.getStatements().add(out.assign(val));
		}

		scope.add(body);
		return scope;
	}

	protected Expression<?> getExpression(Expression globalIndex, Expression localIndex) {
		return getExpression(getTraversableArguments(globalIndex), globalIndex, localIndex);
	}

	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		return expression.apply(args, localIndex);
	}

	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset)	{
		if (globalIndex instanceof KernelIndex) {
			return ((ArrayVariable) getOutputVariable()).referenceRelative(offset, (KernelIndex) globalIndex);
		} else {
			return ((ArrayVariable) getOutputVariable()).referenceRelative(offset);
		}
	}

	@Override
	public RepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new RepeatedProducerComputation<>(
				null, getShape(), getMemLength(),
				initial, condition, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
