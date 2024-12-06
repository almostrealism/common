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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.InputStub;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.computations.ReshapeProducer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// TODO  Move to calculus package
public interface DeltaFeatures extends MatrixFeatures {
	boolean enableTotalIsolation = false;
	boolean enableRestoreReplacements = false;

	default boolean isChainRuleSupported() {
		return false;
	}

	default <T extends Shape<?>> CollectionProducer<T> generateIsolatedDelta(ComputationBase<T, T, Evaluable<T>> producer,
																			 Producer<?> input) {
		Map<Producer<?>, Producer<?>> replacements = new HashMap<>();
		List toReplace = enableTotalIsolation ? producer.getInputs() : Collections.singletonList(input);

		CollectionProducer isolated = (CollectionProducer) replaceInput(producer, toReplace, replacements);
		CollectionProducer delta = isolated.delta(replacements.get(input));

		if (enableRestoreReplacements) {
			List<Supplier> restore = new ArrayList();
			Map<Producer<?>, Producer<?>> originals = new HashMap<>();
			replacements.forEach((k, v) -> {
				originals.put(v, k);
				restore.add(v);
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

		if (AlgebraFeatures.cannotMatch(producer, target)) {
			return (CollectionProducer)
					zeros(shape.append(targetShape));
		} else if (AlgebraFeatures.match(producer, target)) {
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

			Optional<Producer<T>> match = matchInput(producer, target);

			if (match == null) {
				return null;
			} else if (match.isEmpty()) {
				return (CollectionProducer<T>) zeros(shape.append(targetShape));
			}

			Producer<T> in = match.get();

			if (AlgebraFeatures.match(in, target)) {
				return applyDeltaStrategy(producer, target);
			}

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

	default <T extends Shape<?>> CollectionProducer<T> applyDeltaStrategy(CollectionProducer<T> producer,
																		  Producer<?> target) {
		return null;
	}

	default <T extends Shape<?>> Function<Collection<Producer<?>>, CollectionProducer<T>>
			deltaStrategyProcessor(MultiTermDeltaStrategy strategy,
					  Function<List<Producer<?>>, CollectionProducer<T>> producerFactory,
					  TraversalPolicy producerShape, Producer<?> target) {
		return terms -> {
			if (strategy == MultiTermDeltaStrategy.NONE) {
				return null;
			}

			long matches = terms.stream()
					.filter(t -> AlgebraFeatures.match(t, target))
					.count();

			if (matches == 0) {
				return (CollectionProducer) CollectionFeatures.getInstance().c(0);
			} else if (matches > 1) {
				return null;
			}

			int pSize = producerShape.getTotalSize();
			int tSize = shape(target).getTotalSize();
			TraversalPolicy finalShape = producerShape.append(shape(target));

			switch (strategy) {
				case IGNORE:
					return (CollectionProducer)
							MatrixFeatures.getInstance().identity(shape(pSize, tSize))
									.reshape(finalShape);
				case COMBINE:
					CollectionProducer result = producerFactory.apply(terms.stream()
							.filter(t -> !AlgebraFeatures.match(t, target))
							.collect(Collectors.toList())).flatten();
					return (CollectionProducer) diagonal(result).reshape(finalShape);
				default:
					throw new IllegalArgumentException();
			}
		};
	}

	default <T extends Shape<?>> CollectionProducer<T> replaceInput(
			Producer<T> producer,
			List<Supplier> toReplace,
			Map<Producer<?>, Producer<?>> replacements) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).generate(List.of(
						replaceInput((Producer) ((ReshapeProducer) producer).getChildren().iterator().next(),
					toReplace, replacements)));
		} else {
			return (CollectionProducer) replaceInput((ComputationBase) producer, toReplace, replacements);
		}
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
				Producer<?> inputStub = replacements.getOrDefault(input, new InputStub((Producer) input));
				newInputs.add((Process) inputStub);
				replacements.put((Producer) input, inputStub);
			} else {
				newInputs.add((Process) input);
			}
		}

		return (ComputationBase<T, T, Evaluable<T>>) producer.generate(newInputs);
	}

	enum MultiTermDeltaStrategy {
		NONE, IGNORE, COMBINE;
	}
}
