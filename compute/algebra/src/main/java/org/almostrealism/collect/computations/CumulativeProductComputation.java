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
 * A computation that produces the cumulative (prefix) product of a collection as a single
 * compiled kernel.
 *
 * <p>Each output element is produced by its own kernel thread, which iterates over the input
 * and multiplies together every element whose index precedes (or, for the inclusive form,
 * matches) the thread's output index. The iteration is a loop in the generated code, so the
 * whole scan compiles to one kernel regardless of the collection's length, and no per-element
 * or per-value kernels are created.</p>
 *
 * <p>Two forms are supported, selected by the {@code pad} flag:</p>
 * <ul>
 *   <li><strong>Inclusive</strong> ({@code pad == false}): element i is the product of input
 *       elements 0 through i.</li>
 *   <li><strong>Exclusive</strong> ({@code pad == true}): element 0 is 1.0 and element i is
 *       the product of input elements 0 through i - 1.</li>
 * </ul>
 *
 * <p>In both the compiled loop and the {@linkplain #getValueAt(Expression) expression-embedding}
 * form, each step contributes one multiplicative factor — the input element when its index falls
 * inside the prefix, or 1.0 otherwise — so the accumulator is referenced exactly once per step.
 * The unrolled embedding is therefore a flat product whose size is linear in the collection
 * length. (Referencing the accumulator in both branches of a conditional instead would double
 * the expression tree at every step, making embedding infeasible for any consumer compiled
 * without {@link Process#optimize()}, where isolation is never consulted.)</p>
 *
 * @see TraversableRepeatedProducerComputation
 * @see org.almostrealism.collect.AggregationFeatures#cumulativeProduct(Producer, boolean)
 *
 * @author Michael Murray
 */
public class CumulativeProductComputation extends TraversableRepeatedProducerComputation {

	/** Whether the scan is exclusive: element i is the product of elements 0 through i - 1. */
	private final boolean pad;

	/**
	 * Creates a cumulative product computation over the given input.
	 *
	 * @param shape The output {@link TraversalPolicy}, matching the input shape with each
	 *              element traversed by its own kernel thread
	 * @param pad   If true, produce the exclusive form (1.0 prepended); otherwise element i
	 *              includes input element i in its product
	 * @param input The collection to scan
	 */
	public CumulativeProductComputation(TraversalPolicy shape, boolean pad, Producer<PackedCollection> input) {
		super("cumulativeProduct", shape, shape.getTotalSize(), null, null, input);
		this.pad = pad;
		setInitial((args, index) -> e(1.0));

		// The base constructor captures the metadata signature before this constructor body
		// has assigned pad, so it must be refreshed now that the flag is set
		init();
	}

	/**
	 * Returns the multiplicative factor contributed by input element {@code position} to the
	 * product for output element {@code target}: the input value when {@code position} falls
	 * inside the target's prefix, and 1.0 otherwise.
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
		return conditional(include, input.getValueAt(position), e(1.0));
	}

	/**
	 * Returns the value of output element {@code index} as a flat product of one factor per
	 * input element, for consumers that embed this computation rather than reading its
	 * materialised output. The prefix comparison uses the queried index itself, so the
	 * expression is correct for any consumer indexing pattern.
	 *
	 * @param index The output element index being queried
	 * @return An {@link Expression} for the prefix product at that index
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversableExpression[] args = getTraversableArguments(index);

		Expression value = e(1.0);

		for (int j = 0; j < count; j++) {
			value = value.multiply(factor(args[1], index, e(j)));
			value = value.generate(value.flatten());
		}

		return value;
	}

	/**
	 * Returns the accumulation expression for one iteration of the compiled loop: the current
	 * accumulator value multiplied by the factor for the input element at the loop index,
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
		return currentValue.multiply(factor(args[1], k, localIndex));
	}

	/**
	 * Returns a signature that includes the {@code pad} flag, ensuring the inclusive and
	 * exclusive forms produce different compiled kernels rather than sharing a cached kernel
	 * from the instruction cache. The two forms differ only inside the generated loop body,
	 * which the shape- and type-based parent signature does not capture.
	 *
	 * @return A signature string that includes the pad flag, or null if the parent signature is null
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;
		return signature + "{pad=" + pad + "}";
	}

	/**
	 * Generates a new instance of this computation with updated child processes,
	 * preserving the {@code pad} flag.
	 *
	 * @param children The child processes, where the first element is the output destination
	 * @return A new {@link CumulativeProductComputation} over the remaining child as input
	 */
	@Override
	public CumulativeProductComputation generate(List<Process<?, ?>> children) {
		return new CumulativeProductComputation(getShape(), pad,
				children.stream().skip(1).toArray(Producer[]::new)[0]);
	}
}
