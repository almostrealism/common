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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Finite-difference calculus on fields sampled over structured (regular, rectilinear) grids,
 * expressed entirely as {@link CollectionProducer} graphs.
 *
 * <p>A <em>scalar field</em> is a collection whose every axis is a grid axis: a 2D field over an
 * {@code n0 x n1} grid has shape {@code (n0, n1)}, a 3D field has shape {@code (n0, n1, n2)}. A
 * <em>vector field</em> over a {@code d}-dimensional grid has shape {@code (d, n0, ..., n(d-1))}:
 * component {@code k} is the slice at index {@code k} of axis 0 and is the component along grid
 * axis {@code k}. Grid spacings are given per grid axis; a single spacing applies to every axis.</p>
 *
 * <p>Every operator is built from {@link #shift(Producer, int, int, GridBoundary)}, which reads
 * the neighbouring cell along an axis, with the cells beyond the edge of the grid supplied by a
 * {@link GridBoundary}. A shift is a composition of the existing {@code concat} and {@code subset}
 * operations, so the operators inherit their handling of the input's
 * {@link io.almostrealism.collect.TraversalOrdering} and their derivatives
 * ({@link CollectionProducer#delta}), and an operator such as the Laplacian compiles to a single
 * kernel with no intermediate buffers.</p>
 *
 * <p>All difference operators are second-order accurate in the grid spacing in the interior of the
 * grid and, under {@link GridBoundary#periodic()}, everywhere. Under the clamped and fixed
 * boundaries the ghost cells define the discrete problem at the edges; see {@link GridBoundary}.</p>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these operations
 * should <em>implement</em> this interface rather than accept or hold an instance of it.</p>
 *
 * @see GridBoundary
 * @author Michael Murray
 */
public interface FieldCalculusFeatures extends CollectionFeatures {

	/**
	 * Returns the field shifted along an axis, so that the result at grid index {@code i} along
	 * {@code axis} is the field at index {@code i + offset}. Cells that would be read from beyond
	 * the edge of the grid are supplied by the boundary condition.
	 *
	 * @param field    the grid field (any number of axes; every axis may be shifted)
	 * @param axis     the axis along which to shift
	 * @param offset   the signed number of cells to shift by; its magnitude must not exceed the
	 *                 length of the axis
	 * @param boundary the boundary condition supplying the ghost cells
	 * @return a producer with the same shape as the field (without its ordering)
	 * @throws IllegalArgumentException if the axis is out of range or the offset is too large
	 */
	default CollectionProducer shift(Producer<PackedCollection> field, int axis, int offset, GridBoundary boundary) {
		TraversalPolicy shape = shape(field).withOrder(null);
		if (axis < 0 || axis >= shape.getDimensions()) {
			throw new IllegalArgumentException("axis " + axis + " is not an axis of " + shape);
		}

		long magnitude = Math.abs((long) offset);
		if (magnitude > shape.length(axis)) {
			throw new IllegalArgumentException("offset " + offset + " exceeds the length of axis "
					+ axis + " of " + shape);
		}

		int[] position = new int[shape.getDimensions()];
		if (offset == 0) return subset(shape, field, position);

		int depth = (int) magnitude;
		CollectionProducer ghost = boundary.ghost(field, axis, depth, offset > 0);

		CollectionProducer extended;
		if (offset > 0) {
			extended = concat(axis, field, ghost);
			position[axis] = offset;
		} else {
			extended = concat(axis, ghost, field);
		}

		return subset(shape, extended, position);
	}

	/**
	 * Returns the forward difference {@code (f[i+1] - f[i]) / h} along an axis, which is the
	 * derivative at the face between cells {@code i} and {@code i+1} (first order at the cell,
	 * second order at the face).
	 *
	 * @param field    the grid field
	 * @param axis     the axis to differentiate along
	 * @param spacing  the grid spacing {@code h} along the axis
	 * @param boundary the boundary condition
	 * @return the forward difference, with the shape of the field
	 */
	default CollectionProducer forwardDifference(Producer<PackedCollection> field, int axis,
												 double spacing, GridBoundary boundary) {
		return shift(field, axis, 1, boundary).subtract(field).multiply(1.0 / spacing);
	}

	/**
	 * Returns the backward difference {@code (f[i] - f[i-1]) / h} along an axis, which is the
	 * derivative at the face between cells {@code i-1} and {@code i}.
	 *
	 * @param field    the grid field
	 * @param axis     the axis to differentiate along
	 * @param spacing  the grid spacing {@code h} along the axis
	 * @param boundary the boundary condition
	 * @return the backward difference, with the shape of the field
	 */
	default CollectionProducer backwardDifference(Producer<PackedCollection> field, int axis,
												  double spacing, GridBoundary boundary) {
		return c(field).subtract(shift(field, axis, -1, boundary)).multiply(1.0 / spacing);
	}

	/**
	 * Returns the central difference {@code (f[i+1] - f[i-1]) / 2h} along an axis, a second-order
	 * approximation of the first derivative at each cell.
	 *
	 * @param field    the grid field
	 * @param axis     the axis to differentiate along
	 * @param spacing  the grid spacing {@code h} along the axis
	 * @param boundary the boundary condition
	 * @return the central difference, with the shape of the field
	 */
	default CollectionProducer centralDifference(Producer<PackedCollection> field, int axis,
												 double spacing, GridBoundary boundary) {
		return shift(field, axis, 1, boundary).subtract(shift(field, axis, -1, boundary))
				.multiply(0.5 / spacing);
	}

	/**
	 * Returns the second difference {@code (f[i+1] - 2 f[i] + f[i-1]) / h^2} along an axis, a
	 * second-order approximation of the second derivative at each cell.
	 *
	 * @param field    the grid field
	 * @param axis     the axis to differentiate along
	 * @param spacing  the grid spacing {@code h} along the axis
	 * @param boundary the boundary condition
	 * @return the second difference, with the shape of the field
	 */
	default CollectionProducer secondDifference(Producer<PackedCollection> field, int axis,
												double spacing, GridBoundary boundary) {
		return shift(field, axis, 1, boundary).add(shift(field, axis, -1, boundary))
				.subtract(c(field).multiply(2.0))
				.multiply(1.0 / (spacing * spacing));
	}

	/**
	 * Returns the gradient of a scalar field by central differences.
	 *
	 * @param field    the scalar field, of shape {@code (n0, ..., n(d-1))}
	 * @param boundary the boundary condition
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the gradient, a vector field of shape {@code (d, n0, ..., n(d-1))}
	 */
	default CollectionProducer gradient(Producer<PackedCollection> field, GridBoundary boundary,
										double... spacing) {
		TraversalPolicy shape = shape(field);
		int d = shape.getDimensions();
		int[] componentDims = new int[d + 1];
		componentDims[0] = 1;
		for (int k = 0; k < d; k++) {
			componentDims[k + 1] = shape.length(k);
		}

		CollectionProducer[] components = new CollectionProducer[d];
		for (int k = 0; k < d; k++) {
			components[k] = centralDifference(field, k, gridSpacing(spacing, k, d), boundary)
					.reshape(componentDims);
		}

		return d == 1 ? components[0] : concat(0, components);
	}

	/**
	 * Returns component {@code k} of a vector field: the scalar field along grid axis {@code k}.
	 *
	 * @param vectorField the vector field, of shape {@code (d, n0, ..., n(d-1))}
	 * @param k           the component, in {@code [0, d)}
	 * @return the component, of shape {@code (n0, ..., n(d-1))}
	 */
	default CollectionProducer component(Producer<PackedCollection> vectorField, int k) {
		return slice(0, k, 1, vectorField).reshape(shape(vectorField).subset(1).withOrder(null));
	}

	/**
	 * Returns the divergence of a vector field by central differences: the sum over grid axes
	 * {@code k} of the central difference of component {@code k} along axis {@code k}.
	 *
	 * <p>Applied to a {@link #gradient} this is the wide stencil {@code (f[i+2] - 2 f[i] + f[i-2]) / 4h^2}
	 * per axis, not the {@link #laplacian}. Each shift inlines the index expression of its input,
	 * so on a 3D grid the divergence of an unstored gradient takes minutes to compile; store the
	 * gradient in an earlier operation and take the divergence of the stored field.</p>
	 *
	 * @param vectorField the vector field, of shape {@code (d, n0, ..., n(d-1))}
	 * @param boundary    the boundary condition, applied to each component
	 * @param spacing     the grid spacing per axis, or one spacing for every axis
	 * @return the divergence, a scalar field of shape {@code (n0, ..., n(d-1))}
	 * @throws IllegalArgumentException if the number of components is not the number of grid axes
	 */
	default CollectionProducer divergence(Producer<PackedCollection> vectorField, GridBoundary boundary,
										  double... spacing) {
		TraversalPolicy shape = shape(vectorField);
		int d = shape.getDimensions() - 1;
		if (shape.length(0) != d) {
			throw new IllegalArgumentException("a vector field over " + d
					+ " grid axes must have " + d + " components, not " + shape.length(0));
		}

		CollectionProducer result = null;
		for (int k = 0; k < d; k++) {
			CollectionProducer term = centralDifference(component(vectorField, k), k,
					gridSpacing(spacing, k, d), boundary);
			result = result == null ? term : result.add(term);
		}

		return result;
	}

	/**
	 * Returns the Laplacian of a scalar field with the standard compact stencil: the sum over axes
	 * of {@link #secondDifference}. In 2D this is the 5-point stencil, in 3D the 7-point stencil.
	 * The truncation error is second order in the spacing.
	 *
	 * @param field    the scalar field
	 * @param boundary the boundary condition
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the Laplacian, with the shape of the field
	 */
	default CollectionProducer laplacian(Producer<PackedCollection> field, GridBoundary boundary,
										 double... spacing) {
		int d = shape(field).getDimensions();

		CollectionProducer result = null;
		for (int k = 0; k < d; k++) {
			CollectionProducer term = secondDifference(field, k, gridSpacing(spacing, k, d), boundary);
			result = result == null ? term : result.add(term);
		}

		return result;
	}

	/**
	 * Returns the Laplacian of a 2D scalar field with the isotropic 9-point stencil
	 * <pre>
	 *     (4 (N + S + E + W) + (NE + NW + SE + SW) - 20 C) / (6 h^2)
	 * </pre>
	 * which is second-order accurate like the 5-point stencil, but whose leading error term is
	 * rotationally invariant ({@code h^2/12} times the biharmonic), so it does not favour the grid
	 * axes. The grid spacing must be the same along both axes.
	 *
	 * @param field    the 2D scalar field
	 * @param boundary the boundary condition; the corner neighbours are shifts of shifts, so the
	 *                 boundary is applied along each axis in turn
	 * @param spacing  the grid spacing along both axes
	 * @return the Laplacian, with the shape of the field
	 * @throws IllegalArgumentException if the field is not two-dimensional
	 */
	default CollectionProducer ninePointLaplacian(Producer<PackedCollection> field, GridBoundary boundary,
												  double spacing) {
		if (shape(field).getDimensions() != 2) {
			throw new IllegalArgumentException("the 9-point Laplacian applies to 2D fields, not " + shape(field));
		}

		CollectionProducer up = shift(field, 0, 1, boundary);
		CollectionProducer down = shift(field, 0, -1, boundary);
		CollectionProducer edges = up.add(down)
				.add(shift(field, 1, 1, boundary))
				.add(shift(field, 1, -1, boundary));
		CollectionProducer corners = shift(up, 1, 1, boundary)
				.add(shift(up, 1, -1, boundary))
				.add(shift(down, 1, 1, boundary))
				.add(shift(down, 1, -1, boundary));

		return edges.multiply(4.0).add(corners).subtract(c(field).multiply(20.0))
				.multiply(1.0 / (6.0 * spacing * spacing));
	}

	/**
	 * Returns the curvature of the graph of a height field, {@code z = h(x)},
	 * <pre>
	 *     kappa = div( grad h / sqrt(1 + |grad h|^2) )
	 * </pre>
	 * which is the sum of the principal curvatures, or twice the mean curvature, of the surface
	 * (the quantity in the Young-Laplace pressure jump {@code sigma * kappa}). A surface that is
	 * convex upward, such as the top of a sphere of radius {@code R}, has {@code kappa = -2/R}.
	 *
	 * <p>The divergence is taken in conservative (flux) form: for each axis {@code k} the flux
	 * {@code grad h / sqrt(1 + |grad h|^2)} is evaluated at the two faces of the cell along
	 * {@code k}, with the normal derivative a one-sided difference across the face and the
	 * tangential derivatives the average of the central differences of the two cells sharing the
	 * face. The result is second-order accurate, and in the small-slope limit it reduces to
	 * {@link #laplacian} (see {@link #linearizedHeightFieldCurvature}).</p>
	 *
	 * <p>The flux shifts the central differences, so the operator nests shifts of shifts like
	 * {@link #divergence}({@link #gradient}); on a 3D grid its index-expression simplification is
	 * correspondingly slow to compile (tested only in 2D). See the 3D nesting note on
	 * {@link #divergence}.</p>
	 *
	 * @param height   the height field
	 * @param boundary the boundary condition
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the curvature, with the shape of the height field
	 */
	default CollectionProducer heightFieldCurvature(Producer<PackedCollection> height, GridBoundary boundary,
													double... spacing) {
		int d = shape(height).getDimensions();

		CollectionProducer[] central = new CollectionProducer[d];
		for (int j = 0; j < d; j++) {
			central[j] = centralDifference(height, j, gridSpacing(spacing, j, d), boundary);
		}

		CollectionProducer result = null;
		for (int k = 0; k < d; k++) {
			double h = gridSpacing(spacing, k, d);
			CollectionProducer upper = faceFlux(central, k,
					forwardDifference(height, k, h, boundary), 1, boundary);
			CollectionProducer lower = faceFlux(central, k,
					backwardDifference(height, k, h, boundary), -1, boundary);
			CollectionProducer term = upper.subtract(lower).multiply(1.0 / h);
			result = result == null ? term : result.add(term);
		}

		return result;
	}

	/**
	 * Returns the small-slope (linearized) curvature of a height field, which is its
	 * {@link #laplacian}. It is the limit of {@link #heightFieldCurvature} as
	 * {@code |grad h| -> 0}, with the same sign convention.
	 *
	 * @param height   the height field
	 * @param boundary the boundary condition
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the linearized curvature, with the shape of the height field
	 */
	default CollectionProducer linearizedHeightFieldCurvature(Producer<PackedCollection> height,
															  GridBoundary boundary, double... spacing) {
		return laplacian(height, boundary, spacing);
	}

	/**
	 * Returns the normalized flux {@code dh/dx_k / sqrt(1 + |grad h|^2)} through one face of every
	 * cell along axis {@code k}, as used by {@link #heightFieldCurvature}.
	 *
	 * @param central  the central differences of the height field along every axis
	 * @param k        the axis normal to the face
	 * @param normal   the one-sided difference of the height field across the face
	 * @param side     {@code 1} for the face towards the next cell, {@code -1} for the previous
	 * @param boundary the boundary condition, used to read the neighbouring cell's tangential slope
	 * @return the flux through the face
	 */
	private CollectionProducer faceFlux(CollectionProducer[] central, int k, CollectionProducer normal,
										int side, GridBoundary boundary) {
		CollectionProducer squared = normal.sq().add(1.0);
		for (int j = 0; j < central.length; j++) {
			if (j == k) continue;

			CollectionProducer tangential = central[j].add(shift(central[j], k, side, boundary))
					.multiply(0.5);
			squared = squared.add(tangential.sq());
		}

		return normal.divide(squared.sqrt());
	}

	/**
	 * Returns one weighted-Jacobi relaxation of the discrete Poisson equation
	 * {@code laplacian(p) = f} (with the {@link #laplacian} stencil):
	 * <pre>
	 *     p' = (1 - omega) p + omega (sum_k (p[+k] + p[-k]) / h_k^2 - f) / (sum_k 2 / h_k^2)
	 * </pre>
	 *
	 * <p>Every cell of the result reads its neighbours in {@code p}, so the update is globally
	 * coupled: assign the result to a separate next-state collection and copy that back over
	 * {@code p} in a later operation (the step contract of a {@code SimulationRunner} system), or
	 * evaluate it into a second collection at a host step boundary. Never assign it to {@code p}
	 * in the same operation. {@code omega = 1} is plain Jacobi; {@code omega = 2/3} damps the
	 * high-frequency error fastest, which suits Jacobi as a smoother.</p>
	 *
	 * @param p        the current estimate of the solution
	 * @param f        the right-hand side, with the shape of {@code p}
	 * @param boundary the boundary condition of the problem (fixed for Dirichlet, clamped for
	 *                 homogeneous Neumann, periodic)
	 * @param omega    the relaxation weight, in {@code (0, 1]}
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the relaxed estimate, with the shape of {@code p}
	 */
	default CollectionProducer jacobiStep(Producer<PackedCollection> p, Producer<PackedCollection> f,
										  GridBoundary boundary, double omega, double... spacing) {
		CollectionProducer update = poissonPointUpdate(p, f, boundary, spacing);
		if (omega == 1.0) return update;
		return c(p).multiply(1.0 - omega).add(update.multiply(omega));
	}

	/**
	 * Returns one full red-black Gauss-Seidel sweep of the discrete Poisson equation
	 * {@code laplacian(p) = f}: the cells with even coordinate sum ("red") are relaxed first from
	 * their (black) neighbours, then the black cells are relaxed from the updated red neighbours.
	 * Because the black half reads the red half as a producer rather than a stored collection,
	 * the whole sweep is a single computation. With {@code omega > 1} this is successive
	 * over-relaxation.
	 *
	 * <p>The result is globally coupled in the same way as {@link #jacobiStep}: write it to a
	 * next-state collection and copy it back in a later operation.</p>
	 *
	 * <p>The checkerboard is a true two-colouring only when every {@link GridBoundary#periodic()}
	 * axis has an even length: across the wrap of an odd-length periodic axis the two adjacent
	 * cells share a colour, so the black half no longer reads only updated red neighbours and the
	 * sweep becomes a Jacobi/Gauss-Seidel hybrid along that wrap. It still converges, but for a
	 * true red-black sweep use even lengths on periodic axes (the clamped and fixed boundaries put
	 * the colouring discontinuity outside the grid and are unaffected). The black half also reads
	 * the red half, which is itself built from shifts, so the sweep nests shifts of shifts like
	 * {@link #divergence}({@link #gradient}) and is correspondingly slow to compile in 3D (tested
	 * only in 2D).</p>
	 *
	 * @param p        the current estimate of the solution
	 * @param f        the right-hand side, with the shape of {@code p}
	 * @param boundary the boundary condition of the problem
	 * @param omega    the relaxation weight, in {@code (0, 2)}
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the relaxed estimate, with the shape of {@code p}
	 */
	default CollectionProducer redBlackGaussSeidelStep(Producer<PackedCollection> p, Producer<PackedCollection> f,
													   GridBoundary boundary, double omega, double... spacing) {
		CollectionProducer red = gaussSeidelHalfStep(p, f, boundary, omega, 0, spacing);
		return gaussSeidelHalfStep(red, f, boundary, omega, 1, spacing);
	}

	/**
	 * Returns half of a red-black Gauss-Seidel sweep: the cells whose coordinate sum has the given
	 * parity are relaxed from their neighbours in {@code p}, and every other cell keeps its value.
	 *
	 * @param p        the current estimate of the solution
	 * @param f        the right-hand side, with the shape of {@code p}
	 * @param boundary the boundary condition of the problem
	 * @param omega    the relaxation weight
	 * @param parity   {@code 0} to relax the red cells, {@code 1} to relax the black cells
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the partly relaxed estimate, with the shape of {@code p}
	 */
	default CollectionProducer gaussSeidelHalfStep(Producer<PackedCollection> p, Producer<PackedCollection> f,
												   GridBoundary boundary, double omega, int parity,
												   double... spacing) {
		CollectionProducer correction = poissonPointUpdate(p, f, boundary, spacing).subtract(p);
		CollectionProducer mask = checkerboard(shape(p), parity);
		return c(p).add(mask.multiply(correction).multiply(omega));
	}

	/**
	 * Returns the value of every cell that satisfies the discrete Poisson equation exactly given
	 * its neighbours: {@code (sum_k (p[+k] + p[-k]) / h_k^2 - f) / (sum_k 2 / h_k^2)}.
	 *
	 * @param p        the current estimate of the solution
	 * @param f        the right-hand side
	 * @param boundary the boundary condition
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the point update, with the shape of {@code p}
	 */
	default CollectionProducer poissonPointUpdate(Producer<PackedCollection> p, Producer<PackedCollection> f,
												  GridBoundary boundary, double... spacing) {
		int d = shape(p).getDimensions();

		double diagonal = 0.0;
		CollectionProducer neighbours = null;
		for (int k = 0; k < d; k++) {
			double h = gridSpacing(spacing, k, d);
			double weight = 1.0 / (h * h);
			diagonal += 2.0 * weight;

			CollectionProducer term = shift(p, k, 1, boundary).add(shift(p, k, -1, boundary)).multiply(weight);
			neighbours = neighbours == null ? term : neighbours.add(term);
		}

		return neighbours.subtract(f).multiply(1.0 / diagonal);
	}

	/**
	 * Returns the residual {@code f - laplacian(p)} of the discrete Poisson equation.
	 *
	 * @param p        the current estimate of the solution
	 * @param f        the right-hand side, with the shape of {@code p}
	 * @param boundary the boundary condition of the problem
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return the residual, with the shape of {@code p}
	 */
	default CollectionProducer poissonResidual(Producer<PackedCollection> p, Producer<PackedCollection> f,
											   GridBoundary boundary, double... spacing) {
		return c(f).subtract(laplacian(p, boundary, spacing));
	}

	/**
	 * Returns the root-mean-square of the {@link #poissonResidual}, a single value that falls to
	 * zero as the estimate converges to the discrete solution.
	 *
	 * @param p        the current estimate of the solution
	 * @param f        the right-hand side, with the shape of {@code p}
	 * @param boundary the boundary condition of the problem
	 * @param spacing  the grid spacing per axis, or one spacing for every axis
	 * @return a producer of shape {@code (1)} holding the RMS residual
	 */
	default CollectionProducer poissonResidualNorm(Producer<PackedCollection> p, Producer<PackedCollection> f,
												   GridBoundary boundary, double... spacing) {
		int size = shape(p).getTotalSize();
		return poissonResidual(p, f, boundary, spacing).sq().reshape(size).sum()
				.multiply(1.0 / size).sqrt();
	}

	/**
	 * Returns a mask over a grid that is {@code 1} at every cell whose coordinate sum has the given
	 * parity and {@code 0} elsewhere: the red ({@code parity = 0}) or black ({@code parity = 1})
	 * cells of a checkerboard colouring.
	 *
	 * @param shape  the shape of the grid
	 * @param parity {@code 0} or {@code 1}
	 * @return the mask, with the given shape
	 */
	default CollectionProducer checkerboard(TraversalPolicy shape, int parity) {
		CollectionProducer coordinateSum = gridIndex(shape, 0);
		for (int k = 1; k < shape.getDimensions(); k++) {
			coordinateSum = coordinateSum.add(gridIndex(shape, k));
		}

		CollectionProducer odd = coordinateSum.mod(2.0);
		return parity % 2 == 0 ? odd.minus().add(1.0) : odd;
	}

	/**
	 * Returns the index of every cell of a grid along one axis, as a field over the grid: the
	 * value at grid position {@code (i0, ..., i(d-1))} is {@code i_axis}. Multiplying by the
	 * spacing and adding the origin gives the coordinate field along that axis, from which
	 * analytic fields and initial conditions are built without any host loop.
	 *
	 * @param shape the shape of the grid
	 * @param axis  the axis whose index is returned
	 * @return the index field, with the given shape
	 * @throws IllegalArgumentException if the axis is out of range
	 */
	default CollectionProducer gridIndex(TraversalPolicy shape, int axis) {
		if (axis < 0 || axis >= shape.getDimensions()) {
			throw new IllegalArgumentException("axis " + axis + " is not an axis of " + shape);
		}

		int size = shape.getTotalSize();
		long stride = 1;
		for (int k = axis + 1; k < shape.getDimensions(); k++) {
			stride *= shape.length(k);
		}

		// TODO(review): on an FP32 backend a grid with more than 2^24 cells rounds these index
		// TODO(review): values when stored (see dynamic-indexing.md); read through a SplitIndexTraversalOrdering.
		CollectionProducer index = integers(0, size);
		if (stride > 1) {
			index = floor(index.divide((double) stride));
		}

		if (axis > 0) {
			index = index.mod(shape.length(axis));
		}

		return index.reshape(shape.withOrder(null).traverseEach());
	}

	/**
	 * Returns the grid spacing along an axis from the {@code double... spacing} argument that every
	 * operator of this interface accepts, where a single value applies to every axis.
	 *
	 * @param spacing    the spacings, one per axis or a single shared value
	 * @param axis       the axis
	 * @param dimensions the number of grid axes
	 * @return the spacing along the axis
	 * @throws IllegalArgumentException if the number of spacings is neither 1 nor the number of axes
	 */
	private static double gridSpacing(double[] spacing, int axis, int dimensions) {
		if (spacing.length == 1) return spacing[0];
		if (spacing.length != dimensions) {
			throw new IllegalArgumentException(spacing.length + " grid spacings for "
					+ dimensions + " grid axes");
		}

		return spacing[axis];
	}
}
