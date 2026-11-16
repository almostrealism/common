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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * The treatment of the cells beyond the edge of a structured grid, used by the finite-difference
 * operators of {@link FieldCalculusFeatures}.
 *
 * <p>A boundary condition is expressed as <em>ghost cells</em>: when a grid field is shifted by
 * {@code d} cells along an axis, {@code |d|} layers of cells enter the grid from beyond one of its
 * edges, and the boundary condition decides what those layers hold. Every ghost layer is itself a
 * {@link CollectionProducer} built from the field, so the shifted field remains one computation
 * graph and is compiled into the kernel of whatever operator consumes it.</p>
 *
 * <ul>
 *   <li>{@link #periodic()} - the grid wraps around: the cells beyond the upper edge are the first
 *       cells of the axis, and the cells beyond the lower edge are the last.</li>
 *   <li>{@link #clamped()} - every ghost cell repeats the edge cell it is adjacent to, so the
 *       one-sided difference across the edge is zero (a zero-gradient, or Neumann, condition).</li>
 *   <li>{@link #fixed(double)} - every ghost cell holds a fixed value (a Dirichlet condition). The
 *       grid points are the interior nodes; the boundary nodes, one spacing outside the first and
 *       last grid points, carry the value.</li>
 * </ul>
 *
 * @see FieldCalculusFeatures#shift(Producer, int, int, GridBoundary)
 * @author Michael Murray
 */
public abstract class GridBoundary implements CollectionFeatures {

	/**
	 * Returns the cells that lie beyond one edge of the field along an axis.
	 *
	 * @param field the grid field
	 * @param axis  the axis along which the field is extended
	 * @param depth the number of ghost layers, at least 1 and at most the length of the axis
	 * @param upper {@code true} for the layers beyond the last cell of the axis (ordered outward),
	 *              {@code false} for the layers before the first cell (ordered so that the layer
	 *              adjacent to the first cell comes last)
	 * @return a producer whose shape is the field's shape with the length of {@code axis} replaced
	 *         by {@code depth}
	 */
	public abstract CollectionProducer ghost(Producer<PackedCollection> field, int axis, int depth, boolean upper);

	/**
	 * Returns the boundary condition that governs a <em>tangential derivative</em> of a field to which
	 * this boundary condition applies - the ghost cells of {@code d(field)/dx_j} when the field is
	 * extended along a different axis {@code k != j}.
	 *
	 * <p>Shifting a field across an edge and then differentiating it along a tangential axis is not the
	 * same as differentiating first and then shifting the derivative with this same boundary, because
	 * the ghost layer the boundary supplies has its own tangential variation. For the
	 * {@link #periodic()} boundary the ghost layer is a wrapped copy of the field, so its tangential
	 * derivative is the wrapped derivative and the derivative boundary is again periodic. For the
	 * {@link #clamped()} boundary the ghost layer repeats the edge cells, so its tangential derivative
	 * is the edge cells' derivative and the derivative boundary is again clamped. For the
	 * {@link #fixed(double)} boundary the ghost layer is a constant, so its tangential derivative is
	 * zero regardless of the fixed value, and the derivative boundary is {@code fixed(0)}.</p>
	 *
	 * <p>Operators that shift a derivative field across an edge - such as the tangential slopes
	 * averaged across a face in {@link FieldCalculusFeatures#heightFieldCurvature} - must use this
	 * boundary for that shift rather than reusing the boundary of the undifferentiated field, which
	 * would otherwise inject the fixed value as a spurious tangential slope.</p>
	 *
	 * @return the boundary condition for a tangential derivative of a field with this boundary
	 */
	public GridBoundary derivative() {
		return this;
	}

	/**
	 * Returns a description of this boundary condition for diagnostics.
	 *
	 * @return the name of the boundary condition
	 */
	@Override
	public abstract String toString();

	/**
	 * Returns the periodic boundary condition, under which the grid wraps around along every axis.
	 *
	 * @return the periodic boundary condition
	 */
	public static GridBoundary periodic() {
		return new GridBoundary() {
			@Override
			public CollectionProducer ghost(Producer<PackedCollection> field, int axis, int depth, boolean upper) {
				int length = shape(field).length(axis);
				return slice(axis, upper ? 0 : length - depth, depth, field);
			}

			@Override
			public String toString() { return "periodic"; }
		};
	}

	/**
	 * Returns the clamped (zero-gradient, Neumann) boundary condition, under which every ghost cell
	 * repeats the edge cell it is adjacent to.
	 *
	 * @return the clamped boundary condition
	 */
	public static GridBoundary clamped() {
		return new GridBoundary() {
			@Override
			public CollectionProducer ghost(Producer<PackedCollection> field, int axis, int depth, boolean upper) {
				int length = shape(field).length(axis);
				CollectionProducer edge = slice(axis, upper ? length - 1 : 0, 1, field);
				if (depth == 1) return edge;

				CollectionProducer[] copies = new CollectionProducer[depth];
				for (int i = 0; i < depth; i++) {
					copies[i] = edge;
				}

				return concat(axis, copies);
			}

			@Override
			public String toString() { return "clamped"; }
		};
	}

	/**
	 * Returns the fixed-value (Dirichlet) boundary condition, under which every ghost cell holds the
	 * given value.
	 *
	 * @param value the value of the field beyond the grid
	 * @return the fixed-value boundary condition
	 */
	public static GridBoundary fixed(double value) {
		return new GridBoundary() {
			@Override
			public CollectionProducer ghost(Producer<PackedCollection> field, int axis, int depth, boolean upper) {
				return constant(shape(field).replaceDimension(axis, depth).withOrder(null), value);
			}

			@Override
			public GridBoundary derivative() {
				return value == 0.0 ? this : fixed(0.0);
			}

			@Override
			public String toString() { return "fixed(" + value + ")"; }
		};
	}
}
