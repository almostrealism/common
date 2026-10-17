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

package org.almostrealism.calculus;

import io.almostrealism.collect.TraversalPolicy;

/**
 * The axis of a Jacobian that a reduced delta sums away.
 *
 * <p>The Jacobian of a computation {@code f} with respect to a target {@code x} has an
 * output axis (index 0, one entry per element of {@code f}) and an input axis (index 1,
 * one entry per element of {@code x}). A caller that is going to contract one of those
 * axes says so when it asks for the delta, and the computation produces the contracted
 * result directly rather than materializing the Jacobian first:</p>
 * <ul>
 *   <li>{@link #OUT} sums away the output axis, weighting each output by the upstream
 *       gradient {@code g}: the vector-Jacobian product {@code gᵀJ}, with the shape of
 *       the target. This is reverse-mode differentiation.</li>
 *   <li>{@link #IN} sums away the input axis, weighting each input by the tangent
 *       {@code t}: the Jacobian-vector product {@code J t}, with the shape of the
 *       computation. This is forward-mode differentiation.</li>
 * </ul>
 * <p>Without a weight every entry along the reduced axis is weighted by one.</p>
 *
 * @author Michael Murray
 * @see org.almostrealism.collect.CollectionProducer#delta(io.almostrealism.relation.Producer, ReduceAxis, io.almostrealism.relation.Producer)
 */
public enum ReduceAxis {
	/** The output axis (index 0) is summed away, producing a vector-Jacobian product. */
	OUT(0),

	/** The input axis (index 1) is summed away, producing a Jacobian-vector product. */
	IN(1);

	/** The index of this axis in the Jacobian. */
	private final int index;

	/**
	 * Creates the axis with the given Jacobian index.
	 *
	 * @param index the index of the axis in the Jacobian
	 */
	ReduceAxis(int index) {
		this.index = index;
	}

	/**
	 * Returns the index of this axis in the Jacobian: 0 for the output axis and 1 for the
	 * input axis.
	 *
	 * @return the axis index
	 */
	public int getIndex() {
		return index;
	}

	/**
	 * Returns the shape of a delta reduced along this axis, which is the shape of the
	 * axis that remains.
	 *
	 * @param outputShape the shape of the computation being differentiated
	 * @param targetShape the shape of the target of differentiation
	 * @return the target shape for {@link #OUT}, or the output shape for {@link #IN}
	 */
	public TraversalPolicy resultShape(TraversalPolicy outputShape, TraversalPolicy targetShape) {
		return this == OUT ? targetShape : outputShape;
	}

	/**
	 * Returns the shape of the weight applied along this axis, which is the shape of the
	 * axis being summed away.
	 *
	 * @param outputShape the shape of the computation being differentiated
	 * @param targetShape the shape of the target of differentiation
	 * @return the output shape for {@link #OUT}, or the target shape for {@link #IN}
	 */
	public TraversalPolicy weightShape(TraversalPolicy outputShape, TraversalPolicy targetShape) {
		return this == OUT ? outputShape : targetShape;
	}

	/**
	 * Returns the axis with the given Jacobian index.
	 *
	 * @param index 0 for the output axis or 1 for the input axis
	 * @return the axis
	 * @throws IllegalArgumentException if the index is neither 0 nor 1
	 */
	public static ReduceAxis of(int index) {
		for (ReduceAxis axis : values()) {
			if (axis.index == index) return axis;
		}

		throw new IllegalArgumentException("A Jacobian has no axis " + index);
	}
}
