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
import io.almostrealism.compute.ComputableProcessContext;
import io.almostrealism.compute.ParallelProcessContext;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AggregatedProducerComputation<T extends PackedCollection<?>> extends TraversableRepeatedProducerComputation<T> {
	public static boolean enableTransitiveDelta = true;
	public static boolean enableChainRule = false;
	public static boolean enableIndexSimplification = true;
	public static boolean enableIndexCache = false;
	public static boolean enableLogging = false;

	private BiFunction<Expression, Expression, Expression> expression;
	private boolean replaceLoop;

	private TraversableExpression<Double> inputArg;
	private DefaultIndex row, ref;
	private Expression<Integer> uniqueOffset;
	private Expression<? extends Number> uniqueIndex;

	private Map<String, Expression<?>> indexCache;

	public AggregatedProducerComputation(String name, TraversalPolicy shape, int count,
										 BiFunction<TraversableExpression[], Expression, Expression> initial,
										 BiFunction<Expression, Expression, Expression> expression,
										 Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(name, shape, count, initial, null, arguments);
		this.expression = expression;
		this.count = count;

		if (enableIndexCache)
			indexCache = new HashMap<>();

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
	public boolean isChainRuleSupported() {
		return enableChainRule || super.isChainRuleSupported();
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

	protected Expression<?> checkCache(Expression<?> index) {
		if (indexCache == null || index.countNodes() > 100) return null;

		String key = index.getExpression(Expression.defaultLanguage());
		if (indexCache.containsKey(key)) {
			if (enableLogging)
				log("Using cached value for index " + key);

			return indexCache.get(key);
		}

		return null;
	}

	protected Expression<Double> cache(Expression<?> index, Expression<Double> result) {
		if (indexCache == null || index.countNodes() > 100) return result;

		String key = index.getExpression(Expression.defaultLanguage());
		indexCache.put(key, result);
		return result;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (uniqueIndex == null) {
			if (enableIndexSimplification) {
				index = index.simplify();
			}

			Expression e = checkCache(index);
			if (e != null) return e;

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

			return cache(index, value);
		} else {
			Expression uniqueIndex = index
					.multiply(Math.toIntExact(ref.getLimit().getAsLong()))
					.add(uniqueOffset.withIndex(row, index));
			return inputArg.getValueAt(uniqueIndex);
		}
	}

	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		if (globalIndex instanceof KernelIndex) {
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
	public ParallelProcessContext createContext(ProcessContext ctx) {
		return ComputableProcessContext.of(ctx, this, count);
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<?> delta = attemptDelta(target);
		if (delta != null) return (CollectionProducer) delta;

		if (enableTransitiveDelta && getInputs().size() == 2 && getInputs().get(1) instanceof CollectionProducer) {
			int outLength = ((CollectionProducer<T>) getInputs().get(1)).getShape().getTotalSize();
			int inLength = shape(target).getTotalSize();

			if (AlgebraFeatures.match(getInputs().get(1), target)) {
				delta = identity(shape(inLength, outLength))
						.reshape(inLength, outLength, 1).traverse(0);
			} else {
				delta = ((CollectionProducer) getInputs().get(1)).delta(target);
				delta = delta.reshape(outLength, inLength);
				delta = delta.transpose();
			}

			delta = delta.enumerate(1, count).traverse(2);
			return new AggregatedProducerComputation<>(getName(), shape(delta).replace(shape(1)),
						count, initial, expression, (Supplier) delta)
					.setReplaceLoop(isReplaceLoop())
					.reshape(getShape().append(shape(target)));
		} else {
			delta = super.delta(target);
			if (delta instanceof ConstantRepeatedDeltaComputation) {
				TraversableDeltaComputation<T> traversable = TraversableDeltaComputation.create("delta", getShape(), shape(target),
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
		AggregatedProducerComputation<T> c = new AggregatedProducerComputation<>(getName(), getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
		c.setReplaceLoop(replaceLoop);
		return c;
	}
}
