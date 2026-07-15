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

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that produces a prefix accumulation (scan) of a collection as a single
 * compiled kernel: output element i combines the per-element terms of every input element
 * whose index precedes (or, for the inclusive form, matches) i.
 *
 * <p>Each output element is produced by its own kernel thread, which iterates over the input
 * with a loop in the generated code, so the whole scan compiles to one kernel regardless of
 * the collection's length, and no per-element or per-value kernels are created.</p>
 *
 * <p>Subclasses define the accumulation by supplying three pieces:</p>
 * <ul>
 *   <li>{@link #identity()} — the accumulator's starting value, which must also be the
 *       neutral element of {@link #combine} (1.0 for a product, 0.0 for a sum), since
 *       excluded positions contribute it as their factor;</li>
 *   <li>{@link #combine} — how the accumulator absorbs one term;</li>
 *   <li>{@link #term} — the value contributed by the input element at a position.</li>
 * </ul>
 *
 * <p>Two forms are supported, selected by the {@code pad} flag: inclusive
 * ({@code pad == false}), where element i accumulates positions 0 through i, and exclusive
 * ({@code pad == true}), where element 0 is the identity and element i accumulates
 * positions 0 through i - 1.</p>
 *
 * <p>In both the compiled loop and the {@linkplain #getValueAt(Expression) expression-embedding}
 * form, each step contributes one factor — the term when its position falls inside the prefix,
 * or the identity otherwise — so the accumulator is referenced exactly once per step. The
 * unrolled embedding is therefore a flat chain whose size is linear in the collection length.
 * (Referencing the accumulator in both branches of a conditional instead would double the
 * expression tree at every step, making embedding infeasible for any consumer compiled without
 * {@link Process#optimize()}, where isolation is never consulted.)</p>
 *
 * @see CumulativeProductComputation
 * @see TraversableRepeatedProducerComputation
 *
 * @author Michael Murray
 */
public abstract class PrefixAccumulationComputation extends TraversableRepeatedProducerComputation {

	/** Whether the scan is exclusive: element i accumulates positions 0 through i - 1. */
	private final boolean pad;

	/**
	 * Creates a prefix accumulation over the given input.
	 *
	 * @param name  The operation identifier, used in generated code and cache signatures
	 * @param shape The output {@link TraversalPolicy}, matching the input shape with each
	 *              element traversed by its own kernel thread
	 * @param pad   If true, produce the exclusive form (identity prepended); otherwise
	 *              element i includes position i in its accumulation
	 * @param input The collection to scan
	 */
	public PrefixAccumulationComputation(String name, TraversalPolicy shape, boolean pad,
										 Producer<PackedCollection> input) {
		super(name, shape, shape.getTotalSize(), null, null, input);
		this.pad = pad;
		setInitial((args, index) -> identity());

		// The base constructor captures the metadata signature before this constructor body
		// has assigned pad, so it must be refreshed now that the flag is set
		init();
	}

	/**
	 * Returns the accumulator's starting value, which must be the neutral element of
	 * {@link #combine} because excluded positions contribute it as their factor.
	 *
	 * @return An {@link Expression} for the identity value
	 */
	protected abstract Expression<?> identity();

	/**
	 * Returns the accumulator after absorbing one term.
	 *
	 * @param accumulator The accumulated value so far
	 * @param factor      The factor to absorb (a term, or the identity when excluded)
	 * @return An {@link Expression} for the updated accumulator
	 */
	protected abstract Expression<?> combine(Expression<?> accumulator, Expression<?> factor);

	/**
	 * Returns the value contributed by the input element at the given position.
	 *
	 * @param input    The traversable input collection
	 * @param position The input element index under consideration
	 * @return An {@link Expression} for the term at that position
	 */
	protected abstract Expression<?> term(TraversableExpression input, Expression<?> position);

	/**
	 * Returns the factor contributed by input element {@code position} to the accumulation
	 * for output element {@code target}: the {@linkplain #term term} when {@code position}
	 * falls inside the target's prefix, and the {@linkplain #identity identity} otherwise.
	 *
	 * @param input    The traversable input collection
	 * @param target   The output element index whose prefix is being accumulated
	 * @param position The input element index under consideration
	 * @return An {@link Expression} for the factor
	 */
	private Expression<?> factor(TraversableExpression input, Expression<?> target, Expression<?> position) {
		Expression<Boolean> include = pad ?
				target.greaterThan(position) :
				target.greaterThanOrEqual(position);
		return conditional(include, term(input, position), identity());
	}

	/**
	 * Returns the value of output element {@code index} as a flat chain of one factor per
	 * input element, for consumers that embed this computation rather than reading its
	 * materialised output. The prefix comparison uses the queried index itself, so the
	 * expression is correct for any consumer indexing pattern.
	 *
	 * @param index The output element index being queried
	 * @return An {@link Expression} for the accumulation at that index
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversableExpression[] args = getTraversableArguments(index);

		Expression value = identity();

		for (int j = 0; j < count; j++) {
			value = combine(value, factor(args[1], index, e(j)));
			value = value.generate(value.flatten());
		}

		return value;
	}

	/**
	 * Returns the accumulation expression for one iteration of the compiled loop: the current
	 * accumulator value combined with the factor for the input element at the loop index,
	 * relative to this thread's output element (the kernel index).
	 *
	 * @param args        The traversable arguments, where args[0] is the output and args[1] the input
	 * @param globalIndex The global index, a {@link KernelIndex} when compiling the loop scope
	 * @param localIndex  The loop iteration index
	 * @return The updated accumulator {@link Expression}
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		CollectionVariable output = (CollectionVariable) args[0];

		Expression k = globalIndex instanceof KernelIndex ? globalIndex : new KernelIndex();
		Expression currentValue = output.reference(k.multiply(output.length()));
		return combine(currentValue, factor(args[1], k, localIndex));
	}

	/**
	 * Returns the exclusive-form flag, for subclasses to pass along when
	 * {@linkplain #generate(List) regenerating}.
	 *
	 * @return Whether this scan is exclusive
	 */
	protected boolean isPad() { return pad; }

	/**
	 * Returns a signature that includes the concrete scan type and the {@code pad} flag,
	 * ensuring accumulations that differ only inside the generated loop body — which the
	 * shape- and type-based parent signature does not capture — produce different compiled
	 * kernels rather than sharing a cached kernel from the instruction cache.
	 *
	 * @return A signature string including the scan type and pad flag, or null if the parent
	 *         signature is null
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;
		return signature + "{" + getClass().getSimpleName() + ",pad=" + pad + "}";
	}

	/**
	 * {@inheritDoc} Subclasses must regenerate their own concrete type, preserving their
	 * configuration.
	 */
	@Override
	public abstract PrefixAccumulationComputation generate(List<Process<?, ?>> children);
}
