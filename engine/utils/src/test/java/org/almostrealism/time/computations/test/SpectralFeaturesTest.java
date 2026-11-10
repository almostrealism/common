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

package org.almostrealism.time.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.time.SpectralFeatures;
import org.almostrealism.time.computations.FourierTransform;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.function.Supplier;

/**
 * Tests for the iterative {@link FourierTransform} kernel and the multidimensional spectral
 * tools of {@link SpectralFeatures}: round trips on CPU and GPU, agreement with a direct DFT,
 * plane wave bin placement, dominant wavelength recovery and the spectral Poisson solve.
 *
 * <p>Tolerances are chosen for FP32 execution (the precision of Metal, and of every backend on a
 * host configured for FP32): they are several times the error observed in FP32 and remain valid,
 * with a wide margin, under FP64.</p>
 */
public class SpectralFeaturesTest extends TestSuiteBase implements SpectralFeatures {

	/** Round trip tolerance, absolute, for signals of unit amplitude. */
	private static final double ROUND_TRIP_TOLERANCE = 1e-4;

	/**
	 * Relative tolerance for recovering a planted wavelength. The planted waves are deliberately
	 * not harmonics of the grid, so their power leaks into neighbouring wavevectors; the observed
	 * error is about 1% for the noisy plane wave and 2% for the hexagonal pattern (in FP32), and
	 * half a radial bin is 6-8% at the planted wavelengths.
	 */
	private static final double WAVELENGTH_TOLERANCE = 0.03;

	/**
	 * Evaluates a producer with the given compute requirement active for both compilation and
	 * execution.
	 *
	 * @param requirement the requirement to apply
	 * @param producer    the producer to evaluate
	 * @return the evaluated result
	 */
	protected PackedCollection evaluate(ComputeRequirement requirement, Supplier<Producer<PackedCollection>> producer) {
		DefaultComputer computer = Hardware.getLocalHardware().getComputer();
		computer.pushRequirements(List.of(requirement));

		try {
			return producer.get().get().evaluate();
		} finally {
			computer.popRequirements();
		}
	}

	/**
	 * Returns the coordinate of every cell of a {@code [rows, cols]} grid along one axis, in
	 * sample units: the row index for axis 0 and the column index for axis 1.
	 *
	 * @param rows the number of rows
	 * @param cols the number of columns
	 * @param axis 0 for rows, 1 for columns
	 * @return a producer shaped {@code [rows, cols]}
	 */
	protected CollectionProducer coordinate(int rows, int cols, int axis) {
		CollectionProducer index = integers(0, rows * cols);
		CollectionProducer col = index.mod((double) cols);
		CollectionProducer value = axis == 0 ? index.subtract(col).divide(c(cols)) : col;
		return value.reshape(rows, cols);
	}

	/**
	 * Returns a deterministic, non-trivial complex test signal of the given shape: a sum of
	 * incommensurate sinusoids of the flat element index.
	 *
	 * @param shape the complex shape, ending in 2
	 * @return a producer of the signal
	 */
	protected CollectionProducer testSignal(TraversalPolicy shape) {
		CollectionProducer n = integers(0, shape.getTotalSize());
		return sin(n.multiply(c(0.37))).multiply(c(0.6))
				.add(cos(n.multiply(c(1.913))).multiply(c(0.3)))
				.add(sin(n.multiply(c(0.0071))).multiply(c(0.1)))
				.reshape(shape);
	}

	/**
	 * Returns deterministic pseudo-random noise, uniform in {@code [-amplitude / 2, amplitude / 2)},
	 * computed on the device from the fractional part of a rapidly oscillating sinusoid of the
	 * element index.
	 *
	 * @param size      the number of values
	 * @param amplitude the peak-to-peak amplitude
	 * @return a producer of {@code size} noise values
	 */
	protected CollectionProducer noise(int size, double amplitude) {
		CollectionProducer hash = sin(integers(0, size).multiply(c(12.9898))).multiply(c(43758.5453));
		return hash.subtract(floor(hash)).subtract(c(0.5)).multiply(c(amplitude));
	}

	/**
	 * Returns the largest absolute difference between two collections.
	 *
	 * @param a the first collection
	 * @param b the second collection
	 * @return the maximum absolute element-wise difference
	 */
	protected double maxDifference(PackedCollection a, PackedCollection b) {
		return max(abs(cp(a).reshape(a.getShape().getTotalSize())
				.subtract(cp(b).reshape(b.getShape().getTotalSize())))).evaluate().toDouble(0);
	}

	/**
	 * Asserts that {@code actual} is within {@code tolerance} of {@code expected}, relative to
	 * {@code expected}. Unlike {@code assertSimilar}, a NaN result fails.
	 *
	 * @param label     what is being compared, for the failure message
	 * @param expected  the expected value
	 * @param actual    the actual value
	 * @param tolerance the relative tolerance
	 */
	protected void assertRelative(String label, double expected, double actual, double tolerance) {
		Assert.assertEquals(label, expected, actual, tolerance * Math.abs(expected));
	}

	/** One-dimensional batched round trip on the CPU. */
	@Test(timeout = 120000)
	public void roundTrip1dCpu() {
		assertRoundTrip1d(ComputeRequirement.CPU);
	}

	/** One-dimensional batched round trip on the GPU (Metal or OpenCL where present). */
	@Test(timeout = 120000)
	public void roundTrip1dGpu() {
		assertRoundTrip1d(ComputeRequirement.GPU);
	}

	/**
	 * Checks that {@code inverse(forward(x)) == x} for 8 lines of 512 complex samples.
	 *
	 * @param requirement the compute requirement to run under
	 */
	protected void assertRoundTrip1d(ComputeRequirement requirement) {
		int count = 8;
		int bins = 512;

		PackedCollection input = testSignal(shape(count, 2, bins)).evaluate();
		PackedCollection forward = evaluate(requirement,
				() -> new FourierTransform(count, bins, false, cp(input).traverse(1)));
		PackedCollection reversed = evaluate(requirement,
				() -> new FourierTransform(count, bins, true, cp(forward).traverse(1)));

		double error = maxDifference(input, reversed);
		log("roundTrip1d requirement=" + requirement + " maxError=" + error);
		Assert.assertTrue("1-D round trip error " + error, error < ROUND_TRIP_TOLERANCE);
	}

	/**
	 * Compares the forward transform against a direct evaluation of the DFT definition
	 * {@code X[k] = sum_n x[n] e^(-2 pi i k n / N)}, which pins down the sign and normalisation
	 * convention.
	 */
	@Test(timeout = 120000)
	public void forwardMatchesDirectDft() {
		int bins = 64;
		PackedCollection input = testSignal(shape(bins, 2)).evaluate();
		PackedCollection spectrum = new FourierTransform(bins, cp(input)).get().evaluate();

		double worst = 0;
		for (int k = 0; k < bins; k++) {
			double re = 0;
			double im = 0;
			for (int n = 0; n < bins; n++) {
				double angle = -2 * Math.PI * k * n / bins;
				double xr = input.toDouble(2 * n);
				double xi = input.toDouble(2 * n + 1);
				re += xr * Math.cos(angle) - xi * Math.sin(angle);
				im += xr * Math.sin(angle) + xi * Math.cos(angle);
			}

			worst = Math.max(worst, Math.abs(re - spectrum.toDouble(2 * k)));
			worst = Math.max(worst, Math.abs(im - spectrum.toDouble(2 * k + 1)));
		}

		log("forwardMatchesDirectDft maxError=" + worst);
		Assert.assertTrue("Forward transform differs from the DFT by " + worst, worst < 1e-3);
	}

	/** Two-dimensional round trip of a non-square grid on the CPU. */
	@Test(timeout = 120000)
	public void roundTrip2dCpu() {
		assertRoundTrip2d(ComputeRequirement.CPU);
	}

	/** Two-dimensional round trip of a non-square grid on the GPU (Metal or OpenCL where present). */
	@Test(timeout = 120000)
	public void roundTrip2dGpu() {
		assertRoundTrip2d(ComputeRequirement.GPU);
	}

	/**
	 * Checks that {@code ifft2d(fft2d(x)) == x} for a {@code [64, 128]} complex grid, which
	 * exercises both the contiguous (columns) and strided (rows) passes.
	 *
	 * @param requirement the compute requirement to run under
	 */
	protected void assertRoundTrip2d(ComputeRequirement requirement) {
		PackedCollection input = testSignal(shape(64, 128, 2)).evaluate();
		PackedCollection spectrum = evaluate(requirement, () -> fft2d(cp(input)));
		PackedCollection reversed = evaluate(requirement, () -> ifft2d(cp(spectrum)));

		Assert.assertEquals(input.getShape().getTotalSize(), reversed.getShape().getTotalSize());
		double error = maxDifference(input, reversed);
		log("roundTrip2d requirement=" + requirement + " maxError=" + error);
		Assert.assertTrue("2D round trip error " + error, error < ROUND_TRIP_TOLERANCE);
	}

	/** Three-dimensional round trip, showing that the N-dimensional transform needs no new code. */
	@Test(timeout = 120000)
	public void roundTrip3d() {
		PackedCollection input = testSignal(shape(8, 16, 32, 2)).evaluate();
		PackedCollection reversed = fftn(fftn(cp(input), false), true).evaluate();

		double error = maxDifference(input, reversed);
		log("roundTrip3d maxError=" + error);
		Assert.assertTrue("3D round trip error " + error, error < ROUND_TRIP_TOLERANCE);
	}

	/**
	 * The 2D FFT of {@code cos(2 pi (3 x / cols + 5 y / rows))} on a {@code [32, 64]} grid puts all
	 * of its energy in the bins {@code (5, 3)} and {@code (rows - 5, cols - 3)}, each with
	 * amplitude {@code rows * cols / 2}.
	 */
	@Test(timeout = 120000)
	public void planeWaveEnergyBin() {
		int rows = 32;
		int cols = 64;

		CollectionProducer phase = coordinate(rows, cols, 1).multiply(c(2 * Math.PI * 3 / cols))
				.add(coordinate(rows, cols, 0).multiply(c(2 * Math.PI * 5 / rows)));
		PackedCollection power = powerSpectrum(fft2d(toComplex(cos(phase)))).evaluate();
		Assert.assertEquals(rows * cols, power.getShape().getTotalSize());

		double total = power.doubleStream().sum();
		double expected = Math.pow(rows * cols / 2.0, 2);
		double positive = power.toDouble(5 * cols + 3);
		double negative = power.toDouble((rows - 5) * cols + (cols - 3));

		log("planeWaveEnergyBin positive=" + positive + " negative=" + negative + " total=" + total);
		assertRelative("positive bin", expected, positive, 1e-3);
		assertRelative("negative bin", expected, negative, 1e-3);
		Assert.assertTrue((positive + negative) / total > 0.9999);
	}

	/**
	 * A constant power spectrum has a constant radial average, and the radial bin layout of an
	 * {@code n x n} grid with spacing {@code d} has {@code n / 2 + 1} bins of width
	 * {@code 2 pi / (n d)}.
	 */
	@Test(timeout = 120000)
	public void radialSpectrumOfConstantPower() {
		int n = 32;
		double spacing = 0.5;
		TraversalPolicy grid = shape(n, n);

		Assert.assertEquals(n / 2 + 1, radialBinCount(grid, spacing));
		assertRelative("bin width", 2 * Math.PI / (n * spacing), radialBinWidth(grid, spacing), 1e-12);

		PackedCollection radial = radialPowerSpectrum(c(3.0).repeat(n * n).reshape(grid), spacing).evaluate();
		Assert.assertEquals(n / 2 + 1, radial.getShape().getTotalSize());
		radial.doubleStream().forEach(v -> assertRelative("radial bin", 3.0, v, 1e-5));
	}

	/**
	 * The radial spectrum of {@code cos(2 pi 4 x / n)} on an {@code n x n} grid holds all of its
	 * power in bin 4, the average over that shell of the two cells at {@code (0, +-4)}; every
	 * other bin, including bin 0, is empty. This checks the shell membership, which a constant
	 * spectrum cannot.
	 */
	@Test(timeout = 120000)
	public void radialSpectrumOfPlaneWave() {
		int n = 32;
		double spacing = 0.5;
		TraversalPolicy grid = shape(n, n);

		CollectionProducer field = cos(coordinate(n, n, 1).multiply(c(2 * Math.PI * 4 / n)));
		PackedCollection power = powerSpectrum(fft2d(toComplex(field))).evaluate();
		PackedCollection shellCells = radialBin(grid, spacing).evaluate();
		PackedCollection radial = radialPowerSpectrum(cp(power), spacing).evaluate();

		long cellsInShell = shellCells.doubleStream().filter(b -> b == 4.0).count();
		double expected = 2 * Math.pow(n * n / 2.0, 2) / cellsInShell;
		log("radialSpectrumOfPlaneWave cellsInShell=" + cellsInShell + " radial=" + radial.toArrayString());

		for (int b = 0; b < radial.getShape().getTotalSize(); b++) {
			if (b == 4) {
				assertRelative("shell 4", expected, radial.toDouble(b), 1e-4);
			} else {
				Assert.assertEquals("shell " + b, 0.0, radial.toDouble(b), 1e-3 * expected);
			}
		}
	}

	/**
	 * The parabolic peak of a radial spectrum sampled from {@code 10 - (b - 3.3)^2} is at bin 3.3
	 * exactly, and bin 0 is excluded from the search even when it is the largest.
	 */
	@Test(timeout = 60000)
	public void spectralPeakOfParabola() {
		CollectionProducer bin = integers(0, 8);
		CollectionProducer parabola = c(10.0).subtract(bin.subtract(c(3.3)).sq());
		CollectionProducer spectrum = parabola.add(greaterThan(c(0.5), bin, c(100.0), c(0.0)));

		double binWidth = 0.25;
		PackedCollection peak = spectralPeak(spectrum, binWidth).evaluate();
		log("spectralPeakOfParabola peak=" + peak.toDouble(0));
		assertRelative("parabolic peak", 3.3 * binWidth, peak.toDouble(0), 1e-5);
	}

	/**
	 * A single plane wave at a non-integer bin plus uniform noise of comparable amplitude: the
	 * dominant wavelength is recovered within {@link #WAVELENGTH_TOLERANCE} in physical units.
	 */
	@Test(timeout = 120000)
	public void dominantWavelengthWithNoise() {
		int n = 128;
		double spacing = 0.5;
		double wavelength = 7.3;
		double angle = Math.toRadians(30);

		CollectionProducer x = coordinate(n, n, 1).multiply(c(spacing));
		CollectionProducer y = coordinate(n, n, 0).multiply(c(spacing));
		double k = 2 * Math.PI / wavelength;
		CollectionProducer wave = cos(x.multiply(c(k * Math.cos(angle))).add(y.multiply(c(k * Math.sin(angle)))));
		PackedCollection field = wave.add(noise(n * n, 1.5).reshape(n, n)).evaluate();

		PackedCollection result = dominantWavelength(cp(field), spacing).evaluate();
		log("dominantWavelengthWithNoise planted=" + wavelength + " recovered=" + result.toDouble(0));
		assertRelative("wavelength", wavelength, result.toDouble(0), WAVELENGTH_TOLERANCE);
	}

	/**
	 * A hexagonal pattern (three plane waves 120 degrees apart, as produced by a regular
	 * spike array) on a rectangular grid with different spacing per axis: the dominant
	 * wavelength is recovered within {@link #WAVELENGTH_TOLERANCE}.
	 */
	@Test(timeout = 120000)
	public void dominantWavelengthHexagonal() {
		int rows = 128;
		int cols = 128;
		double dy = 0.25;
		double dx = 0.3;
		double wavelength = 6.0;
		double k = 2 * Math.PI / wavelength;

		CollectionProducer x = coordinate(rows, cols, 1).multiply(c(dx));
		CollectionProducer y = coordinate(rows, cols, 0).multiply(c(dy));

		CollectionProducer field = null;
		for (int i = 0; i < 3; i++) {
			double theta = Math.toRadians(10 + 120 * i);
			CollectionProducer wave = cos(x.multiply(c(k * Math.cos(theta))).add(y.multiply(c(k * Math.sin(theta)))));
			field = field == null ? wave : field.add(wave);
		}

		PackedCollection result = dominantWavelength(field, dy, dx).evaluate();
		log("dominantWavelengthHexagonal planted=" + wavelength + " recovered=" + result.toDouble(0));
		assertRelative("wavelength", wavelength, result.toDouble(0), WAVELENGTH_TOLERANCE);
	}

	/**
	 * Manufactured solution of {@code laplacian(p) = f} on the periodic square {@code [0, 2 pi)^2}:
	 * {@code p = sin(x) cos(2y) + 0.5 cos(3x)}, so {@code f = -5 sin(x) cos(2y) - 4.5 cos(3x)}.
	 */
	@Test(timeout = 120000)
	public void poissonManufacturedSolution() {
		int n = 64;
		double spacing = 2 * Math.PI / n;

		CollectionProducer x = coordinate(n, n, 1).multiply(c(spacing));
		CollectionProducer y = coordinate(n, n, 0).multiply(c(spacing));
		CollectionProducer mode = sin(x).multiply(cos(y.multiply(c(2.0))));
		CollectionProducer ripple = cos(x.multiply(c(3.0)));

		PackedCollection expected = mode.add(ripple.multiply(c(0.5))).evaluate();
		CollectionProducer source = mode.multiply(c(-5.0)).add(ripple.multiply(c(-4.5)));
		PackedCollection solution = poissonSolve(source, spacing).evaluate();

		Assert.assertEquals(n * n, solution.getShape().getTotalSize());
		double error = maxDifference(expected, solution);
		log("poissonManufacturedSolution maxError=" + error);
		Assert.assertTrue("Poisson solution error " + error, error < 1e-4);
	}
}
