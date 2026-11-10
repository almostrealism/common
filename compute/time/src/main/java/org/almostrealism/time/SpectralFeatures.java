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
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.time.computations.FourierTransform;

import java.util.stream.IntStream;

/**
 * Multidimensional spectral tools for fields sampled on regular periodic grids: N-dimensional
 * FFTs built from batched 1-D passes of {@link FourierTransform}, wavenumber grids, power and
 * radially averaged power spectra, dominant wavelength estimation and a spectral Poisson solver,
 * together with the element-wise operations on interleaved complex data (real and imaginary parts,
 * power, magnitude and phase) that they are built from.
 *
 * <p>Every method returns a {@link CollectionProducer}; nothing is evaluated on the host.
 * {@link TemporalFeatures} extends this interface, so every type with the temporal operations
 * also has these.</p>
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li>A <em>grid</em> is a real collection of shape {@code [n0, n1, ...]}; a <em>complex grid</em>
 *       has a trailing interleaved (real, imaginary) dimension: {@code [n0, n1, ..., 2]}. Every
 *       transformed axis length must be a power of 2.</li>
 *   <li>{@code spacing} gives the physical distance between samples along each axis, in axis order
 *       (for a {@code [rows, cols]} grid where rows run along y, {@code spacing = (dy, dx)}). A single
 *       value applies to every axis.</li>
 *   <li>Forward transforms are unnormalised, {@code X[k] = sum_n x[n] e^(-2 pi i k.n / N)}; the inverse
 *       carries the full {@code 1 / (n0 * n1 * ...)} factor, so {@code inverse(forward(x)) == x}.</li>
 *   <li>Frequency index {@code m} along an axis of length {@code n} is the signed index
 *       {@code m < n/2 ? m : m - n} (the Nyquist index {@code n/2} maps to {@code -n/2}), and its
 *       angular wavenumber is {@code 2 pi m / (n d)}.</li>
 * </ul>
 *
 * @see FourierTransform
 * @author Michael Murray
 */
public interface SpectralFeatures extends GeometryFeatures, MatrixFeatures {

	/**
	 * The number of consecutive integers, starting at zero, that a single precision float
	 * represents exactly ({@code 2^24}). Cell indices are decoded into per-axis coordinates with
	 * floating-point arithmetic, so any index a spectral operation decodes must be below this
	 * limit for the decoded coordinates to be exact on an FP32 backend.
	 */
	int MAX_EXACT_INDEX = 1 << 24;

	/**
	 * Transforms a complex grid along one axis, with no transposition: every line along the
	 * axis is transformed by one kernel thread of a single {@link FourierTransform}.
	 *
	 * @param complex the complex grid, shaped {@code [n0, ..., nk, 2]}
	 * @param axis    the grid axis to transform, in {@code [0, k]}
	 * @param inverse true for the inverse transform (which divides by the axis length)
	 * @return a producer with the shape of {@code complex}
	 * @throws IllegalArgumentException if {@code complex} is not a complex grid, the axis is out of
	 *                                  range, or its length is not a power of 2
	 */
	default CollectionProducer fftAxis(Producer<PackedCollection> complex, int axis, boolean inverse) {
		TraversalPolicy shape = complexGridShape(complex);
		int gridDimensions = shape.getDimensions() - 1;

		if (axis < 0 || axis >= gridDimensions) {
			throw new IllegalArgumentException("Axis " + axis + " is not a grid axis of " + shape);
		}

		int bins = shape.length(axis);
		int stride = 1;
		for (int a = axis + 1; a < gridDimensions; a++) {
			stride *= shape.length(a);
		}

		int lines = shape.getTotalSize() / (2 * bins);
		TraversalPolicy lineShape = new TraversalPolicy(lines, 2, bins).traverse(1);
		return new FourierTransform(lines, bins, stride, inverse, reshape(lineShape, complex)).reshape(shape);
	}

	/**
	 * Transforms a complex grid along every grid axis (an N-dimensional FFT). Each axis is one
	 * {@link #fftAxis(Producer, int, boolean) batched pass}, so a 2D transform is two kernels and a
	 * 3D transform three.
	 *
	 * @param complex the complex grid, shaped {@code [n0, ..., nk, 2]}
	 * @param inverse true for the inverse transform, normalised by {@code 1 / (n0 * ... * nk)}
	 * @return a producer with the shape of {@code complex}
	 */
	default CollectionProducer fftn(Producer<PackedCollection> complex, boolean inverse) {
		int gridDimensions = complexGridShape(complex).getDimensions() - 1;

		CollectionProducer result = null;
		for (int axis = 0; axis < gridDimensions; axis++) {
			result = fftAxis(result == null ? complex : result, axis, inverse);
		}

		return result;
	}

	/**
	 * Forward 2D FFT of a {@code [rows, cols, 2]} complex grid.
	 *
	 * @param complex the complex grid
	 * @return the unnormalised spectrum, shaped {@code [rows, cols, 2]}
	 * @throws IllegalArgumentException if {@code complex} is not a 2D complex grid
	 */
	default CollectionProducer fft2d(Producer<PackedCollection> complex) {
		return fftn(require2d(complex), false);
	}

	/**
	 * Inverse 2D FFT of a {@code [rows, cols, 2]} spectrum, normalised by {@code 1 / (rows * cols)}.
	 *
	 * @param spectrum the complex spectrum
	 * @return the complex grid, shaped {@code [rows, cols, 2]}
	 * @throws IllegalArgumentException if {@code spectrum} is not a 2D complex grid
	 */
	default CollectionProducer ifft2d(Producer<PackedCollection> spectrum) {
		return fftn(require2d(spectrum), true);
	}

	/**
	 * Converts a real grid into a complex grid with zero imaginary part.
	 *
	 * @param real the real grid, shaped {@code [n0, ..., nk]}
	 * @return the complex grid, shaped {@code [n0, ..., nk, 2]}
	 */
	default CollectionProducer toComplex(Producer<PackedCollection> real) {
		TraversalPolicy shape = shape(real);
		return complexFromParts(real, zeros(shape)).reshape(shape.appendDimension(2));
	}

	/**
	 * Extracts the real part of interleaved complex data.
	 *
	 * @param complex complex data shaped {@code [..., 2]}
	 * @return the real parts, with the last dimension removed (shape {@code [1]} for a single value)
	 */
	default CollectionProducer realPart(Producer<PackedCollection> complex) {
		return complexComponent(complex, 0);
	}

	/**
	 * Extracts the imaginary part of interleaved complex data.
	 *
	 * @param complex complex data shaped {@code [..., 2]}
	 * @return the imaginary parts, with the last dimension removed (shape {@code [1]} for a single value)
	 */
	default CollectionProducer imaginaryPart(Producer<PackedCollection> complex) {
		return complexComponent(complex, 1);
	}

	/**
	 * Computes the power spectrum (magnitude squared) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * power[k] = re[k]^2 + im[k]^2</p>
	 *
	 * <p>This is more efficient than computing the magnitude (which requires
	 * a square root) when the actual magnitude value isn't needed, such as
	 * for power spectral density or energy calculations.</p>
	 *
	 * <h3>Example Usage</h3>
	 * <pre>{@code
	 * // Compute FFT and get power spectrum
	 * Producer<PackedCollection> signal = ...;
	 * FourierTransform fft = fft(1024, signal);
	 *
	 * // Reshape to [1024, 2] for complex pairs
	 * CollectionProducer complex = fft.reshape(1024, 2);
	 *
	 * // Compute power spectrum
	 * CollectionProducer power = powerSpectrum(complex);
	 * // Output shape: [1024]
	 * }</pre>
	 *
	 * <p>Data of any rank is accepted: a {@code [rows, cols, 2]} spectrum produces a
	 * {@code [rows, cols]} power spectrum.</p>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs the power spectrum with the last dimension removed
	 */
	default CollectionProducer powerSpectrum(Producer<PackedCollection> complex) {
		return add(sq(realPart(complex)), sq(imaginaryPart(complex)));
	}

	/**
	 * Computes the phase angle (argument) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * phase[k] = atan2(im[k], re[k])</p>
	 *
	 * <p>The result is in radians, ranging from -PI to PI. This represents
	 * the angle of each complex value in the complex plane.</p>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs phase angles in radians with the last dimension removed
	 * @see #atan2(Producer, Producer)
	 */
	default CollectionProducer phase(Producer<PackedCollection> complex) {
		return atan2(imaginaryPart(complex), realPart(complex));
	}

	/**
	 * Computes the log magnitude (power in dB) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * logMag[k] = 10 * log10(re[k]^2 + im[k]^2)</p>
	 *
	 * <p>The result is in decibels (dB). A floor of {@code 1e-10} is added to the power
	 * to avoid log(0).</p>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs log magnitude in dB with the last dimension removed
	 */
	default CollectionProducer logMagnitude(Producer<PackedCollection> complex) {
		CollectionProducer safePower = add(powerSpectrum(complex), c(1e-10));
		return multiply(c(10.0 / Math.log(10.0)), log(safePower));
	}

	/**
	 * Computes the magnitude (absolute value) of complex data.
	 *
	 * <p>For complex data with interleaved [re, im] pairs, this computes:
	 * magnitude[k] = sqrt(re[k]^2 + im[k]^2)</p>
	 *
	 * @param complex Producer of complex data with shape [..., 2] where the last
	 *                dimension contains [real, imaginary] pairs
	 * @return A producer that outputs the magnitude with the last dimension removed
	 */
	default CollectionProducer complexMagnitude(Producer<PackedCollection> complex) {
		return sqrt(powerSpectrum(complex));
	}

	/**
	 * Returns the squared angular wavenumber {@code |k|^2} of the cells of a grid identified by
	 * their flat (row-major) index. This is the building block of spectral operators: the Fourier
	 * symbol of the Laplacian is {@code -|k|^2}.
	 *
	 * <p>The index is decoded with floating-point arithmetic, so the grid may have at most
	 * {@link #MAX_EXACT_INDEX} cells.</p>
	 *
	 * @param cell    producer of flat cell indices into {@code grid}; any shape
	 * @param grid    the grid shape
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return a producer of {@code |k|^2} with the shape of {@code cell}
	 * @throws IllegalArgumentException if the grid has more than {@link #MAX_EXACT_INDEX} cells,
	 *                                  or the spacing is invalid
	 */
	default CollectionProducer wavenumberSquared(Producer<PackedCollection> cell,
												 TraversalPolicy grid, double... spacing) {
		double[] d = gridSpacing(grid, spacing);
		requireExactIndices("wavenumberSquared", grid.getTotalSize());
		CollectionProducer index = c(cell);

		CollectionProducer total = null;
		int stride = grid.getTotalSize();

		for (int axis = 0; axis < grid.getDimensions(); axis++) {
			int n = grid.length(axis);
			stride /= n;

			CollectionProducer position = stride == 1 ? index :
					index.subtract(index.mod((double) stride)).divide(c(stride));
			CollectionProducer m = position.mod((double) n);
			CollectionProducer signed = m.subtract(greaterThan(m, c(n / 2.0 - 0.5), c(n), c(0.0)));
			CollectionProducer k = signed.multiply(c(2 * Math.PI / (n * d[axis])));

			total = total == null ? k.sq() : total.add(k.sq());
		}

		return total;
	}

	/**
	 * Returns the squared angular wavenumber {@code |k|^2} of every cell of a grid.
	 *
	 * @param grid    the grid shape
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return a producer of {@code |k|^2} shaped like {@code grid}
	 */
	default CollectionProducer wavenumberSquared(TraversalPolicy grid, double... spacing) {
		return wavenumberSquared(integers(0, grid.getTotalSize()), grid, spacing).reshape(grid);
	}

	/**
	 * Returns the width, in angular wavenumber, of the bins of a
	 * {@link #radialPowerSpectrum radial power spectrum}: the fundamental wavenumber
	 * {@code 2 pi / L} of the longest grid extent {@code L = max(n_a * d_a)}.
	 *
	 * @param grid    the grid shape
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return the radial bin width
	 */
	default double radialBinWidth(TraversalPolicy grid, double... spacing) {
		double[] d = gridSpacing(grid, spacing);
		double extent = IntStream.range(0, d.length).mapToDouble(a -> grid.length(a) * d[a]).max().orElseThrow();
		return 2 * Math.PI / extent;
	}

	/**
	 * Returns the number of bins of a {@link #radialPowerSpectrum radial power spectrum}: enough
	 * to reach the smallest Nyquist wavenumber {@code min(pi / d_a)} of the grid. For an
	 * {@code n x n} grid with equal spacing this is {@code n / 2 + 1}.
	 *
	 * <p>The last bin is the last one whose centre is at or below that Nyquist wavenumber; cells
	 * that round into the bin beyond it lie past Nyquist and are ignored. Every counted bin holds
	 * at least one cell: the axis of the longest extent {@code L = n_L d_L} has wavenumbers at
	 * every multiple of the bin width up to {@code n_L / 2}, and since the smallest Nyquist
	 * wavenumber belongs to the axis of the largest spacing, the bin count never exceeds
	 * {@code n_L / 2 + 1}.</p>
	 *
	 * @param grid    the grid shape
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return the radial bin count
	 */
	default int radialBinCount(TraversalPolicy grid, double... spacing) {
		double[] d = gridSpacing(grid, spacing);
		double nyquist = IntStream.range(0, d.length).mapToDouble(a -> Math.PI / d[a]).min().orElseThrow();
		return (int) Math.floor(nyquist / radialBinWidth(grid, d) + 1e-9) + 1;
	}

	/**
	 * Averages a power spectrum over shells of constant {@code |k|}. Bin {@code b} holds the mean
	 * power of the cells whose wavenumber magnitude is nearest to {@code b * dk}, where {@code dk} is
	 * the {@link #radialBinWidth radial bin width}; cells beyond the last bin (the corners of the
	 * spectrum) are ignored. Bin {@code b} therefore represents angular wavenumber {@code b * dk},
	 * i.e. wavelength {@code 2 pi / (b * dk)}.
	 *
	 * <p>The shell averages are computed from a dense {@code [bins, cells]} membership matrix,
	 * so the intermediate size grows as {@code bins * cells}; a grid whose membership matrix
	 * cannot be addressed with an {@code int} index is rejected rather than allowed to overflow.</p>
	 *
	 * @param power   a power spectrum in FFT order (as produced by
	 *                {@link #powerSpectrum(Producer) powerSpectrum} of an {@link #fftn fftn}), shaped
	 *                like the grid
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return a producer of the {@link #radialBinCount} radially averaged powers
	 * @throws IllegalArgumentException if {@code bins * cells} exceeds {@link Integer#MAX_VALUE},
	 *                                  or the grid has more than {@link #MAX_EXACT_INDEX} cells
	 */
	default CollectionProducer radialPowerSpectrum(Producer<PackedCollection> power, double... spacing) {
		TraversalPolicy grid = shape(power);
		int cells = grid.getTotalSize();
		int bins = radialBinCount(grid, spacing);

		// Tracked as a follow-up task: replace the dense membership matrix with a segmented reduction
		if ((long) bins * cells > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("radialPowerSpectrum of " + grid + " needs a " + bins + " x " +
					cells + " membership matrix, which exceeds the addressable size");
		}

		CollectionProducer binOfCell = repeat(bins, isolated(radialBin(grid, spacing).reshape(cells))).reshape(bins, cells);
		CollectionProducer bin = repeat(1, cells, integers(0, bins).reshape(bins, 1)).reshape(bins, cells);
		CollectionProducer membership = greaterThan(abs(binOfCell.subtract(bin)), c(0.5), c(0.0), c(1.0));

		CollectionProducer weighted = membership.multiply(repeat(bins, reshape(shape(cells), power)).reshape(bins, cells));
		CollectionProducer total = isolated(weighted.sum(1).reshape(bins));
		CollectionProducer count = isolated(membership.sum(1).reshape(bins));
		return total.divide(count);
	}

	/**
	 * Returns the {@link #radialPowerSpectrum radial bin} of every cell of a grid: the integer
	 * nearest to {@code |k| / dk}, where {@code dk} is the {@link #radialBinWidth radial bin width}.
	 *
	 * @param grid    the grid shape
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return a producer of bin indices, shaped {@code [cells]} in row-major cell order
	 */
	default CollectionProducer radialBin(TraversalPolicy grid, double... spacing) {
		double dk = radialBinWidth(grid, spacing);
		CollectionProducer k = sqrt(wavenumberSquared(integers(0, grid.getTotalSize()), grid, spacing));
		return floor(k.divide(c(dk)).add(c(0.5)));
	}

	/**
	 * Returns the index of the largest bin of a radial spectrum other than bin 0 (the mean).
	 * The result is {@linkplain #isolated isolated}, since it is normally read by several
	 * consumers. Bin 0 is excluded by replacing it with zero, which is below every other bin of
	 * a power spectrum because power is never negative.
	 *
	 * @param radialSpectrum a radial spectrum, shaped {@code [bins]}, with non-negative values
	 * @return a single-element producer of the peak bin index
	 */
	default CollectionProducer spectralPeakBin(Producer<PackedCollection> radialSpectrum) {
		int bins = shape(radialSpectrum).getTotalSize();
		CollectionProducer bin = integers(0, bins);
		CollectionProducer candidates = c(radialSpectrum).reshape(bins)
				.multiply(greaterThan(bin, c(0.5), c(1.0), c(0.0)));
		return isolated(indexOfMax(candidates.reshape(shape(1, bins).traverse(1))).reshape(1));
	}

	/**
	 * Locates the peak of a radial spectrum, excluding bin 0 (the mean), with sub-bin precision by
	 * fitting a parabola through the {@link #spectralPeakBin peak bin} and its two neighbours. The
	 * interpolated offset is applied only where the three bins form a true maximum, and is clamped
	 * to half a bin.
	 *
	 * <p>The spectrum is read by the maximum search and three gathers, so it is
	 * {@linkplain #isolated isolated}: computed once, as its own kernel, rather than embedded in
	 * every expression that reads it. Embedding a radial spectrum, which is a reduction over every
	 * grid cell, into each of those reads makes the generated code too large to compile.</p>
	 *
	 * <p>A parabola through bin centres is exact for a spectrum that is locally quadratic in the
	 * bin index. For the spectrum of a field on a grid, {@link #dominantWavenumber} is more
	 * accurate, because it uses the exact wavenumber of every cell rather than the bin centres.</p>
	 *
	 * @param radialSpectrum a radial spectrum, shaped {@code [bins]}
	 * @param binWidth       the wavenumber represented by one bin (see {@link #radialBinWidth})
	 * @return a single-element producer of the peak wavenumber, {@code (b + delta) * binWidth}
	 * @throws IllegalArgumentException if {@code binWidth} is not positive and finite
	 */
	default CollectionProducer spectralPeak(Producer<PackedCollection> radialSpectrum, double binWidth) {
		if (!(binWidth > 0) || Double.isInfinite(binWidth)) {
			throw new IllegalArgumentException("Radial bin width must be positive and finite, not " + binWidth);
		}

		int bins = shape(radialSpectrum).getTotalSize();
		CollectionProducer spectrum = isolated(c(radialSpectrum).reshape(bins));
		CollectionProducer peak = spectralPeakBin(spectrum);

		CollectionProducer left = greaterThan(peak, c(0.5), peak.subtract(c(1.0)), peak);
		CollectionProducer right = greaterThan(peak, c(bins - 1.5), peak, peak.add(c(1.0)));

		CollectionProducer pl = c(shape(1), spectrum, left);
		CollectionProducer pc = c(shape(1), spectrum, peak);
		CollectionProducer pr = c(shape(1), spectrum, right);

		CollectionProducer curvature = pl.subtract(pc.multiply(c(2.0))).add(pr);
		CollectionProducer safeCurvature = greaterThan(c(0.0), curvature, curvature, c(-1.0));
		CollectionProducer offset = greaterThan(c(0.0), curvature,
				pl.subtract(pr).multiply(c(0.5)).divide(safeCurvature), c(0.0));
		CollectionProducer clamped = greaterThan(offset, c(0.5), c(0.5),
				greaterThan(c(-0.5), offset, c(-0.5), offset));

		return peak.add(clamped).multiply(c(binWidth));
	}

	/**
	 * Estimates the dominant angular wavenumber of a real field. The field's mean is removed and
	 * its {@link #radialPowerSpectrum radial power spectrum} locates the {@link #spectralPeakBin
	 * peak bin}. The estimate is then the power-weighted mean of the exact wavenumber magnitude
	 * {@code |k|} of the cells near the peak, computed twice:
	 * <pre>
	 * k1 = sum(P |k|) / sum(P)   over cells with |bin(k) - peak| &lt;= 1
	 * k* = sum(P |k|) / sum(P)   over cells with ||k| - k1| &lt;= 1.5 dk
	 * </pre>
	 * <p>Bin indices are integers, so the first window is evaluated as {@code |bin(k) - peak| < 1.5},
	 * which selects exactly the peak bin and its two neighbours.</p>
	 * <p>Using the exact {@code |k|} of each cell, rather than bin centres, gives sub-bin precision
	 * that does not depend on how the grid's discrete wavevectors fall into shells, which matters
	 * for grids whose axes have different lengths or spacings. The second pass re-centres the
	 * window on the first estimate, removing the bias of a window centred on a whole bin when the
	 * true wavenumber lies between bins.</p>
	 *
	 * @param field   the real field, shaped like a grid
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return a single-element producer of the dominant wavenumber, in radians per unit length
	 */
	default CollectionProducer dominantWavenumber(Producer<PackedCollection> field, double... spacing) {
		TraversalPolicy grid = shape(field);
		int cells = grid.getTotalSize();
		double dk = radialBinWidth(grid, spacing);

		CollectionProducer values = c(field).reshape(cells);
		CollectionProducer mean = isolated(isolated(values.sum()).divide(c(cells)));
		CollectionProducer centred = values.subtract(mean).reshape(grid);
		CollectionProducer power = isolated(powerSpectrum(fftn(toComplex(centred), false)).reshape(cells));
		CollectionProducer peak = spectralPeakBin(radialPowerSpectrum(power.reshape(grid), spacing));

		CollectionProducer k = isolated(sqrt(wavenumberSquared(integers(0, cells), grid, spacing)).reshape(cells));
		CollectionProducer bin = radialBin(grid, spacing).reshape(cells);
		CollectionProducer first = powerCentroid(power, k,
				greaterThan(abs(bin.subtract(peak)), c(1.5), c(0.0), c(1.0)));
		return powerCentroid(power, k,
				greaterThan(abs(k.subtract(first)), c(1.5 * dk), c(0.0), c(1.0)));
	}

	/**
	 * Returns the power-weighted mean wavenumber magnitude over the cells selected by a window.
	 *
	 * @param power  the power of each cell, shaped {@code [cells]}
	 * @param k      the wavenumber magnitude of each cell, shaped {@code [cells]}
	 * @param window 1 for selected cells and 0 elsewhere, shaped {@code [cells]}
	 * @return an isolated single-element producer of {@code sum(P w |k|) / sum(P w)}
	 */
	private CollectionProducer powerCentroid(CollectionProducer power, CollectionProducer k,
											 CollectionProducer window) {
		int cells = shape(power).getTotalSize();
		CollectionProducer weight = power.multiply(window).reshape(cells);
		return isolated(isolated(weight.multiply(k).reshape(cells).sum()).divide(isolated(weight.sum())));
	}

	/**
	 * Estimates the dominant wavelength of a real field, {@code 2 pi} divided by its
	 * {@link #dominantWavenumber dominant wavenumber}, in the units of {@code spacing}.
	 *
	 * @param field   the real field, shaped like a grid
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return a single-element producer of the dominant wavelength
	 */
	default CollectionProducer dominantWavelength(Producer<PackedCollection> field, double... spacing) {
		return c(2 * Math.PI).divide(dominantWavenumber(field, spacing));
	}

	/**
	 * Solves the periodic Poisson equation {@code laplacian(p) = f} spectrally: every Fourier mode
	 * of {@code f} is divided by {@code -|k|^2}. The {@code k = 0} mode is set to zero explicitly,
	 * so the solution has zero mean; a periodic solution exists only when {@code f} has zero mean,
	 * and any mean in {@code f} is discarded.
	 *
	 * <p>The symbol is computed per interleaved complex element, whose flat index is decoded with
	 * floating-point arithmetic, so the grid may have at most {@code MAX_EXACT_INDEX / 2} cells.</p>
	 *
	 * @param source  the right-hand side {@code f}, shaped like a grid
	 * @param spacing the sample spacing along each axis (or one value for all axes)
	 * @return a producer of the zero-mean solution {@code p}, shaped like {@code source}
	 * @throws IllegalArgumentException if the grid has more than {@code MAX_EXACT_INDEX / 2} cells
	 */
	default CollectionProducer poissonSolve(Producer<PackedCollection> source, double... spacing) {
		TraversalPolicy grid = shape(source);
		int cells = grid.getTotalSize();
		requireExactIndices("poissonSolve", 2L * cells);

		CollectionProducer element = integers(0, 2 * cells);
		CollectionProducer cell = element.subtract(element.mod(2.0)).divide(c(2.0));
		CollectionProducer k2 = wavenumberSquared(cell, grid, spacing);

		double smallest = 0.5 * Math.pow(radialBinWidth(grid, spacing), 2);
		CollectionProducer nonzero = greaterThan(k2, c(smallest), c(1.0), c(0.0));
		CollectionProducer symbol = nonzero.minus().divide(k2.add(c(1.0)).subtract(nonzero));

		CollectionProducer spectrum = fftn(toComplex(source), false)
				.multiply(symbol.reshape(grid.appendDimension(2)));
		return realPart(fftn(spectrum, true));
	}

	/**
	 * Returns the shape of a complex grid, validating that its last dimension holds
	 * interleaved (real, imaginary) pairs.
	 *
	 * @param complex the complex grid
	 * @return its shape
	 * @throws IllegalArgumentException if the shape is not {@code [n0, ..., nk, 2]} with k >= 0
	 */
	private TraversalPolicy complexGridShape(Producer<PackedCollection> complex) {
		TraversalPolicy shape = shape(complex);
		if (shape.getDimensions() < 2 || shape.length(shape.getDimensions() - 1) != 2) {
			throw new IllegalArgumentException("A complex grid must have shape [n0, ..., nk, 2], not " + shape);
		}

		return shape;
	}

	/**
	 * Validates that a producer is a 2D complex grid.
	 *
	 * @param complex the complex grid
	 * @return the same producer
	 * @throws IllegalArgumentException if the shape is not {@code [rows, cols, 2]}
	 */
	private Producer<PackedCollection> require2d(Producer<PackedCollection> complex) {
		if (complexGridShape(complex).getDimensions() != 3) {
			throw new IllegalArgumentException("A 2D complex grid must have shape [rows, cols, 2], not " +
					shape(complex));
		}

		return complex;
	}

	/**
	 * Extracts one component of every value of interleaved complex data, as a strided
	 * subset of the {@code [count, 2]} view of the data.
	 *
	 * @param complex   complex data shaped {@code [..., 2]}
	 * @param component 0 for the real part, 1 for the imaginary part
	 * @return the component, shaped like {@code complex} without its last dimension
	 *         ({@code [1]} for a single value)
	 * @throws IllegalArgumentException if the last dimension of {@code complex} is not 2
	 */
	private CollectionProducer complexComponent(Producer<PackedCollection> complex, int component) {
		TraversalPolicy shape = shape(complex);
		int dims = shape.getDimensions();
		if (shape.length(dims - 1) != 2) {
			throw new IllegalArgumentException("Complex data must have an interleaved final dimension of 2, not " + shape);
		}

		int count = shape.getTotalSize() / 2;
		TraversalPolicy result = dims == 1 ? shape(1) :
				shape(IntStream.range(0, dims - 1).map(shape::length).toArray());
		return subset(shape(count, 1), reshape(shape(count, 2), complex), 0, component).reshape(result);
	}

	/**
	 * Validates that every index in {@code [0, count)} is represented exactly by a single
	 * precision float, so that decoding it with floating-point arithmetic is exact.
	 *
	 * @param operation the operation that decodes the indices, for the error message
	 * @param count     the number of indices decoded
	 * @throws IllegalArgumentException if {@code count} exceeds {@link #MAX_EXACT_INDEX}
	 */
	private static void requireExactIndices(String operation, long count) {
		if (count > MAX_EXACT_INDEX) {
			throw new IllegalArgumentException(operation + " decodes " + count +
					" flat indices, but only " + MAX_EXACT_INDEX + " are exact in single precision");
		}
	}

	/**
	 * Expands a spacing argument to one value per grid axis.
	 *
	 * @param grid    the grid shape
	 * @param spacing one value per axis, or a single value for every axis
	 * @return one spacing per axis
	 * @throws IllegalArgumentException if the number of values matches neither 1 nor the grid rank,
	 *                                  any spacing is not positive and finite, or any axis extent
	 *                                  {@code n_a * d_a} or wavenumber {@code 2 pi / d_a} overflows
	 */
	private double[] gridSpacing(TraversalPolicy grid, double... spacing) {
		int dims = grid.getDimensions();
		if (spacing.length != 1 && spacing.length != dims) {
			throw new IllegalArgumentException("Expected 1 or " + dims + " spacings, not " + spacing.length);
		}

		double[] d = IntStream.range(0, dims).mapToDouble(a -> spacing[spacing.length == 1 ? 0 : a]).toArray();
		if (IntStream.range(0, dims).anyMatch(a -> !(d[a] > 0) || !Double.isFinite(d[a]))) {
			throw new IllegalArgumentException("Grid spacing must be positive and finite");
		}

		if (IntStream.range(0, dims).anyMatch(a ->
				!Double.isFinite(grid.length(a) * d[a]) || !Double.isFinite(2 * Math.PI / d[a]))) {
			throw new IllegalArgumentException("Grid spacing must give a finite extent and wavenumber on " + grid);
		}

		return d;
	}
}
