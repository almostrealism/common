/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Abstract base for the extremum reductions that find the largest or smallest element of each
 * group of a {@link PackedCollection}. An extremum is not a linear aggregation, so unlike a sum
 * its derivative is not the extremum of the input's derivatives: the output depends only on the
 * single element the extremum {@link #select selects}, and so does its derivative. The two
 * concrete extrema, the {@link CollectionMaxComputation maximum} and the
 * {@link CollectionMinComputation minimum}, differ only in the direction of the comparison; this
 * class captures everything they share — the selection of the extreme element and the derivative
 * derived from it — leaving each subclass to provide the initial accumulator value, the combining
 * operation, and the index of the extreme element of each group.
 *
 * @see AggregatedProducerComputation
 * @see CollectionMaxComputation
 * @see CollectionMinComputation
 *
 * @author Michael Murray
 */
public abstract class AggregatedExtremumComputation extends AggregatedProducerComputation {
	/**
	 * Constructs an extremum reduction over the input, producing a single element for each group.
	 * The output shape is the input shape with its traversal item replaced by a single element,
	 * and the iteration count is the input's group size.
	 *
	 * @param name  the operation identifier (e.g. {@code "max"}, {@code "min"})
	 * @param shape the {@link TraversalPolicy} of the input collection
	 * @param input the {@link Producer} providing the collection to reduce
	 */
	protected AggregatedExtremumComputation(String name, TraversalPolicy shape, Producer<PackedCollection> input) {
		super(name, shape.replace(new TraversalPolicy(1)), shape.getSize(), input);
	}

	/**
	 * Returns the index of the extreme element of each group of the input, used to
	 * {@link #select select} the element on which the extremum and its derivative depend.
	 *
	 * @param input the input collection, traversed at the axis whose items are the groups
	 * @return a computation of the position of the extreme element of each group
	 */
	protected abstract CollectionProducer indexOfExtremum(Producer<PackedCollection> input);

	/**
	 * Returns the operation name of the {@link #select selection} projection, used as diagnostic
	 * metadata on the generated computation.
	 *
	 * @return the projection operation name (e.g. {@code "projectMax"}, {@code "projectMin"})
	 */
	protected abstract String projectionName();

	/**
	 * Selects, from each group of {@code values}, the element at the position of the extremum of
	 * the same group of this computation's input. Applied to the input itself this is equal in
	 * value to this computation; applied to the derivative of the input it is the derivative of
	 * the extremum, which is that of the element the extremum selects.
	 *
	 * @param values a collection with the shape of the input
	 * @return the selected elements, one for each group
	 */
	protected CollectionProducer select(Producer<PackedCollection> values) {
		Producer<PackedCollection> input = getInputs().get(1);
		return new DynamicIndexProjectionProducerComputation(projectionName(), shape(input).replace(shape(1)),
				(args, idx) -> args[2].getValueAt(idx).toInt(),
				true, values, indexOfExtremum(input));
	}

	/**
	 * The extremum is not a linear aggregation, so its derivative is not the extremum of the
	 * input's derivatives: it is the derivative of the element that the extremum
	 * {@link #select selects}. The Jacobian and the {@link ReduceAxis#OUT output-reduced}
	 * delta are those of the selection, and the {@link ReduceAxis#IN input-reduced} delta
	 * selects, from the input's own input-reduced delta, the element at the position of
	 * the extremum.
	 */
	@Override
	protected CollectionProducer deltaDependent(Producer<?> target, ReduceAxis axis, Producer<PackedCollection> weight) {
		Producer<PackedCollection> child = getInputs().get(1);
		if (axis == ReduceAxis.NONE) {
			return select(child).delta(target);
		} else if (!(child instanceof Shape)) {
			return super.deltaDependent(target, axis, weight);
		} else if (axis == ReduceAxis.OUT) {
			return select(child).delta(target, axis, weight);
		}

		CollectionProducer input = c(child);
		return select(reshape(input.getShape(), input.delta(target, axis, weight)));
	}

	/**
	 * Indicates that this computation supports signature generation for caching
	 * and deduplication purposes.
	 *
	 * @return {@code true} to enable signature support
	 */
	@Override
	protected boolean isSignatureSupported() { return true; }
}
