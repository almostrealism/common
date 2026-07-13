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
 * <p>Unlike {@link TraversableRepeatedProducerComputation}, this computation deliberately does
 * not implement {@link TraversableExpression}: consumers read its materialised output rather
 * than embedding the scan, since embedding would unroll the entire iteration into a single
 * expression tree.</p>
 *
 * @see ConstantRepeatedProducerComputation
 * @see org.almostrealism.collect.AggregationFeatures#cumulativeProduct(Producer, boolean)
 *
 * @author Michael Murray
 */
public class CumulativeProductComputation extends ConstantRepeatedProducerComputation {

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
	 * Returns the accumulation expression for one loop iteration: the current accumulator value,
	 * multiplied by the input element at the loop index whenever that index falls inside this
	 * thread's prefix (indices below the kernel index, or at it for the inclusive form).
	 *
	 * @param args        The traversable arguments, where args[0] is the output and args[1] the input
	 * @param globalIndex The global index (unused; the kernel index is referenced directly)
	 * @param localIndex  The loop iteration index
	 * @return The updated accumulator {@link Expression}
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		CollectionVariable output = (CollectionVariable) args[0];
		Expression<?> currentValue = output.reference(new KernelIndex().multiply(output.length()));
		Expression<Boolean> include = pad ?
				localIndex.lessThan(new KernelIndex()) :
				localIndex.lessThanOrEqual(new KernelIndex());
		return conditional(include,
				currentValue.multiply(args[1].getValueAt(localIndex)), currentValue);
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
