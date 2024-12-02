/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.RelativeTraversableExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AggregatedProducerComputation<T extends PackedCollection<?>> extends TraversableRepeatedProducerComputation<T> {
	public static boolean enableTransitiveDelta = true;
	public static boolean enableContextualKernelIndex = true;
	public static boolean enableLogging = false;

	private BiFunction<Expression, Expression, Expression> expression;
	private boolean replaceLoop;

	private TraversableExpression<Double> inputArg;
	private DefaultIndex row, ref;
	private Expression<Integer> uniqueOffset;
	private Expression<? extends Number> uniqueIndex;

	public AggregatedProducerComputation(TraversalPolicy shape, int count,
										 BiFunction<TraversableExpression[], Expression, Expression> initial,
										 BiFunction<Expression, Expression, Expression> expression,
										 Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(shape, count, initial, null, arguments);
		this.expression = expression;
		this.count = count;

		if (enableLogging)
			log("Created AggregatedProducerComputation (" + count + " items)");
	}

	public boolean isReplaceLoop() {
		return replaceLoop;
	}

	public AggregatedProducerComputation<T> setReplaceLoop(boolean replaceLoop) {
		this.replaceLoop = replaceLoop;
		return this;
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		if (!replaceLoop || getMemLength() > 1 || getIndexLimit().isEmpty())
			return;

		if (context == null) {
			throw new UnsupportedOperationException();
		}

		if (isFixedCount()) {
			inputArg = getCollectionArgumentVariable(1);
			if (inputArg == null) return;
			if (inputArg.isRelative()) {
				throw new UnsupportedOperationException();
			}

			row = new DefaultIndex(getVariablePrefix() + "_g");
			row.setLimit(getShape().getCountLong());

			ref = new DefaultIndex(getVariablePrefix() + "_i");
			getIndexLimit().ifPresent(ref::setLimit);

			Expression index = Index.child(row, ref);
			uniqueOffset = inputArg.uniqueNonZeroOffset(row, ref, index);
			if (uniqueOffset == null) {
				if (enableLogging)
					log("Unable to determine unique offset for AggregatedProducerComputation:" +
							getMetadata().getId() + " (" + count + " items)");

				return;
			}

			uniqueIndex = row
					.multiply(Math.toIntExact(ref.getLimit().getAsLong()))
					.add(uniqueOffset);

			if (enableLogging) {
				log("Unique offset for AggregatedProducerComputation:" +
						getMetadata().getId() + " (" + count + " items) is " +
						uniqueOffset.getExpressionSummary());
			}
		}
	}

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		if (uniqueIndex == null) return super.getScope(context);

		Scope<T> scope = new Scope<>(getFunctionName(), getMetadata());

		Expression<?> out = getDestination(new KernelIndex(context), ref, e(0));
		Expression<?> val = inputArg.getValueAt(uniqueIndex.withIndex(row, new KernelIndex(context)));
		scope.getStatements().add(out.assign(val));
		return scope;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (uniqueIndex == null) {
			TraversableExpression args[] = getTraversableArguments(index);

			Expression value = initial.apply(args, e(0));
			if (enableLogging)
				log("Generating values for aggregation " + getShape().toStringDetail());

			for (int i = 0; i < count; i++) {
				value = expression.apply(value, args[1].getValueRelative(e(i)));

				if (enableLogging)
					log("Added value " + i + "/" + count + " (" + value.countNodes() + " total nodes)");

				value = value.generate(value.flatten());

				if (enableLogging && value.countNodes() > 10000000) {
					log("Returning early due to excessive node count (" + value.countNodes() + ")");
					return value;
				}
			}

			return value;
		} else {
			Expression uniqueIndex = index
					.multiply(Math.toIntExact(ref.getLimit().getAsLong()))
					.add(uniqueOffset.withIndex(row, index));
			return inputArg.getValueAt(uniqueIndex);
		}
	}

	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		if (enableContextualKernelIndex && globalIndex instanceof KernelIndex) {
			Expression currentValue = ((CollectionVariable) ((RelativeTraversableExpression) args[0]).getExpression())
					.referenceRelative(new IntegerConstant(0), (KernelIndex) globalIndex);
			return expression.apply(currentValue, args[1].getValueRelative(localIndex));
		} else {
			Expression currentValue = ((CollectionVariable) ((RelativeTraversableExpression) args[0]).getExpression())
					.referenceRelative(new IntegerConstant(0));
			return expression.apply(currentValue, args[1].getValueRelative(localIndex));
		}
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (uniqueOffset == null)
			return null;

		return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<?> delta = attemptDelta(this, target);
		if (delta != null) return (CollectionProducer) delta;

		if (enableTransitiveDelta && getInputs().size() == 2 && getInputs().get(1) instanceof CollectionProducer) {
			int outLength = ((CollectionProducer<T>) getInputs().get(1)).getShape().getTotalSize();
			int inLength = shape(target).getTotalSize();

			delta = ((CollectionProducer) getInputs().get(1)).delta(target);
			delta = delta.reshape(outLength, inLength);
			delta = delta.enumerate(1, 1);
			delta = delta.enumerate(1, count).traverse(2);
			return new AggregatedProducerComputation<>(shape(delta).replace(shape(1)),
						count, initial, expression, (Supplier) delta)
					.setReplaceLoop(isReplaceLoop())
					.reshape(getShape().append(shape(target)));
		} else {
			delta = super.delta(target);
			if (delta instanceof ConstantRepeatedDeltaComputation) {
				TraversableDeltaComputation<T> traversable = TraversableDeltaComputation.create(getShape(), shape(target),
						args -> CollectionExpression.create(getShape(), this::getValueAt), target,
						getInputs().stream().skip(1).toArray(Supplier[]::new));
				traversable.addDependentLifecycle(this);
				((ConstantRepeatedDeltaComputation) delta).setFallback(traversable);
			}

			return (CollectionProducer) delta;
		}
	}

	@Override
	public AggregatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		AggregatedProducerComputation<T> c = new AggregatedProducerComputation<>(getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
		c.setReplaceLoop(replaceLoop);
		return c;
	}
}
