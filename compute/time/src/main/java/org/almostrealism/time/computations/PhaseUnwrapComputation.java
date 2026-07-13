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

package org.almostrealism.time.computations;

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.PrefixAccumulationComputation;

import java.util.List;

/**
 * A {@link PrefixAccumulationComputation} that unwraps phase angles to produce a continuous
 * phase sequence, as a single compiled kernel.
 *
 * <p>Phase values from {@code atan2} are wrapped to [-PI, PI]. Unwrapping detects
 * discontinuities (jumps greater than PI between consecutive values) and adjusts by
 * 2 * PI to make the sequence continuous:</p>
 *
 * <pre>
 * unwrapped[i] = wrapped[0] + sum over j in 1..i of correct(wrapped[j] - wrapped[j - 1])
 * </pre>
 *
 * <p>where {@code correct} maps a difference into [-PI, PI] by adding or subtracting
 * 2 * PI. This is an inclusive prefix sum whose term at position 0 is the first wrapped
 * value and whose term at position j is the corrected consecutive difference.</p>
 *
 * <p>Because inputs are wrapped to [-PI, PI] by construction, consecutive differences lie
 * within (-2 * PI, 2 * PI) and a single correction suffices.</p>
 *
 * @see PrefixAccumulationComputation
 * @see org.almostrealism.time.TemporalFeatures#unwrapPhase(PackedCollection)
 *
 * @author Michael Murray
 */
public class PhaseUnwrapComputation extends PrefixAccumulationComputation {

	/**
	 * Creates a phase unwrapping computation over the given wrapped phase values.
	 *
	 * @param shape The output {@link TraversalPolicy}, matching the input shape with each
	 *              element traversed by its own kernel thread
	 * @param input The wrapped phase values (in [-PI, PI], as produced by atan2)
	 */
	public PhaseUnwrapComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("unwrapPhase", shape, false, input);
	}

	/**
	 * Returns the additive identity, 0.0.
	 *
	 * @return An {@link Expression} for 0.0
	 */
	@Override
	protected Expression<?> identity() { return e(0.0); }

	/**
	 * Returns the accumulator plus the factor.
	 *
	 * @param accumulator The accumulated phase so far
	 * @param factor      The factor to absorb
	 * @return An {@link Expression} for the sum
	 */
	@Override
	protected Expression<?> combine(Expression<?> accumulator, Expression<?> factor) {
		return accumulator.add(factor);
	}

	/**
	 * Returns the term at the given position: the first wrapped value at position 0, and the
	 * wrap-corrected consecutive difference elsewhere. The previous-element read is clamped to
	 * position 0 so that no out-of-bounds access occurs even when both conditional branches
	 * are evaluated by the generated code.
	 *
	 * @param input    The traversable wrapped phase values
	 * @param position The input element index
	 * @return An {@link Expression} for the term at that position
	 */
	@Override
	protected Expression<?> term(TraversableExpression input, Expression<?> position) {
		Expression current = input.getValueAt(position);
		Expression previous = input.getValueAt(
				conditional(position.eq(e(0)), e(0), position.subtract(e(1))));

		Expression diff = current.subtract(previous);
		Expression corrected = conditional(diff.greaterThan(e(Math.PI)),
				diff.subtract(e(2 * Math.PI)),
				conditional(diff.lessThan(e(-Math.PI)), diff.add(e(2 * Math.PI)), diff));

		return conditional(position.eq(e(0)), current, corrected);
	}

	/**
	 * Generates a new instance of this computation with updated child processes.
	 *
	 * @param children The child processes, where the first element is the output destination
	 * @return A new {@link PhaseUnwrapComputation} over the remaining child as input
	 */
	@Override
	public PhaseUnwrapComputation generate(List<Process<?, ?>> children) {
		return new PhaseUnwrapComputation(getShape(),
				children.stream().skip(1).toArray(Producer[]::new)[0]);
	}
}
