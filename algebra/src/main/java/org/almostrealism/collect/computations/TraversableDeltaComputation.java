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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class TraversableDeltaComputation<T extends PackedCollection<?>>
		extends KernelProducerComputationAdapter<T, T>
		implements ComputerFeatures {
	public static boolean enableTraverseEach = false;

	private Function<TraversableExpression[], CollectionExpression> expression;
	private List<ScopeLifecycle> dependentLifecycles;

	@SafeVarargs
	public TraversableDeltaComputation(TraversalPolicy shape,
											Function<TraversableExpression[], CollectionExpression> expression,
											Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = expression;
		this.dependentLifecycles = new ArrayList<>();
	}

	public void addDependentLifecycle(ScopeLifecycle lifecycle) {
		dependentLifecycles.add(lifecycle);
	}

	protected CollectionExpression getExpression(Expression index) {
		return expression.apply(getTraversableArguments(index));
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		ScopeLifecycle.prepareScope(dependentLifecycles.stream(), manager);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		ScopeLifecycle.prepareArguments(dependentLifecycles.stream(), map);
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		ScopeLifecycle.resetArguments(dependentLifecycles.stream());
	}

	@Override
	public TraversableDeltaComputation<T> generate(List<Process<?, ?>> children) {
		TraversableDeltaComputation<T> result =
				(TraversableDeltaComputation<T>) new TraversableDeltaComputation(getShape(), expression,
					children.stream().skip(1).toArray(Supplier[]::new))
					.setPostprocessor(getPostprocessor()).setShortCircuit(getShortCircuit());
		dependentLifecycles.forEach(result::addDependentLifecycle);
		return result;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(index).getValueAt(index);
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(new IntegerConstant(0)).getValueRelative(index);
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		throw new UnsupportedOperationException();
	}

	private static Supplier[] validateArgs(Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		Stream.of(args).forEach(Objects::requireNonNull);
		return args;
	}

	public static <T extends PackedCollection<?>> TraversableDeltaComputation<T> create(
																TraversalPolicy deltaShape, TraversalPolicy targetShape,
														  	 	Function<TraversableExpression[], CollectionExpression> expression,
															  	Producer<?> target,
														  		Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		TraversalPolicy ds = enableTraverseEach ? deltaShape.traverseEach() : deltaShape;
		return new TraversableDeltaComputation<>(ds.append(targetShape),
				exp -> expression.apply(exp).delta(targetShape, matcher(target)), args);
	}

	private static Function<Expression, Predicate<Expression>> matcher(Producer<?> target) {
		return index -> exp -> {
			if (!(exp instanceof InstanceReference)) return false;

			InstanceReference ref = (InstanceReference) exp;
			Variable v = ref.getReferent();

			w: while (true) {
				if (match(v.getProducer(), target)) {
					break w;
				}

				v = v.getDelegate();
				if (v == null) return false;
			}

			return true;
		};
	}

	private static boolean match(Supplier<?> p, Supplier<?> q) {
		while (p instanceof ReshapeProducer || p instanceof MemoryDataDestination) {
			if (p instanceof ReshapeProducer) {
				p = ((ReshapeProducer<?>) p).getChildren().iterator().next();
			} else {
				p = (Producer<?>) ((MemoryDataDestination) p).getDelegate();
			}
		}

		while (q instanceof ReshapeProducer || q instanceof MemoryDataDestination) {
			if (q instanceof ReshapeProducer) {
				q = ((ReshapeProducer<?>) q).getChildren().iterator().next();
			} else {
				q = (Producer<?>) ((MemoryDataDestination) q).getDelegate();
			}
		}

		if (Objects.equals(p, q)) {
			return true;
		} else if (p instanceof PassThroughProducer && 	q instanceof PassThroughProducer) {
			return ((PassThroughProducer) p).getIndex() == ((PassThroughProducer) q).getIndex();
		}

		return false;
	}
}
