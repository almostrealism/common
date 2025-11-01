/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Computable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public abstract class TransitiveDeltaExpressionComputation<T extends PackedCollection<?>>
												extends TraversableExpressionComputation<T> {
	public static final boolean enableAtomicKernel = true;

	protected TransitiveDeltaExpressionComputation(String name, TraversalPolicy shape,
												   Producer<PackedCollection<?>>... arguments) {
		super(name, shape, MultiTermDeltaStrategy.NONE, arguments);
	}

	@Override
	public boolean isOutputRelative() {
		return !enableAtomicKernel || !isFixedCount();
	}

	@Override
	public boolean isZero() {
		return super.isZero() || getChildren().stream().skip(1).allMatch(Algebraic::isZero);
	}

	@Override
	public boolean isDiagonal(int width) {
		return isIdentity(width) || getChildren().stream().skip(1)
				.allMatch(p -> Algebraic.isDiagonal(width, p));
	}

	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		List<Process<?, ?>> scalars = isDiagonal(width) ? getChildren().stream().skip(1)
				.map(p -> Algebraic.getDiagonalScalar(width, p))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.filter(c -> c instanceof Process)
				.map(c -> (Process<?, ?>) c)
				.collect(Collectors.toList()) : Collections.emptyList();
		if (scalars.size() != getChildren().size() - 1) {
			return super.getDiagonalScalar(width);
		}

		List<Process<?, ?>> operands = new ArrayList<>();
		operands.add(null);
		operands.addAll(scalars);
		return Optional.of(generate(operands));
	}

	protected boolean isTransitiveArgumentIndex(int index) {
		return index > 0;
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = attemptDelta(target);
		if (delta != null) return delta;

		TraversalPolicy targetShape = shape(target);

		List<CollectionProducer<PackedCollection<?>>> operands = List.of(
				getChildren().stream().skip(1)
						.filter(p -> p instanceof CollectionProducer)
						.toArray(CollectionProducer[]::new));

		boolean supported = true;

		if (operands.size() != getChildren().size() - 1) {
			supported = false;
		} else if (operands.stream().anyMatch(o -> !o.isFixedCount())) {
			warn("Transitive delta not implemented for variable count operands");
			supported = false;
		}

		if (!supported) {
			return super.delta(target);
		}

		List<Process<?, ?>> deltas = new ArrayList<>();
		deltas.add(null);

		for (int i = 0; i < operands.size(); i++) {
			if (isTransitiveArgumentIndex(i + 1)) {
				deltas.add((Process) operands.get(i).delta(target));
			} else {
				deltas.add((Process) operands.get(i));
			}
		}

		return generate(deltas).reshape(getShape().append(targetShape));
	}
}
