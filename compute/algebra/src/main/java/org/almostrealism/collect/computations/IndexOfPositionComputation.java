/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.IndexOfPositionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Stream;

/**
 * A computation that maps multi-dimensional position producers to linear indices
 * within a target shape.
 *
 * <p>This is the computation behind {@code CollectionFeatures#index(TraversalPolicy,
 * TraversalPolicy, Producer...)}, and hence sits inside every {@code valueAt(...)}
 * gather chain. The target shape ({@code positionShape}) parameterizes the generated
 * {@link IndexOfPositionExpression} but is not one of the computation's inputs, so the
 * {@link #signature()} appends it explicitly — following the precedent of
 * {@link ArithmeticSequenceComputation#signature()}. Without a signature here, every
 * enclosing gather chain had a null signature and recompiled on each use; without the
 * shape in the signature, two index computations over different target geometries
 * could incorrectly share one compiled kernel.</p>
 *
 * @see IndexOfPositionExpression
 * @see ArithmeticSequenceComputation
 *
 * @author Michael Murray
 */
public class IndexOfPositionComputation extends TraversableExpressionComputation {
	/**
	 * The shape defining how positions map to linear indices. This parameterizes the
	 * generated expression but is not an input, so it is part of the computation's
	 * identity and appears in the {@link #signature()}.
	 */
	private final TraversalPolicy positionShape;

	/**
	 * Constructs an index computation for the given output shape and target shape.
	 *
	 * @param shape The output shape of the computed indices
	 * @param positionShape The shape defining how positions map to linear indices
	 * @param pos The position producers, one per dimension of the target shape
	 */
	@SafeVarargs
	public IndexOfPositionComputation(TraversalPolicy shape, TraversalPolicy positionShape,
									  Producer<PackedCollection>... pos) {
		super("index", shape, pos);
		this.positionShape = positionShape;
		init();
	}

	/**
	 * Returns a signature that includes the target shape used for index computation,
	 * ensuring index computations over different target geometries produce different
	 * compiled kernels while structurally identical ones reuse one compiled program.
	 *
	 * @return A signature string including the target shape, or null if the parent
	 *         signature is null or the shape is not yet assigned (during base class
	 *         construction)
	 */
	@Override
	public String signature() {
		if (positionShape == null) return null;

		String signature = super.signature();
		if (signature == null) return null;

		return signature + "{" + positionShape.toStringDetail() + "}";
	}

	/**
	 * Creates the expression that computes linear indices from the position inputs.
	 *
	 * @param args Array of {@link TraversableExpression}s; the first entry is the
	 *             destination and the remainder are the position inputs
	 * @return An {@link IndexOfPositionExpression} over the position inputs
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new IndexOfPositionExpression(getShape(), positionShape,
				Stream.of(args).skip(1).toArray(TraversableExpression[]::new));
	}

	/**
	 * Generates a new index computation over the specified child processes,
	 * preserving the target shape.
	 *
	 * @param children List of child {@link Process} instances; the first entry is the
	 *                 destination and the remainder are the position inputs
	 * @return A new {@link IndexOfPositionComputation} with the specified children
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new IndexOfPositionComputation(getShape(), positionShape,
				children.stream().skip(1).toArray(Producer[]::new));
	}
}
