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

import io.almostrealism.expression.DefaultIndex;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.algebra.DeltaAlternate;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class RepeatedProducerComputation<T extends PackedCollection<?>> extends CollectionProducerComputationBase<T, T> implements DeltaAlternate<T> {

	protected BiFunction<TraversableExpression[], Expression, Expression> initial;
	private BiFunction<TraversableExpression[], Expression, Expression> condition;
	protected BiFunction<TraversableExpression[], Expression, Expression> expression;
	private int memLength;

	private CollectionProducer<T> deltaAlternate;

	@SafeVarargs
	public RepeatedProducerComputation(TraversalPolicy shape,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(shape, 1, initial, condition, expression, args);
	}

	@SafeVarargs
	public RepeatedProducerComputation(TraversalPolicy shape, int size,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(null, shape, (Supplier[]) args);
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

	protected void setExpression(BiFunction<TraversableExpression[], Expression, Expression> expression) {
		this.expression = expression;
	}

	@Override
	public CollectionProducer<T> getDeltaAlternate() {
		return deltaAlternate;
	}

	public void setDeltaAlternate(CollectionProducer<T> deltaAlternate) {
		this.deltaAlternate = deltaAlternate;
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
		return getExpression(getTraversableArguments(globalIndex), localIndex);
	}

	protected Expression<?> getExpression(TraversableExpression[] args, Expression localIndex) {
		return expression.apply(args, localIndex);
	}

	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset)	{
		return ((ArrayVariable) getOutputVariable()).referenceRelative(offset);
	}

	@Override
	public RepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new RepeatedProducerComputation<>(
				getShape(), getMemLength(),
				initial, condition, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
