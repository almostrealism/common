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

package org.almostrealism.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;

/**
 * A tensor argument of a PDSL stage that holds one value per batch entry and position of one axis
 * of the stage's input, {@code [batch, length(axis)]}, which the stage expands across the input's
 * other non-batch axes with
 * {@link org.almostrealism.collect.CollectionFeatures#broadcast(TraversalPolicy, int,
 * io.almostrealism.relation.Producer) broadcast}: the factors of {@code scale(factors, axis)}, the
 * factors and the offsets of {@code modulate(factors, offsets, axis)}, and the validity mask of the
 * machine-learning extension's {@code key_mask(mask)}, one value per key along axis 3 of a
 * {@code [batch, heads, queries, keys]} score tensor.
 *
 * <p>The broadcast accepts any values of the right count and reshapes them to
 * {@code [batch, length(axis)]}, so values of a matching count but another layout, transposed or
 * flat, would silently be paired with the wrong batch entries and positions. A stage therefore
 * obtains its values through {@link #forInput(TraversalPolicy)}, which hands them out only once
 * they are checked against the input the stage is placed on and rejects any other layout. A
 * primitive creates the values with {@link PdslPrimitiveContext#toAxisValues}, which normalizes
 * the argument the way every tensor argument is normalized.</p>
 */
public final class PdslAxisValues {

	/** Identifies the values in error messages, such as {@code "modulate() offsets"}. */
	private final String contextName;

	/** The values, normalized to a producer. */
	private final CollectionProducer values;

	/** The axis of the stage input the values index. */
	private final int axis;

	/**
	 * Wraps the values of an along-axis argument.
	 *
	 * @param contextName identifies the values in error messages, such as {@code "modulate() offsets"}
	 * @param values      the values, already normalized to a producer
	 * @param axis        the axis of the stage input the values index; never the batch axis
	 */
	public PdslAxisValues(String contextName, CollectionProducer values, int axis) {
		this.contextName = contextName;
		this.values = values;
		this.axis = axis;
	}

	/**
	 * Returns the values for the input of the stage they are placed on, once they are checked
	 * against it: the axis must be a non-batch axis of the input, and the values must be exactly
	 * {@code [batch, length(axis)]}, one per batch entry and position of the axis.
	 *
	 * @param inputShape the stage's input shape
	 * @return the values, normalized to a producer
	 * @throws PdslParseException if the axis or the shape of the values does not fit the input
	 */
	public CollectionProducer forInput(TraversalPolicy inputShape) {
		if (axis <= 0 || axis >= inputShape.getDimensions()) {
			throw new PdslParseException(contextName + ": axis " + axis
					+ " is not a non-batch axis of the input shape " + inputShape);
		}

		TraversalPolicy valueShape = values.getShape();
		if (valueShape.getDimensions() != 2
				|| valueShape.length(0) != inputShape.length(0)
				|| valueShape.length(1) != inputShape.length(axis)) {
			throw new PdslParseException(contextName + " must be shaped ["
					+ inputShape.length(0) + ", " + inputShape.length(axis)
					+ "], one value per batch entry and position of axis " + axis
					+ " of the input shape " + inputShape + ", got " + valueShape);
		}

		return values;
	}
}
