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

package org.almostrealism.calculus.test;

import io.almostrealism.code.ComputationBase;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.calculus.FieldCalculusFeatures;
import org.almostrealism.calculus.GridBoundary;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.ExplicitIndexTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntToDoubleFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests for the finite-difference operators of {@link FieldCalculusFeatures}: neighbour shifts
 * under each {@link GridBoundary}, the gradient, divergence and Laplacian against analytic
 * fields, second-order convergence under grid refinement, the curvature of a height field, the
 * Jacobi and red-black Gauss-Seidel Poisson relaxations driven by a {@link SimulationRunner}, the
 * derivative of the Laplacian, and agreement between the CPU and GPU backends.
 */
public class FieldCalculusTests extends TestSuiteBase {

	/** Evaluates a producer and returns its values in logical order. */
	private double[] values(CollectionProducer producer) {
		PackedCollection result = producer.evaluate();
		return result.toArray(0, result.getShape().getTotalSize());
	}

	/** Returns the coordinate field {@code origin + spacing * index} along an axis of a grid. */
	private CollectionProducer coordinate(TraversalPolicy shape, int axis, double origin, double spacing) {
		return gridIndex(shape, axis).multiply(spacing).add(origin);
	}

	/** Returns the value of a function at every point of a 2D grid, in logical order, on the host. */
	private double[] sample(int n0, int n1, double origin, double spacing, DoubleBinaryOperator f) {
		double[] out = new double[n0 * n1];
		for (int i = 0; i < n0; i++) {
			for (int j = 0; j < n1; j++) {
				out[i * n1 + j] = f.applyAsDouble(origin + i * spacing, origin + j * spacing);
			}
		}

		return out;
	}

	/** Returns the largest absolute difference between two arrays over the cells at least {@code margin} from every edge. */
	private double maxError(double[] expected, double[] actual, int n0, int n1, int margin) {
		double max = 0.0;
		for (int i = margin; i < n0 - margin; i++) {
			for (int j = margin; j < n1 - margin; j++) {
				max = Math.max(max, Math.abs(expected[i * n1 + j] - actual[i * n1 + j]));
			}
		}

		return max;
	}

	/**
	 * Returns the expected shift of a 2D field held in logical order, computed on the host from the
	 * definition of each boundary condition.
	 */
	private double[] expectedShift(double[] field, int n0, int n1, int axis, int offset,
								   String mode, double fixedValue) {
		double[] out = new double[field.length];
		for (int i = 0; i < n0; i++) {
			for (int j = 0; j < n1; j++) {
				int si = axis == 0 ? i + offset : i;
				int sj = axis == 1 ? j + offset : j;
				int n = axis == 0 ? n0 : n1;
				int s = axis == 0 ? si : sj;

				if (s < 0 || s >= n) {
					if (mode.equals("fixed")) {
						out[i * n1 + j] = fixedValue;
						continue;
					} else if (mode.equals("periodic")) {
						s = Math.floorMod(s, n);
					} else {
						s = Math.max(0, Math.min(n - 1, s));
					}
				}

				if (axis == 0) si = s; else sj = s;
				out[i * n1 + j] = field[si * n1 + sj];
			}
		}

		return out;
	}

	/** Returns the boundary condition named by {@code mode}. */
	private GridBoundary boundary(String mode, double fixedValue) {
		if (mode.equals("periodic")) return GridBoundary.periodic();
		if (mode.equals("clamped")) return GridBoundary.clamped();
		return GridBoundary.fixed(fixedValue);
	}

	/** Asserts that every shift of the field along every axis matches the host definition. */
	private void assertShifts(PackedCollection field, double[] logical, int n0, int n1) {
		for (String mode : new String[] { "periodic", "clamped", "fixed" }) {
			for (int axis = 0; axis < 2; axis++) {
				for (int offset : new int[] { -2, -1, 0, 1, 2 }) {
					double[] expected = expectedShift(logical, n0, n1, axis, offset, mode, 7.5);
					double[] actual = values(shift(cp(field), axis, offset, boundary(mode, 7.5)));
					for (int k = 0; k < expected.length; k++) {
						assertEquals(mode + " shift axis " + axis + " by " + offset + " at " + k,
								expected[k], actual[k]);
					}
				}
			}
		}
	}

	/**
	 * Every boundary mode supplies the specified ghost cells: periodic wraps, clamped repeats the
	 * edge cell, and fixed supplies the fixed value, for shifts of one and two cells in both
	 * directions along both axes. A zero shift reproduces the field (no ghost cells) with the
	 * ordering dropped from its shape.
	 */
	@Test(timeout = 120000)
	public void shiftBoundaryModes() {
		int n0 = 3;
		int n1 = 4;
		double[] logical = new double[n0 * n1];
		for (int k = 0; k < logical.length; k++) logical[k] = k;

		assertShifts(integers(0, n0 * n1).reshape(shape(n0, n1)).evaluate(), logical, n0, n1);
	}

	/**
	 * A field whose shape carries a {@link io.almostrealism.collect.TraversalOrdering} is shifted
	 * according to its logical layout: here the memory is read in reverse.
	 */
	@Test(timeout = 120000)
	public void shiftRespectsOrdering() {
		int n0 = 3;
		int n1 = 4;
		int size = n0 * n1;
		double[] logical = new double[size];
		for (int k = 0; k < size; k++) logical[k] = 10.0 * (size - 1 - k) + 1.0;

		PackedCollection memory = integers(0, size).multiply(10.0).add(1.0).evaluate();
		PackedCollection permutation = integers(0, size).minus().add(size - 1).evaluate();
		ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(permutation);
		TraversalPolicy shape = shape(n0, n1);
		PackedCollection ordered = new PackedCollection(shape, shape.getDimensions() - 1, memory, 0, order);
		assertTrue("the input should carry its ordering", ordered.getShape().getOrder() == order);

		assertShifts(ordered, logical, n0, n1);
	}

	/**
	 * {@code component} of a vector field whose shape carries a
	 * {@link io.almostrealism.collect.TraversalOrdering} returns the component's values in logical
	 * order: the extracted component is dense and must not inherit the whole vector field's
	 * ordering, which indexes a larger layout and would otherwise expose the wrong values.
	 */
	@Test(timeout = 120000)
	public void componentRespectsOrdering() {
		int d = 2;
		int n0 = 3;
		int n1 = 4;
		int size = d * n0 * n1;
		double[] logical = new double[size];
		for (int k = 0; k < size; k++) logical[k] = 10.0 * (size - 1 - k) + 1.0;

		PackedCollection memory = integers(0, size).multiply(10.0).add(1.0).evaluate();
		PackedCollection permutation = integers(0, size).minus().add(size - 1).evaluate();
		ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(permutation);
		TraversalPolicy shape = shape(d, n0, n1);
		PackedCollection ordered = new PackedCollection(shape, shape.getDimensions() - 1, memory, 0, order);
		assertTrue("the input should carry its ordering", ordered.getShape().getOrder() == order);

		for (int k = 0; k < d; k++) {
			double[] actual = values(component(cp(ordered), k));
			assertEquals("component " + k + " length", n0 * n1, actual.length);
			for (int m = 0; m < n0 * n1; m++) {
				assertEquals("component " + k + " at " + m, logical[k * n0 * n1 + m], actual[m]);
			}
		}
	}

	/**
	 * {@code gridIndex} rejects an axis outside {@code [0, dimensions)} rather than silently
	 * producing an incorrect field: a negative axis would otherwise make the stride span every
	 * dimension and return an all-zero index field, and an index field built from it would be wrong.
	 * A valid axis returns the cell index along that axis, repeating across the other axes.
	 */
	@Test(timeout = 120000)
	public void gridIndexRejectsInvalidAxis() {
		TraversalPolicy shape = shape(2, 3);

		for (int axis : new int[] { -1, 2, 3 }) {
			boolean rejected = false;
			try {
				gridIndex(shape, axis);
			} catch (IllegalArgumentException expected) {
				rejected = true;
			}

			assertTrue("gridIndex should reject axis " + axis + " of " + shape, rejected);
		}

		double[] along0 = values(gridIndex(shape, 0));
		double[] expected0 = { 0, 0, 0, 1, 1, 1 };
		for (int k = 0; k < expected0.length; k++) {
			assertEquals("gridIndex axis 0 at " + k, expected0[k], along0[k]);
		}

		double[] along1 = values(gridIndex(shape, 1));
		double[] expected1 = { 0, 1, 2, 0, 1, 2 };
		for (int k = 0; k < expected1.length; k++) {
			assertEquals("gridIndex axis 1 at " + k, expected1[k], along1[k]);
		}
	}

	/**
	 * {@code gridIndex} returns the cell coordinate along the requested axis, repeated across every
	 * other axis, for a 3D grid whose axes have distinct lengths. The construction broadcasts
	 * {@code integers(0, length(axis))} rather than dividing a linear index, so every stored value
	 * is a single-axis coordinate in {@code [0, length(axis))} - this both pins the layout and
	 * confirms the values never exceed one axis length (the property that keeps the field exact on an
	 * FP32 backend above {@code 2^24} cells).
	 */
	@Test(timeout = 120000)
	public void gridIndexBroadcastsPerAxis() {
		int n0 = 2;
		int n1 = 3;
		int n2 = 4;
		TraversalPolicy shape = shape(n0, n1, n2);

		for (int axis = 0; axis < 3; axis++) {
			int length = shape.length(axis);
			double[] actual = values(gridIndex(shape, axis));
			assertEquals("gridIndex axis " + axis + " length", n0 * n1 * n2, actual.length);

			for (int i = 0; i < n0; i++) {
				for (int j = 0; j < n1; j++) {
					for (int k = 0; k < n2; k++) {
						int coordinate = axis == 0 ? i : axis == 1 ? j : k;
						int p = (i * n1 + j) * n2 + k;
						assertTrue("gridIndex axis " + axis + " value in range at " + p,
								actual[p] >= 0 && actual[p] < length);
						assertEquals("gridIndex axis " + axis + " at " + p, coordinate, actual[p]);
					}
				}
			}
		}
	}

	/** Asserts that {@code slice} rejects the given arguments at the API boundary. */
	private void assertSliceRejected(int axis, int start, int length, CollectionProducer collection) {
		boolean rejected = false;
		try {
			slice(axis, start, length, collection);
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}

		assertTrue("slice should reject axis=" + axis + " start=" + start + " length=" + length, rejected);
	}

	/**
	 * {@code slice} fails fast at the API boundary when the axis is out of range or the half-open
	 * range {@code [start, start + length)} is not within the axis, rather than throwing an opaque
	 * index error or producing an out-of-bounds projection. A valid slice extracts the requested
	 * consecutive layers, dropping the source ordering.
	 */
	@Test(timeout = 120000)
	public void sliceValidatesRange() {
		TraversalPolicy shape = shape(3, 4);
		CollectionProducer grid = integers(0, shape.getTotalSize()).reshape(shape);
		PackedCollection stored = grid.evaluate();

		assertSliceRejected(-1, 0, 1, cp(stored));
		assertSliceRejected(2, 0, 1, cp(stored));
		assertSliceRejected(0, 2, 2, cp(stored));
		assertSliceRejected(1, 0, 5, cp(stored));
		assertSliceRejected(0, -1, 2, cp(stored));
		assertSliceRejected(0, 0, 0, cp(stored));

		double[] rows = values(slice(0, 1, 2, cp(stored)));
		double[] expected = { 4, 5, 6, 7, 8, 9, 10, 11 };
		assertEquals(expected.length, rows.length);
		for (int k = 0; k < expected.length; k++) {
			assertEquals("slice rows at " + k, expected[k], rows[k]);
		}
	}

	/**
	 * The 5-point and 9-point Laplacians of {@code sin(kx x) sin(ky y)} on a periodic grid are
	 * {@code -(kx^2 + ky^2) f} up to their leading truncation errors, {@code (kx^4 + ky^4) h^2 / 12}
	 * and the isotropic {@code (kx^2 + ky^2)^2 h^2 / 12} respectively, and each equals the exact
	 * eigenvalue of its stencil to rounding.
	 */
	@Test(timeout = 120000)
	public void laplacianOfSineProduct() {
		int n = 32;
		double kx = 2.0;
		double ky = 3.0;
		double h = 2 * Math.PI / n;
		TraversalPolicy shape = shape(n, n);

		PackedCollection f = sin(coordinate(shape, 0, 0.0, h).multiply(kx))
				.multiply(sin(coordinate(shape, 1, 0.0, h).multiply(ky))).evaluate();
		double[] field = sample(n, n, 0.0, h, (x, y) -> Math.sin(kx * x) * Math.sin(ky * y));

		double sx = Math.sin(kx * h / 2);
		double sy = Math.sin(ky * h / 2);
		double fivePoint = -4.0 * (sx * sx + sy * sy) / (h * h);
		double ninePoint = (4.0 * (2 * Math.cos(kx * h) + 2 * Math.cos(ky * h))
				+ 4 * Math.cos(kx * h) * Math.cos(ky * h) - 20.0) / (6.0 * h * h);
		double continuous = -(kx * kx + ky * ky);
		double truncation = (Math.pow(kx, 4) + Math.pow(ky, 4)) * h * h / 12.0;
		double isotropicTruncation = continuous * continuous * h * h / 12.0;

		double[] five = values(laplacian(cp(f), GridBoundary.periodic(), h));
		double[] nine = values(ninePointLaplacian(cp(f), GridBoundary.periodic(), h));

		for (int k = 0; k < field.length; k++) {
			assertEquals(fivePoint * field[k], five[k], 1e-3);
			assertEquals(ninePoint * field[k], nine[k], 1e-3);
			assertEquals(continuous * field[k], five[k], 1.1 * truncation);
			assertEquals(continuous * field[k], nine[k], 1.1 * isotropicTruncation);
		}
	}

	/**
	 * The operators are dimension-generic: on a periodic 3D grid with a different spacing along
	 * each axis, the 7-point Laplacian of {@code sin(x) sin(2y) sin(z)} and the divergence of its
	 * central-difference gradient (a stencil of twice the width) each equal the exact eigenvalue of
	 * their stencil. The gradient is stored before its divergence is taken: composing the two in 3D
	 * nests shifts of shifts deeply enough that simplifying the kernel's index expressions takes
	 * minutes.
	 */
	@Test(timeout = 120000)
	public void laplacian3D() {
		int n0 = 8;
		int n1 = 12;
		int n2 = 10;
		double[] h = { 2 * Math.PI / n0, 2 * Math.PI / n1, 2 * Math.PI / n2 };
		TraversalPolicy shape = shape(n0, n1, n2);

		PackedCollection f = sin(coordinate(shape, 0, 0.0, h[0]))
				.multiply(sin(coordinate(shape, 1, 0.0, h[1]).multiply(2.0)))
				.multiply(sin(coordinate(shape, 2, 0.0, h[2]))).evaluate();
		double[] field = values(cp(f));

		double eigenvalue = 0.0;
		double wideEigenvalue = 0.0;
		double[] k = { 1.0, 2.0, 1.0 };
		for (int a = 0; a < 3; a++) {
			double s = Math.sin(k[a] * h[a] / 2);
			double w = Math.sin(k[a] * h[a]);
			eigenvalue -= 4.0 * s * s / (h[a] * h[a]);
			wideEigenvalue -= w * w / (h[a] * h[a]);
		}

		double[] lap = values(laplacian(cp(f), GridBoundary.periodic(), h));
		PackedCollection grad = gradient(cp(f), GridBoundary.periodic(), h).evaluate();
		double[] divGrad = values(divergence(cp(grad), GridBoundary.periodic(), h));
		assertEquals(n0 * n1 * n2, lap.length);

		for (int i = 0; i < field.length; i++) {
			assertEquals(eigenvalue * field[i], lap[i], 1e-3);
			assertEquals(wideEigenvalue * field[i], divGrad[i], 1e-3);
		}
	}

	/**
	 * The central-difference gradient of a linear field is exact away from the edges, and the
	 * divergence of the position field {@code (x, y)} is exactly 2 there, for every boundary mode.
	 */
	@Test(timeout = 120000)
	public void gradientOfLinearFieldIsExact() {
		int n0 = 6;
		int n1 = 7;
		double h0 = 0.5;
		double h1 = 0.25;
		TraversalPolicy shape = shape(n0, n1);

		PackedCollection x = coordinate(shape, 0, -1.0, h0).evaluate();
		PackedCollection y = coordinate(shape, 1, 2.0, h1).evaluate();
		PackedCollection linear = cp(x).multiply(3.0).add(cp(y).multiply(-2.0)).add(1.0).evaluate();
		PackedCollection position = concat(0, cp(x).reshape(1, n0, n1), cp(y).reshape(1, n0, n1)).evaluate();

		for (GridBoundary boundary : List.of(GridBoundary.periodic(), GridBoundary.clamped(), GridBoundary.fixed(0.0))) {
			double[] grad = values(gradient(cp(linear), boundary, h0, h1));
			double[] div = values(divergence(cp(position), boundary, h0, h1));
			assertEquals(2 * n0 * n1, grad.length);

			for (int i = 1; i < n0 - 1; i++) {
				for (int j = 1; j < n1 - 1; j++) {
					assertEquals(boundary + " d/dx", 3.0, grad[i * n1 + j], 1e-4);
					assertEquals(boundary + " d/dy", -2.0, grad[n0 * n1 + i * n1 + j], 1e-4);
					assertEquals(boundary + " div", 2.0, div[i * n1 + j], 1e-4);
				}
			}
		}
	}

	/**
	 * Under grid refinement the error of the 5-point and 9-point Laplacians, the divergence of a
	 * gradient, and the curvature of a smooth height field falls by a factor of about four per
	 * halving of the spacing: the operators are second-order accurate.
	 */
	@Test(timeout = 300000)
	public void secondOrderConvergence() {
		double amplitude = 0.3;
		int[] sizes = { 16, 32, 64 };
		double[][] errors = new double[4][sizes.length];

		for (int s = 0; s < sizes.length; s++) {
			int n = sizes[s];
			double h = 2 * Math.PI / n;
			TraversalPolicy shape = shape(n, n);

			PackedCollection f = sin(coordinate(shape, 0, 0.0, h))
					.multiply(cos(coordinate(shape, 1, 0.0, h).multiply(2.0))).evaluate();
			double[] expectedLaplacian = sample(n, n, 0.0, h, (x, y) -> -5.0 * Math.sin(x) * Math.cos(2 * y));

			PackedCollection height = sin(coordinate(shape, 0, 0.0, h))
					.multiply(sin(coordinate(shape, 1, 0.0, h))).multiply(amplitude).evaluate();
			double[] expectedCurvature = sample(n, n, 0.0, h, (x, y) -> {
				double hx = amplitude * Math.cos(x) * Math.sin(y);
				double hy = amplitude * Math.sin(x) * Math.cos(y);
				double hxx = -amplitude * Math.sin(x) * Math.sin(y);
				double hyy = hxx;
				double hxy = amplitude * Math.cos(x) * Math.cos(y);
				double g = 1 + hx * hx + hy * hy;
				return ((1 + hy * hy) * hxx - 2 * hx * hy * hxy + (1 + hx * hx) * hyy) / Math.pow(g, 1.5);
			});

			GridBoundary periodic = GridBoundary.periodic();
			errors[0][s] = maxError(expectedLaplacian, values(laplacian(cp(f), periodic, h)), n, n, 0);
			errors[1][s] = maxError(expectedLaplacian, values(ninePointLaplacian(cp(f), periodic, h)), n, n, 0);
			errors[2][s] = maxError(expectedLaplacian,
					values(divergence(gradient(cp(f), periodic, h), periodic, h)), n, n, 0);
			errors[3][s] = maxError(expectedCurvature, values(heightFieldCurvature(cp(height), periodic, h)), n, n, 0);
		}

		String[] names = { "5-point Laplacian", "9-point Laplacian", "div(grad)", "height-field curvature" };
		for (int op = 0; op < names.length; op++) {
			for (int s = 1; s < sizes.length; s++) {
				double order = Math.log(errors[op][s - 1] / errors[op][s]) / Math.log(2.0);
				log(names[op] + " n=" + sizes[s] + " error=" + errors[op][s] + " order=" + order);
				assertTrue(names[op] + " observed order " + order + " at n=" + sizes[s], order > 1.8);
			}
		}
	}

	/**
	 * The curvature of a spherical cap {@code h = sqrt(R^2 - r^2)} is {@code -2/R} (its mean
	 * curvature has magnitude {@code 1/R}) in the interior of the grid, while the linearized form
	 * underestimates it where the slope is not small.
	 */
	@Test(timeout = 120000)
	public void sphericalCapCurvature() {
		double radius = 1.0;
		int n = 33;
		double half = 0.4;
		double h = 2 * half / (n - 1);
		TraversalPolicy shape = shape(n, n);

		CollectionProducer x = coordinate(shape, 0, -half, h);
		CollectionProducer y = coordinate(shape, 1, -half, h);
		PackedCollection cap = x.sq().add(y.sq()).minus().add(radius * radius).sqrt().evaluate();

		double[] curvature = values(heightFieldCurvature(cp(cap), GridBoundary.clamped(), h));
		double[] linearized = values(linearizedHeightFieldCurvature(cp(cap), GridBoundary.clamped(), h));
		double[] expected = new double[n * n];
		Arrays.fill(expected, -2.0 / radius);

		double error = maxError(expected, curvature, n, n, 2);
		log("spherical cap curvature max interior error=" + error);
		assertTrue("curvature error " + error, error < 5e-3);

		int corner = 2 * n + 2;
		assertTrue("the linearized curvature should differ where the slope is large",
				Math.abs(linearized[corner] - curvature[corner]) > 0.1);
	}

	/**
	 * A manufactured Dirichlet problem: {@code laplacian(p) = -2 pi^2 sin(pi x) sin(pi y)} on the
	 * unit square with {@code p = 0} on the boundary, whose solution is
	 * {@code sin(pi x) sin(pi y)}. Weighted Jacobi and red-black Gauss-Seidel are each run as the
	 * step of a {@link SimulationRunner} system (relax into a next-state collection, copy back),
	 * the residual norm is recorded every few steps, and it must fall at every sample. Gauss-Seidel
	 * reaches a smaller residual than Jacobi in the same number of steps, and over-relaxed
	 * Gauss-Seidel converges to the discrete solution, which matches the analytic one to the
	 * second-order discretization error.
	 */
	@Test(timeout = 300000)
	public void poissonRelaxationConverges() {
		int n = 15;
		double h = 1.0 / (n + 1);
		TraversalPolicy shape = shape(n, n);
		GridBoundary dirichlet = GridBoundary.fixed(0.0);

		PackedCollection f = sin(coordinate(shape, 0, h, h).multiply(Math.PI))
				.multiply(sin(coordinate(shape, 1, h, h).multiply(Math.PI)))
				.multiply(-2 * Math.PI * Math.PI).evaluate();

		int interval = 15;
		int samples = 20;
		double[] jacobi = relax(f, new PackedCollection(shape), dirichlet, h, false, 1.0, interval, samples);
		double[] gaussSeidel = relax(f, new PackedCollection(shape), dirichlet, h, true, 1.0, interval, samples);

		for (int j = 1; j < samples; j++) {
			assertTrue("Jacobi residual rose at sample " + j, jacobi[j] < jacobi[j - 1]);
			assertTrue("Gauss-Seidel residual rose at sample " + j, gaussSeidel[j] < gaussSeidel[j - 1]);
		}

		log("residual after " + interval * samples + " steps: Jacobi=" + jacobi[samples - 1]
				+ " Gauss-Seidel=" + gaussSeidel[samples - 1]);
		assertTrue(gaussSeidel[samples - 1] < jacobi[samples - 1]);

		PackedCollection solution = new PackedCollection(shape);
		double omega = 2.0 / (1.0 + Math.sin(Math.PI * h));
		double[] sor = relax(f, solution, dirichlet, h, true, omega, interval, samples);
		assertTrue("SOR residual " + sor[samples - 1], sor[samples - 1] < 1e-2 * sor[0]);

		double[] expected = sample(n, n, h, h, (x, y) -> Math.sin(Math.PI * x) * Math.sin(Math.PI * y));
		double error = maxError(expected, solution.toArray(0, n * n), n, n, 0);
		log("SOR solution error=" + error);
		assertTrue("solution error " + error, error < 1e-2);
	}

	/**
	 * Runs a Poisson relaxation as a {@link SimulationRunner} system and returns the recorded
	 * residual norms.
	 *
	 * @param f           the right-hand side
	 * @param p           the estimate, initially zero, which receives the final estimate
	 * @param boundary    the boundary condition
	 * @param h           the grid spacing
	 * @param gaussSeidel whether to use red-black Gauss-Seidel rather than weighted Jacobi
	 * @param omega       the relaxation weight
	 * @param interval    the number of steps between residual samples
	 * @param samples     the number of samples
	 * @return the residual norm after each interval
	 */
	private double[] relax(PackedCollection f, PackedCollection p, GridBoundary boundary, double h,
						   boolean gaussSeidel, double omega, int interval, int samples) {
		PackedCollection next = new PackedCollection(f.getShape());

		Temporal system = () -> {
			OperationList tick = new OperationList("Poisson relaxation");
			CollectionProducer relaxed = gaussSeidel
					? redBlackGaussSeidelStep(cp(p), cp(f), boundary, omega, h)
					: jacobiStep(cp(p), cp(f), boundary, omega, h);
			tick.add(a("relax", cp(next), relaxed));
			tick.add(a("copyBack", cp(p), cp(next)));
			return tick;
		};

		SimulationRunner runner = new SimulationRunner(system, 1.0);
		SimulationRunner.Recording residual = runner.record(
				poissonResidualNorm(cp(p), cp(f), boundary, h), interval, samples);
		runner.run((long) interval * samples);

		double[] norms = residual.getBuffer().toArray(0, samples);
		runner.destroy();
		return norms;
	}

	/**
	 * The Laplacian is differentiable: its Jacobian with respect to the field on a periodic grid is
	 * the stencil matrix, {@code -4} on the diagonal and {@code 1} at each of the four neighbours.
	 */
	@Test(timeout = 120000)
	public void laplacianJacobianIsStencil() {
		int n = 4;
		int size = n * n;
		PackedCollection field = new PackedCollection(shape(n, n)).randFill();
		CollectionProducer input = cp(field);

		double[] jacobian = values(laplacian(input, GridBoundary.periodic(), 1.0).delta(input));
		assertEquals(size * size, jacobian.length);

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				for (int k = 0; k < n; k++) {
					for (int l = 0; l < n; l++) {
						double expected = 0.0;
						if (i == k && j == l) expected = -4.0;
						else if (j == l && (Math.floorMod(i - k, n) == 1 || Math.floorMod(k - i, n) == 1)) expected = 1.0;
						else if (i == k && (Math.floorMod(j - l, n) == 1 || Math.floorMod(l - j, n) == 1)) expected = 1.0;

						int row = i * n + j;
						int col = k * n + l;
						assertEquals("d lap[" + i + "," + j + "] / d f[" + k + "," + l + "]",
								expected, jacobian[row * size + col], 1e-5);
					}
				}
			}
		}
	}

	/**
	 * The operators compile and give the same results on every available backend: the CPU and,
	 * where the host provides one, the GPU.
	 */
	@Test(timeout = 300000)
	public void operatorsOnEachBackend() {
		List<ComputeRequirement> backends = Stream.of(ComputeRequirement.CPU, ComputeRequirement.GPU)
				.filter(r -> Hardware.getLocalHardware().isAvailable(r))
				.collect(Collectors.toList());
		assertFalse("no backend is available", backends.isEmpty());
		log("backends=" + backends);

		int n = 24;
		double h = 2 * Math.PI / n;
		double[] field = sample(n, n, 0.0, h, (x, y) -> Math.sin(x) * Math.sin(2 * y));
		PackedCollection f = sin(coordinate(shape(n, n), 0, 0.0, h))
				.multiply(sin(coordinate(shape(n, n), 1, 0.0, h).multiply(2.0))).evaluate();
		double eigenvalue = -4.0 * (Math.pow(Math.sin(h / 2), 2) + Math.pow(Math.sin(h), 2)) / (h * h);

		for (ComputeRequirement backend : backends) {
			assertBackend(backend, "laplacian", () -> laplacian(cp(f), GridBoundary.periodic(), h),
					k -> eigenvalue * field[k], 1e-3);
			assertBackend(backend, "jacobi", () -> jacobiStep(cp(f), zeros(shape(n, n)), GridBoundary.periodic(), 1.0, h),
					k -> field[k] * (1.0 + eigenvalue * h * h / 4.0), 1e-4);
			assertBackend(backend, "curvature", () -> heightFieldCurvature(cp(f).multiply(1e-3), GridBoundary.periodic(), h),
					k -> 1e-3 * eigenvalue * field[k], 1e-4);
		}
	}

	/**
	 * Evaluates a computation on one backend and compares it with the expected values.
	 *
	 * @param backend     the backend to evaluate on
	 * @param name        the name of the computation, for diagnostics
	 * @param computation builds a fresh computation
	 * @param expected    the expected value of each element
	 * @param tolerance   the absolute tolerance
	 */
	private void assertBackend(ComputeRequirement backend, String name, Supplier<CollectionProducer> computation,
							   IntToDoubleFunction expected, double tolerance) {
		CollectionProducer producer = computation.get();
		((ComputationBase) producer).setComputeRequirements(List.of(backend));
		double[] actual = values(producer);
		for (int k = 0; k < actual.length; k++) {
			assertEquals(name + " element " + k + " on " + backend, expected.applyAsDouble(k), actual[k], tolerance);
		}
	}
}
