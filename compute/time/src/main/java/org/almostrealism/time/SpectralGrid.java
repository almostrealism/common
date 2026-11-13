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

package org.almostrealism.time;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * A regular periodic grid of samples: the dimensions of the grid and the physical distance
 * between samples along each of its axes. This is the geometry that the spectral operations of
 * {@link SpectralFeatures} need to turn frequency indices into wavenumbers, and the quantities
 * derived from it alone (the radial bin width and bin count of a radial power spectrum) are
 * computed here, on the host, without reading any grid data.
 *
 * <p>Spacing is given in axis order: for a {@code [rows, cols]} grid where rows run along y,
 * the spacing is {@code (dy, dx)}. A single value applies to every axis. The shape of a grid
 * never carries a {@link io.almostrealism.collect.TraversalOrdering}: every intermediate a
 * spectral operation derives from a grid is dense and in plain row-major order, so an ordering
 * on the shape this grid is constructed from is dropped.</p>
 *
 * <p>The per-cell quantities a grid defines on its own, the wavenumber and radial bin of every
 * cell, the membership of cells in radial bins and the Fourier symbol of the inverse Laplacian,
 * are graphs built by the grid ({@link #wavenumberSquared()}, {@link #wavenumber()},
 * {@link #radialBin()}, {@link #radialMembership()}, {@link #inverseLaplacian()}); nothing about
 * them depends on any field sampled on the grid.</p>
 *
 * @see SpectralFeatures
 * @author Michael Murray
 */
public class SpectralGrid implements CollectionFeatures {
	/**
	 * The number of consecutive integers, starting at zero, that a single precision float
	 * represents exactly ({@code 2^24}). Cell indices are decoded into per-axis coordinates with
	 * floating-point arithmetic, so any index a spectral operation decodes must be below this
	 * limit for the decoded coordinates to be exact on an FP32 backend.
	 */
	public static final int MAX_EXACT_INDEX = 1 << 24;

	/** The dimensions of the grid, with no ordering. */
	private final TraversalPolicy shape;

	/** The sample spacing along each axis, in axis order. */
	private final double[] spacing;

	/**
	 * Creates a grid with the dimensions of {@code shape} and the given sample spacing.
	 *
	 * @param shape   the grid dimensions; any ordering it carries is dropped
	 * @param spacing one spacing per axis, or a single value for every axis
	 * @throws IllegalArgumentException if the number of values matches neither 1 nor the grid rank,
	 *                                  any spacing is not positive and finite, or any axis extent
	 *                                  {@code n_a * d_a} or wavenumber {@code 2 pi / d_a} overflows
	 */
	public SpectralGrid(TraversalPolicy shape, double... spacing) {
		this.shape = shape.withOrder(null);

		int dims = this.shape.getDimensions();
		if (spacing.length != 1 && spacing.length != dims) {
			throw new IllegalArgumentException("Expected 1 or " + dims + " spacings, not " + spacing.length);
		}

		double[] d = IntStream.range(0, dims).mapToDouble(a -> spacing[spacing.length == 1 ? 0 : a]).toArray();
		if (IntStream.range(0, dims).anyMatch(a -> !(d[a] > 0) || !Double.isFinite(d[a]))) {
			throw new IllegalArgumentException("Grid spacing must be positive and finite");
		}

		if (IntStream.range(0, dims).anyMatch(a ->
				!Double.isFinite(this.shape.length(a) * d[a]) || !Double.isFinite(2 * Math.PI / d[a]))) {
			throw new IllegalArgumentException("Grid spacing must give a finite extent and wavenumber on " + this.shape);
		}

		this.spacing = d;
	}

	/** Returns the dimensions of this grid, which carry no ordering. */
	public TraversalPolicy getShape() { return shape; }

	/** Returns the number of axes of this grid. */
	public int getDimensions() { return shape.getDimensions(); }

	/** Returns the number of cells of this grid. */
	public int getCellCount() { return shape.getTotalSize(); }

	/**
	 * Returns the number of samples along one axis.
	 *
	 * @param axis the axis
	 * @return the axis length
	 */
	public int length(int axis) { return shape.length(axis); }

	/**
	 * Returns the physical distance between samples along one axis.
	 *
	 * @param axis the axis
	 * @return the sample spacing along that axis
	 */
	public double getSpacing(int axis) { return spacing[axis]; }

	/**
	 * Returns the angular wavenumber {@code 2 pi / (n_a d_a)} of frequency index 1 along one
	 * axis: the wavenumber of every frequency index along that axis is a multiple of it.
	 *
	 * @param axis the axis
	 * @return the fundamental wavenumber of that axis
	 */
	public double getFundamentalWavenumber(int axis) {
		return 2 * Math.PI / (length(axis) * spacing[axis]);
	}

	/**
	 * Returns the width, in angular wavenumber, of the bins of a radial power spectrum of this
	 * grid: the fundamental wavenumber {@code 2 pi / L} of the longest grid extent
	 * {@code L = max(n_a * d_a)}.
	 *
	 * @return the radial bin width
	 * @see SpectralFeatures#radialPowerSpectrum
	 */
	public double getRadialBinWidth() {
		double extent = IntStream.range(0, getDimensions())
				.mapToDouble(a -> length(a) * spacing[a]).max().orElseThrow();
		return 2 * Math.PI / extent;
	}

	/**
	 * Returns the number of bins of a radial power spectrum of this grid: enough to reach the
	 * smallest Nyquist wavenumber {@code min(pi / d_a)}. For an {@code n x n} grid with equal
	 * spacing this is {@code n / 2 + 1}.
	 *
	 * <p>The last bin is the last one whose centre is at or below that Nyquist wavenumber; cells
	 * that round into the bin beyond it lie past Nyquist and are ignored. Every counted bin holds
	 * at least one cell: the axis of the longest extent {@code L = n_L d_L} has wavenumbers at
	 * every multiple of the bin width up to {@code n_L / 2}, and since the smallest Nyquist
	 * wavenumber belongs to the axis of the largest spacing, the bin count never exceeds
	 * {@code n_L / 2 + 1}.</p>
	 *
	 * @return the radial bin count
	 * @see SpectralFeatures#radialPowerSpectrum
	 */
	public int getRadialBinCount() {
		double nyquist = Arrays.stream(spacing).map(d -> Math.PI / d).min().orElseThrow();
		return (int) Math.floor(nyquist / getRadialBinWidth() + 1e-9) + 1;
	}

	/**
	 * Validates that an operation which decodes {@code indicesPerCell} flat indices for every
	 * cell of this grid decodes only indices that a single precision float represents exactly.
	 *
	 * @param operation      the operation that decodes the indices, for the error message
	 * @param indicesPerCell the number of flat indices the operation decodes per cell
	 * @throws IllegalArgumentException if the number of decoded indices exceeds {@link #MAX_EXACT_INDEX}
	 */
	protected void requireExactIndices(String operation, int indicesPerCell) {
		long count = (long) indicesPerCell * getCellCount();
		if (count > MAX_EXACT_INDEX) {
			throw new IllegalArgumentException(operation + " decodes " + count +
					" flat indices, but only " + MAX_EXACT_INDEX + " are exact in single precision");
		}
	}

	/**
	 * Validates that the squared wavenumbers of this grid are representable in double precision.
	 * A spacing can give a finite wavenumber whose square is not: a tiny spacing makes the largest
	 * {@code |k|^2}, the sum over the axes of {@code (k_a * floor(n_a / 2))^2}, overflow to
	 * infinity, and a huge spacing makes {@code dk^2} (the square of the
	 * {@link #getRadialBinWidth() radial bin width}, which no nonzero mode falls below) underflow
	 * past the normal range, so nonzero modes become indistinguishable from the zero mode. Either
	 * would turn every spectral operator built from {@code |k|^2} into infinities or zeros.
	 *
	 * @param operation the operation that squares the wavenumbers, for the error message
	 * @throws IllegalArgumentException if the largest {@code |k|^2} is not finite, or half of
	 *                                  {@code dk^2} is below {@link Double#MIN_NORMAL}
	 */
	public void requireRepresentableWavenumbers(String operation) {
		double largest = IntStream.range(0, getDimensions())
				.mapToDouble(a -> getFundamentalWavenumber(a) * (length(a) / 2))
				.map(k -> k * k).sum();
		double width = getRadialBinWidth();

		if (!Double.isFinite(largest) || !(0.5 * width * width >= Double.MIN_NORMAL)) {
			throw new IllegalArgumentException(operation + " squares wavenumbers of " + this +
					" outside the range of double precision");
		}
	}

	/**
	 * Returns the squared angular wavenumber {@code |k|^2} of the cells of this grid identified by
	 * their flat (row-major) index. This is the building block of spectral operators: the Fourier
	 * symbol of the Laplacian is {@code -|k|^2}.
	 *
	 * <p>The index is decoded with floating-point arithmetic, so the grid may have at most
	 * {@link #MAX_EXACT_INDEX} cells.</p>
	 *
	 * @param cell producer of flat cell indices into this grid; any shape
	 * @return a producer of {@code |k|^2} with the shape of {@code cell}
	 * @throws IllegalArgumentException if the grid has more than {@link #MAX_EXACT_INDEX} cells, or
	 *                                  its squared wavenumbers are not
	 *                                  {@link #requireRepresentableWavenumbers representable}
	 */
	protected CollectionProducer wavenumberSquared(Producer<PackedCollection> cell) {
		requireExactIndices("wavenumberSquared", 1);
		requireRepresentableWavenumbers("wavenumberSquared");
		CollectionProducer index = c(cell);

		CollectionProducer total = null;
		int stride = getCellCount();

		for (int axis = 0; axis < getDimensions(); axis++) {
			int n = length(axis);
			stride /= n;

			CollectionProducer position = stride == 1 ? index :
					index.subtract(index.mod((double) stride)).divide(c(stride));
			CollectionProducer m = position.mod((double) n);
			CollectionProducer signed = m.subtract(greaterThan(m, c(n / 2.0 - 0.5), c(n), c(0.0)));
			CollectionProducer k = signed.multiply(c(getFundamentalWavenumber(axis)));

			total = total == null ? k.sq() : total.add(k.sq());
		}

		return total;
	}

	/**
	 * Returns the squared angular wavenumber {@code |k|^2} of every cell of this grid. The result
	 * is dense and in row-major order, like the {@link #getShape() shape} of the grid.
	 *
	 * @return a producer of {@code |k|^2} with the dimensions of this grid and no ordering
	 */
	public CollectionProducer wavenumberSquared() {
		return wavenumberSquared(integers(0, getCellCount())).reshape(shape);
	}

	/**
	 * Returns the {@link SpectralFeatures#radialPowerSpectrum radial bin} of every cell of this
	 * grid: the integer nearest to {@code |k| / dk}, where {@code dk} is the
	 * {@link #getRadialBinWidth() radial bin width}.
	 *
	 * @return a producer of bin indices, shaped {@code [cells]} in row-major cell order
	 */
	public CollectionProducer radialBin() {
		return floor(wavenumber().divide(c(getRadialBinWidth())).add(c(0.5)));
	}

	/**
	 * Returns the angular wavenumber magnitude {@code |k|} of every cell of this grid.
	 *
	 * @return a producer of {@code |k|}, shaped {@code [cells]} in row-major cell order
	 * @throws IllegalArgumentException if the grid has more than {@link #MAX_EXACT_INDEX} cells, or
	 *                                  its squared wavenumbers are not
	 *                                  {@link #requireRepresentableWavenumbers representable}
	 */
	public CollectionProducer wavenumber() {
		return sqrt(wavenumberSquared(integers(0, getCellCount())));
	}

	/**
	 * Returns the membership of every cell of this grid in every
	 * {@link SpectralFeatures#radialPowerSpectrum radial bin}: element {@code [b, c]} is 1 when
	 * the {@link #radialBin() radial bin} of cell {@code c} is {@code b}, and 0 otherwise. Cells
	 * beyond the last bin belong to no bin.
	 *
	 * <p>The matrix is dense, so its size grows as {@code bins * cells}; a grid whose matrix
	 * cannot be addressed with an {@code int} index is rejected rather than allowed to overflow.</p>
	 *
	 * @return a producer of the membership matrix, shaped
	 *         {@code [}{@link #getRadialBinCount() bins}{@code , cells]}
	 * @throws IllegalArgumentException if {@code bins * cells} exceeds {@link Integer#MAX_VALUE}
	 */
	public CollectionProducer radialMembership() {
		int cells = getCellCount();
		int bins = getRadialBinCount();

		// Tracked as a follow-up task: replace the dense membership matrix with a segmented reduction
		if ((long) bins * cells > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Radial membership of " + this + " needs a " + bins + " x " +
					cells + " matrix, which exceeds the addressable size");
		}

		CollectionProducer binOfCell = repeat(bins, isolated(radialBin().reshape(cells))).reshape(bins, cells);
		CollectionProducer bin = repeat(1, cells, integers(0, bins).reshape(bins, 1)).reshape(bins, cells);
		return greaterThan(abs(binOfCell.subtract(bin)), c(0.5), c(0.0), c(1.0));
	}

	/**
	 * Averages per-cell values of this grid over every
	 * {@link SpectralFeatures#radialPowerSpectrum radial bin}: bin {@code b} holds the mean of
	 * the values of the cells whose {@link #radialBin() radial bin} is {@code b}. Cells beyond the
	 * last bin are ignored. The averages are computed from the dense
	 * {@link #radialMembership() membership matrix}, so the intermediate size grows as
	 * {@code bins * cells}.
	 *
	 * @param values one value per cell of this grid, in row-major cell order; any shape with
	 *               {@link #getCellCount()} elements
	 * @return a producer of the {@link #getRadialBinCount() radial bin count} averages
	 * @throws IllegalArgumentException if {@code values} does not hold one value per cell, or
	 *                                  {@link #radialMembership()} rejects this grid
	 */
	public CollectionProducer radialAverage(Producer<PackedCollection> values) {
		int cells = getCellCount();
		if (shape(values).getTotalSize() != cells) {
			throw new IllegalArgumentException("Expected " + cells + " values for " + this +
					", not " + shape(values).getTotalSize());
		}

		int bins = getRadialBinCount();
		CollectionProducer membership = radialMembership();
		CollectionProducer weighted = membership.multiply(repeat(bins, reshape(shape(cells), values)).reshape(bins, cells));
		CollectionProducer total = isolated(weighted.sum(1).reshape(bins));
		CollectionProducer count = isolated(membership.sum(1).reshape(bins));
		return total.divide(count);
	}

	/**
	 * Returns the Fourier symbol of the inverse Laplacian on this grid, {@code -1 / |k|^2}, for
	 * every interleaved (real, imaginary) element of a complex spectrum of the grid, so that
	 * multiplying an {@link SpectralFeatures#fftn FFT} by it solves the periodic Poisson equation.
	 * The {@code k = 0} mode, for which the symbol is undefined, is set to zero, so the solution
	 * has zero mean.
	 *
	 * <p>The symbol is computed per interleaved element, whose flat index is decoded with
	 * floating-point arithmetic, so the grid may have at most {@code MAX_EXACT_INDEX / 2} cells.</p>
	 *
	 * @return a producer of the symbol, shaped like a complex grid {@code [n0, ..., nk, 2]}
	 * @throws IllegalArgumentException if the grid has more than {@code MAX_EXACT_INDEX / 2} cells
	 */
	public CollectionProducer inverseLaplacian() {
		requireExactIndices("inverseLaplacian", 2);

		CollectionProducer element = integers(0, 2 * getCellCount());
		CollectionProducer cell = element.subtract(element.mod(2.0)).divide(c(2.0));
		CollectionProducer k2 = wavenumberSquared(cell);

		double smallest = 0.5 * Math.pow(getRadialBinWidth(), 2);
		CollectionProducer nonzero = greaterThan(k2, c(smallest), c(1.0), c(0.0));
		return nonzero.minus().divide(k2.add(c(1.0)).subtract(nonzero)).reshape(shape.appendDimension(2));
	}

	@Override
	public String toString() {
		return shape + " spacing " + Arrays.toString(spacing);
	}
}
