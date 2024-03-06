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

package org.almostrealism.algebra;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputationBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IndexedExpressionMatcher;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public interface DeltaFeatures extends CollectionFeatures {
	boolean enableChainRule = false;
	boolean enableInputStub = false;
	boolean enableTraversableExpressions = false;

	default <T extends Shape<?>> CollectionProducer<T> generateIsolatedDelta(TraversalPolicy inputShape,
																			 ComputationBase<T, T, Evaluable<T>> producer,
																			 Producer<?> input) {
		if (enableInputStub) {
			CollectionProducerComputation<?> inputStub = inputStub(inputShape);
			ComputationBase delta = (ComputationBase) ((CollectionProducer) replaceInput(producer, input, inputStub)).delta(inputStub);
			return (CollectionProducer<T>) replaceInput(delta, inputStub, input);
		} else {
			return ((CollectionProducer) producer).delta(input);
		}
	}

	default <T extends Shape<?>> CollectionProducer<T> attemptDelta(CollectionProducer<T> producer, Producer<?> target) {
		if (DeltaFeatures.match(producer, target)) {
			TraversalPolicy shape = producer.getShape();
			TraversalPolicy targetShape = shape(target);
			PackedCollection<?> identity =
					new PackedCollection<>(shape(shape.getTotalSize(), targetShape.getTotalSize()))
							.identityFill().reshape(shape.append(targetShape));
			return (CollectionProducer) c(identity);
		}

		if (enableChainRule) {
			Producer<T> in = matchInput(producer, target);
			if (in == target) return null;

			if (in instanceof CollectionProducer) {
				Producer f = generateIsolatedDelta(shape(in), (ComputationBase) producer, in);

				if (f != null) {
					Producer g = ((CollectionProducer<T>) in).delta(target);
					return multiply(f, g);
				}
			}
		}

		return null;
	}

	default <T extends Shape<?>> ComputationBase<T, T, Evaluable<T>> replaceInput(
			ComputationBase<T, T, Evaluable<T>> producer,
			Producer<?> original, Producer<?> replacement) {
		List<Supplier<Evaluable<? extends T>>> inputs = ((ComputationBase) producer).getInputs();
		List<Process<?, ?>> newInputs = new ArrayList<>();
		newInputs.add(null);
		for (int i = 1; i < inputs.size(); i++) {
			Supplier<Evaluable<? extends T>> input = inputs.get(i);
			if (input == original) {
				newInputs.add((Process) replacement);
			} else {
				newInputs.add((Process) input);
			}
		}

		return (ComputationBase<T, T, Evaluable<T>>) producer.generate(newInputs);
	}

	default <T> Producer<T> matchInput(Producer<T> producer, Producer<?> target) {
		if (!(producer instanceof ComputationBase)) return null;

		List<Supplier<Evaluable<? extends T>>> inputs = ((ComputationBase) producer).getInputs();
		List<Producer<T>> matched = new ArrayList<>();

		for (int i = 1; i < inputs.size(); i++) {
			Supplier<Evaluable<? extends T>> input = inputs.get(i);
			if (deepMatch(input, target)) {
				matched.add((Producer<T>) input);
			}
		}

		if (matched.size() == 1) {
			return matched.get(0);
		}

		return null;
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> inputStub(TraversalPolicy shape) {
		return new CollectionProducerComputation<T>() {
			@Override
			public TraversalPolicy getShape() {
				return shape;
			}

			@Override
			public CollectionProducer<T> reshape(TraversalPolicy shape) {
				throw new UnsupportedOperationException();
			}

			@Override
			public CollectionProducer<T> traverse(int axis) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Scope<T> getScope() {
				throw new UnsupportedOperationException();
			}

			@Override
			public Collection<Process<?, ?>> getChildren() {
				return Collections.emptyList();
			}

			@Override
			public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
				return this;
			}
		};
	}

	static IndexedExpressionMatcher matcher(Producer<?> target) {
		return index -> exp -> {
			if (enableTraversableExpressions && target instanceof TraversableExpression) {
				Expression compare = ((TraversableExpression) target).getValueAt(index);
				if (InstanceReference.compareExpressions(compare.getSimplified(), exp.getSimplified())) {
					return true;
				}
			}

			if (!(exp instanceof InstanceReference)) return false;

			InstanceReference ref = (InstanceReference) exp;
			Variable v = ref.getReferent();

			w: while (true) {
				if (match(v.getProducer(), target)) {
					break w;
				}

				v = v.getDelegate();
				if (v == null) {
					return false;
				}
			}

			return true;
		};
	}

	static boolean deepMatch(Supplier<?> p, Supplier<?> target) {
		if (match(p, target)) {
			return true;
		} if (p instanceof Parent) {
			for (Supplier<?> child : ((Parent<Supplier>) p).getChildren()) {
				if (deepMatch(child, target)) {
					return true;
				}
			}
		}

		return false;
	}

	static boolean match(Supplier<?> p, Supplier<?> q) {
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
		} else if (p instanceof CollectionProducerComputation.IsolatedProcess ||
				q instanceof CollectionProducerComputation.IsolatedProcess) {
			Computation.console.features(DeltaFeatures.class)
					.warn("Isolated producer cannot be matched");
		}

		return false;
	}
}
