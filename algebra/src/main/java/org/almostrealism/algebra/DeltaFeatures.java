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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public interface DeltaFeatures extends MatrixFeatures {
	boolean enableIsolationWarnings = false;
	boolean enableTotalIsolation = false;

	default boolean isChainRuleSupported() {
		return false;
	}

	default <T extends Shape<?>> CollectionProducer<T> generateIsolatedDelta(ComputationBase<T, T, Evaluable<T>> producer,
																			 Producer<?> input) {
		Map<Producer<?>, Producer<?>> replacements = new HashMap<>();
		List toReplace = enableTotalIsolation ? producer.getInputs() : Collections.singletonList(input);

		CollectionProducer isolated = (CollectionProducer) replaceInput(producer, toReplace, replacements);
		ComputationBase delta = (ComputationBase) isolated.delta(replacements.get(input));

		if (enableTotalIsolation) {
			List restore = new ArrayList();
			Map<Producer<?>, Producer<?>> originals = new HashMap<>();
			replacements.forEach((k, v) -> {
				if (k != input) {
					originals.put(v, k);
					restore.add(v);
				}
			});

			delta = replaceInput(delta, restore, originals);
		}

		return (CollectionProducer) delta;
	}

	default <T extends Shape<?>> CollectionProducer<T> attemptDelta(CollectionProducer<T> producer, Producer<?> target) {
		if (producer instanceof DeltaAlternate) {
			CollectionProducer<T> alt = ((DeltaAlternate) producer).getDeltaAlternate();
			if (alt != null) return alt.delta(target);
		}

		TraversalPolicy shape = producer.getShape();
		TraversalPolicy targetShape = shape(target);

		if (DeltaFeatures.match(producer, target)) {
			return (CollectionProducer)
					identity(shape(shape.getTotalSize(), targetShape.getTotalSize()))
						.reshape(shape.append(targetShape));
		}

		if (isChainRuleSupported()) {
			if (!producer.isFixedCount()) {
				Computation.console.features(DeltaFeatures.class)
						.warn("Cannot compute partial delta for variable Producer");
				return null;
			}

			Producer<T> in = matchInput(producer, target);
			if (match(in, target)) return null;
			if (!(in instanceof CollectionProducer)) return null;

			Producer f = generateIsolatedDelta((ComputationBase) producer, in);
			if (f == null) return null;

			Producer g = ((CollectionProducer<T>) in).delta(target);

			int finalLength = shape.getTotalSize();
			int outLength = shape(in).getTotalSize();
			int inLength = shape(target).getTotalSize();

			f = reshape(shape(finalLength, outLength), f);
			g = reshape(shape(outLength, inLength), g);
			return (CollectionProducer) matmul(f, g).reshape(shape.append(targetShape));
		}

		return null;
	}

	default <T extends Shape<?>> ComputationBase<T, T, Evaluable<T>> replaceInput(
			ComputationBase<T, T, Evaluable<T>> producer,
			List<Supplier> toReplace,
			Map<Producer<?>, Producer<?>> replacements) {
		List<Supplier<Evaluable<? extends T>>> inputs = ((ComputationBase) producer).getInputs();
		List<Process<?, ?>> newInputs = new ArrayList<>();
		newInputs.add(null);

		for (int i = 1; i < inputs.size(); i++) {
			Supplier<Evaluable<? extends T>> input = inputs.get(i);

			if (toReplace.contains(input)) {
				Producer<?> inputStub = replacements.getOrDefault(input,
						inputStub(shape(input), Countable.isFixedCount(input)));
				newInputs.add((Process) inputStub);
				replacements.put((Producer) input, inputStub);
			} else {
				newInputs.add((Process) input);
			}
		}

		return (ComputationBase<T, T, Evaluable<T>>) producer.generate(newInputs);
	}

	default <T> List<Producer<T>> matchingInputs(Producer<T> producer, Producer<?> target) {
		if (!(producer instanceof ComputationBase)) return Collections.emptyList();

		List<Supplier<Evaluable<? extends T>>> inputs = ((ComputationBase) producer).getInputs();
		List<Producer<T>> matched = new ArrayList<>();

		for (int i = 1; i < inputs.size(); i++) {
			Supplier<Evaluable<? extends T>> input = inputs.get(i);
			if (deepMatch(input, target)) {
				matched.add((Producer<T>) input);
			}
		}

		return matched;
	}

	default <T> Producer<T> matchInput(Producer<T> producer, Producer<?> target) {
		List<Producer<T>> matched = matchingInputs(producer, target);

		if (matched.size() == 1) {
			return matched.get(0);
		}

		return null;
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> inputStub(TraversalPolicy shape, boolean fixedCount) {
		return new CollectionProducerComputation<T>() {
			@Override
			public long getCountLong() {
				return CollectionProducerComputation.super.getCountLong();
			}

			@Override
			public boolean isFixedCount() {
				return fixedCount;
			}

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
			public Scope<T> getScope(KernelStructureContext context) {
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

			@Override
			public Evaluable<T> get() {
				return null;
			}
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
		while (p instanceof ReshapeProducer || p instanceof MemoryDataDestinationProducer) {
			if (p instanceof ReshapeProducer) {
				p = ((ReshapeProducer<?>) p).getChildren().iterator().next();
			} else {
				p = (Producer<?>) ((MemoryDataDestinationProducer) p).getDelegate();
			}
		}

		while (q instanceof ReshapeProducer || q instanceof MemoryDataDestinationProducer) {
			if (q instanceof ReshapeProducer) {
				q = ((ReshapeProducer<?>) q).getChildren().iterator().next();
			} else {
				q = (Producer<?>) ((MemoryDataDestinationProducer) q).getDelegate();
			}
		}

		if (Objects.equals(p, q)) {
			return true;
		} else if (p instanceof PassThroughProducer && 	q instanceof PassThroughProducer) {
			return ((PassThroughProducer) p).getReferencedArgumentIndex() == ((PassThroughProducer) q).getReferencedArgumentIndex();
		} else if (enableIsolationWarnings &&
				(p instanceof CollectionProducerComputation.IsolatedProcess ||
				q instanceof CollectionProducerComputation.IsolatedProcess)) {
			Computation.console.features(DeltaFeatures.class)
					.warn("Isolated producer cannot be matched");
		}

		return false;
	}
}
